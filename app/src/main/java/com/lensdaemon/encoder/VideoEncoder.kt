package com.lensdaemon.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Hardware-accelerated video encoder using MediaCodec
 * Supports H.264 (AVC) and H.265 (HEVC) encoding
 */
class VideoEncoder(
    private val config: EncoderConfig = EncoderConfig.PRESET_1080P
) {
    companion object {
        private const val TAG = "VideoEncoder"
        private const val TIMEOUT_US = 10_000L // 10ms timeout for dequeue operations

        /**
         * Find best encoder for given codec
         */
        fun findEncoder(codec: VideoCodec): MediaCodecInfo? {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            return codecList.codecInfos
                .filter { it.isEncoder }
                .filter { it.supportedTypes.contains(codec.mimeType) }
                .sortedByDescending { info ->
                    // Prefer hardware encoders
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (info.isHardwareAccelerated) 1 else 0
                    } else {
                        // Heuristic: hardware encoders usually have vendor-specific names
                        if (!info.name.startsWith("OMX.google.")) 1 else 0
                    }
                }
                .firstOrNull()
        }

        /**
         * Check if codec is supported
         */
        fun isCodecSupported(codec: VideoCodec): Boolean {
            return findEncoder(codec) != null
        }

        /**
         * Get encoder capabilities for a codec
         */
        fun getCapabilities(codec: VideoCodec): EncoderCapabilities? {
            val encoderInfo = findEncoder(codec) ?: return null
            val capabilities = encoderInfo.getCapabilitiesForType(codec.mimeType)
            val videoCapabilities = capabilities.videoCapabilities

            val supportedResolutions = mutableListOf<Size>()
            val standardResolutions = listOf(
                Size(640, 480),
                Size(1280, 720),
                Size(1920, 1080),
                Size(2560, 1440),
                Size(3840, 2160)
            )
            for (res in standardResolutions) {
                if (videoCapabilities.isSizeSupported(res.width, res.height)) {
                    supportedResolutions.add(res)
                }
            }

            val bitrateRange = videoCapabilities.bitrateRange
            val frameRateRange = videoCapabilities.supportedFrameRates

            val supportedBitrateModes = mutableListOf<BitrateMode>()
            val encoderCapabilities = capabilities.encoderCapabilities
            if (encoderCapabilities.isBitrateModeSupported(BitrateMode.VBR.value)) {
                supportedBitrateModes.add(BitrateMode.VBR)
            }
            if (encoderCapabilities.isBitrateModeSupported(BitrateMode.CBR.value)) {
                supportedBitrateModes.add(BitrateMode.CBR)
            }
            if (encoderCapabilities.isBitrateModeSupported(BitrateMode.CQ.value)) {
                supportedBitrateModes.add(BitrateMode.CQ)
            }

            val supportedProfiles = capabilities.profileLevels.map { it.profile }.distinct()

            val isHardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                encoderInfo.isHardwareAccelerated
            } else {
                !encoderInfo.name.startsWith("OMX.google.")
            }

            return EncoderCapabilities(
                codec = codec,
                codecName = encoderInfo.name,
                isHardwareAccelerated = isHardware,
                supportedResolutions = supportedResolutions,
                maxResolution = Size(
                    videoCapabilities.supportedWidths.upper,
                    videoCapabilities.supportedHeights.upper
                ),
                minResolution = Size(
                    videoCapabilities.supportedWidths.lower,
                    videoCapabilities.supportedHeights.lower
                ),
                supportedBitrateModes = supportedBitrateModes,
                bitrateRange = bitrateRange.lower..bitrateRange.upper,
                supportedFrameRates = frameRateRange.lower.toInt()..frameRateRange.upper.toInt(),
                supportedProfiles = supportedProfiles
            )
        }
    }

    // State management
    private val _state = MutableStateFlow(EncoderState.IDLE)
    val state: StateFlow<EncoderState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(EncoderStats())
    val stats: StateFlow<EncoderStats> = _stats.asStateFlow()

    // MediaCodec instance
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null

    // NAL parser for extracting SPS/PPS
    private val nalParser = NalUnitParser(isHevc = config.codec == VideoCodec.H265)

    // Encoder thread
    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null

    // Callback for encoded frames
    private var frameCallback: ((EncodedFrame) -> Unit)? = null

    // Statistics tracking
    private val framesEncoded = AtomicLong(0)
    private val framesDropped = AtomicLong(0)
    private val totalBytes = AtomicLong(0)
    private var encodingStartTime = 0L
    private var lastStatsUpdate = 0L
    private var recentFrameTimes = mutableListOf<Long>()

    // Control flags
    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    /**
     * Initialize the encoder and create input surface
     * @return Input Surface for camera to render to
     */
    fun initialize(): Result<Surface> {
        if (_state.value != EncoderState.IDLE) {
            return Result.failure(IllegalStateException("Encoder not in IDLE state"))
        }

        _state.value = EncoderState.CONFIGURING

        try {
            // Find encoder
            val encoderInfo = findEncoder(config.codec)
                ?: return Result.failure(EncoderError.CodecNotSupported(config.codec))

            Timber.i("$TAG: Using encoder: ${encoderInfo.name}")

            // Validate resolution
            val capabilities = encoderInfo.getCapabilitiesForType(config.codec.mimeType)
            val videoCapabilities = capabilities.videoCapabilities
            if (!videoCapabilities.isSizeSupported(config.width, config.height)) {
                return Result.failure(EncoderError.ResolutionNotSupported(config.resolution))
            }

            // Create MediaCodec
            mediaCodec = MediaCodec.createByCodecName(encoderInfo.name)

            // Configure with format
            val format = config.toMediaFormat()
            Timber.d("$TAG: Configuring with format: $format")

            val codec = mediaCodec ?: return Result.failure(
                IllegalStateException("MediaCodec creation returned null")
            )

            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            // Create input surface
            inputSurface = codec.createInputSurface()

            // Setup encoder callback thread
            encoderThread = HandlerThread("EncoderThread").apply { start() }
            encoderHandler = Handler(encoderThread!!.looper)

            // Set async callback
            codec.setCallback(encoderCallback, encoderHandler)

            _state.value = EncoderState.READY
            Timber.i("$TAG: Encoder initialized, resolution=${config.width}x${config.height}, bitrate=${config.bitrateBps}")

            val surface = inputSurface ?: return Result.failure(
                IllegalStateException("Failed to create encoder input surface")
            )
            return Result.success(surface)

        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to initialize encoder")
            _state.value = EncoderState.ERROR
            release()
            return Result.failure(EncoderError.ConfigurationError(e.message ?: "Unknown error"))
        }
    }

    /**
     * Start encoding
     */
    fun start() {
        if (_state.value != EncoderState.READY) {
            Timber.w("$TAG: Cannot start - not in READY state")
            return
        }

        try {
            isRunning.set(true)
            isPaused.set(false)
            framesEncoded.set(0)
            framesDropped.set(0)
            totalBytes.set(0)
            encodingStartTime = System.currentTimeMillis()
            lastStatsUpdate = encodingStartTime
            recentFrameTimes.clear()

            mediaCodec?.start()

            _state.value = EncoderState.ENCODING
            _stats.value = EncoderStats(startTimeMs = encodingStartTime)

            Timber.i("$TAG: Encoding started")

        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start encoder")
            _state.value = EncoderState.ERROR
        }
    }

    /**
     * Stop encoding
     */
    fun stop() {
        if (_state.value != EncoderState.ENCODING) {
            return
        }

        _state.value = EncoderState.STOPPING
        isRunning.set(false)

        try {
            // Signal end of stream
            mediaCodec?.signalEndOfInputStream()

            // Stop codec
            mediaCodec?.stop()

            _state.value = EncoderState.READY
            Timber.i("$TAG: Encoding stopped. Frames: ${framesEncoded.get()}, Dropped: ${framesDropped.get()}")

        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error stopping encoder")
            _state.value = EncoderState.ERROR
        }
    }

    /**
     * Pause encoding (frames will be dropped)
     */
    fun pause() {
        isPaused.set(true)
        Timber.d("$TAG: Encoding paused")
    }

    /**
     * Resume encoding
     */
    fun resume() {
        isPaused.set(false)
        Timber.d("$TAG: Encoding resumed")
    }

    /**
     * Release all resources
     */
    fun release() {
        isRunning.set(false)

        try {
            mediaCodec?.stop()
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Error stopping codec during release")
        }

        try {
            mediaCodec?.release()
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Error releasing codec")
        }

        inputSurface?.release()
        inputSurface = null
        mediaCodec = null

        encoderThread?.quitSafely()
        encoderThread = null
        encoderHandler = null

        nalParser.clearCache()

        _state.value = EncoderState.IDLE
        Timber.i("$TAG: Encoder released")
    }

    /**
     * Set callback for encoded frames
     */
    fun setFrameCallback(callback: (EncodedFrame) -> Unit) {
        frameCallback = callback
    }

    /**
     * Request a keyframe (IDR frame)
     */
    fun requestKeyFrame() {
        if (_state.value != EncoderState.ENCODING) return

        try {
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            mediaCodec?.setParameters(params)
            Timber.d("$TAG: Keyframe requested")
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to request keyframe")
        }
    }

    /**
     * Update bitrate dynamically
     */
    fun updateBitrate(newBitrateBps: Int) {
        if (_state.value != EncoderState.ENCODING) return

        try {
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrateBps)
            }
            mediaCodec?.setParameters(params)
            Timber.i("$TAG: Bitrate updated to $newBitrateBps bps")
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to update bitrate")
        }
    }

    /**
     * Get input surface (for camera to render to)
     */
    fun getInputSurface(): Surface? = inputSurface

    /**
     * Get cached SPS data
     */
    fun getSps(): ByteArray? = nalParser.sps

    /**
     * Get cached PPS data
     */
    fun getPps(): ByteArray? = nalParser.pps

    /**
     * Get cached VPS data (H.265 only)
     */
    fun getVps(): ByteArray? = nalParser.vps

    /**
     * Check if we have codec config data
     */
    fun hasConfigData(): Boolean = nalParser.hasConfigData()

    /**
     * Get combined config data (SPS+PPS or VPS+SPS+PPS)
     */
    fun getConfigData(): ByteArray? = nalParser.getConfigData()

    /**
     * Get encoder output format (for muxer configuration)
     * Contains codec-specific data like SPS/PPS
     */
    fun getOutputFormat(): MediaFormat? {
        return try {
            mediaCodec?.outputFormat
        } catch (e: Exception) {
            Timber.w("$TAG: Output format not yet available")
            null
        }
    }

    /**
     * MediaCodec async callback
     */
    private val encoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Surface mode - inputs come from Surface, not buffers
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            if (!isRunning.get()) {
                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (e: Exception) {
                    // Ignore
                }
                return
            }

            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    processEncodedBuffer(buffer, info)
                }
                codec.releaseOutputBuffer(index, false)

            } catch (e: Exception) {
                Timber.e(e, "$TAG: Error processing output buffer")
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Timber.e(e, "$TAG: Codec error: ${e.diagnosticInfo}")
            _state.value = EncoderState.ERROR
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Timber.i("$TAG: Output format changed: $format")
        }
    }

    /**
     * Process encoded buffer and dispatch to callback
     */
    private fun processEncodedBuffer(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (isPaused.get()) {
            framesDropped.incrementAndGet()
            return
        }

        val frameStartTime = System.nanoTime()

        // Copy data from buffer
        val data = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.get(data, 0, info.size)

        // Parse NAL units (caches SPS/PPS)
        nalParser.parse(data)

        // Create encoded frame
        val frame = EncodedFrame(
            data = data,
            presentationTimeUs = info.presentationTimeUs,
            flags = info.flags,
            size = info.size
        )

        // Update statistics
        val frameCount = framesEncoded.incrementAndGet()
        totalBytes.addAndGet(info.size.toLong())

        val frameEndTime = System.nanoTime()
        val encodingTimeMs = (frameEndTime - frameStartTime) / 1_000_000f
        recentFrameTimes.add(System.currentTimeMillis())

        // Update stats periodically (every 500ms)
        val now = System.currentTimeMillis()
        if (now - lastStatsUpdate > 500) {
            updateStats(encodingTimeMs)
            lastStatsUpdate = now
        }

        // Dispatch to callback
        try {
            frameCallback?.invoke(frame)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error in frame callback")
        }

        if (frame.isKeyFrame) {
            Timber.v("$TAG: Keyframe encoded, size=${info.size}")
        }
    }

    /**
     * Update encoder statistics
     */
    private fun updateStats(lastEncodingTimeMs: Float) {
        // Calculate FPS from recent frames
        val now = System.currentTimeMillis()
        val oneSecondAgo = now - 1000
        recentFrameTimes.removeAll { it < oneSecondAgo }
        val currentFps = recentFrameTimes.size.toFloat()

        // Calculate average bitrate
        val durationSec = (now - encodingStartTime) / 1000f
        val avgBitrate = if (durationSec > 0) {
            (totalBytes.get() * 8 / durationSec).toLong()
        } else 0L

        _stats.value = EncoderStats(
            framesEncoded = framesEncoded.get(),
            framesDropped = framesDropped.get(),
            currentBitrateBps = avgBitrate,
            avgEncodingTimeMs = lastEncodingTimeMs,
            totalBytesEncoded = totalBytes.get(),
            startTimeMs = encodingStartTime,
            currentFps = currentFps
        )
    }
}
