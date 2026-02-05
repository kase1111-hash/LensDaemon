package com.lensdaemon.encoder

import android.media.MediaFormat
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Frame processor state
 */
enum class ProcessorState {
    IDLE,
    INITIALIZING,
    READY,
    PROCESSING,
    PAUSED,
    STOPPING,
    ERROR
}

/**
 * Bridges camera output to video encoder
 * Manages the flow of frames from Camera2 Surface to MediaCodec encoder
 */
class FrameProcessor(
    private val encoderConfig: EncoderConfig = EncoderConfig.PRESET_1080P
) {
    companion object {
        private const val TAG = "FrameProcessor"
    }

    // Coroutine scope for async operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // State management
    private val _state = MutableStateFlow(ProcessorState.IDLE)
    val state: StateFlow<ProcessorState> = _state.asStateFlow()

    // Encoded frame output
    private val _encodedFrames = MutableSharedFlow<EncodedFrame>(
        replay = 0,
        extraBufferCapacity = 30 // Buffer for backpressure
    )
    val encodedFrames: SharedFlow<EncodedFrame> = _encodedFrames.asSharedFlow()

    // Video encoder
    private var encoder: VideoEncoder? = null

    // Surface for camera to render to
    private var encoderSurface: Surface? = null

    // Frame callbacks
    private val frameListeners = mutableListOf<(EncodedFrame) -> Unit>()

    // Statistics
    private val processedFrames = AtomicLong(0)
    private val droppedFrames = AtomicLong(0)
    private val isRunning = AtomicBoolean(false)

    // Adaptive bitrate support
    private var adaptiveBitrateEnabled = false
    private var currentBitrate = encoderConfig.bitrateBps
    private var minBitrate = encoderConfig.bitrateBps / 4
    private var maxBitrate = encoderConfig.bitrateBps * 2

    /**
     * Initialize the frame processor and encoder
     * @return Surface for camera to render to, or error
     */
    fun initialize(): Result<Surface> {
        if (_state.value != ProcessorState.IDLE) {
            return Result.failure(IllegalStateException("Processor not in IDLE state"))
        }

        _state.value = ProcessorState.INITIALIZING

        try {
            // Create encoder
            encoder = VideoEncoder(encoderConfig)

            // Initialize encoder and get input surface
            val surfaceResult = encoder!!.initialize()
            if (surfaceResult.isFailure) {
                _state.value = ProcessorState.ERROR
                return Result.failure(surfaceResult.exceptionOrNull()!!)
            }

            encoderSurface = surfaceResult.getOrNull()

            // Set up frame callback
            encoder!!.setFrameCallback { frame ->
                handleEncodedFrame(frame)
            }

            _state.value = ProcessorState.READY
            Timber.i("$TAG: Frame processor initialized")

            return Result.success(encoderSurface!!)

        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to initialize frame processor")
            _state.value = ProcessorState.ERROR
            return Result.failure(e)
        }
    }

    /**
     * Start processing frames
     */
    fun start() {
        if (_state.value != ProcessorState.READY && _state.value != ProcessorState.PAUSED) {
            Timber.w("$TAG: Cannot start - not in READY or PAUSED state")
            return
        }

        isRunning.set(true)
        processedFrames.set(0)
        droppedFrames.set(0)

        encoder?.start()

        _state.value = ProcessorState.PROCESSING
        Timber.i("$TAG: Frame processing started")
    }

    /**
     * Stop processing frames
     */
    fun stop() {
        if (_state.value != ProcessorState.PROCESSING && _state.value != ProcessorState.PAUSED) {
            return
        }

        _state.value = ProcessorState.STOPPING
        isRunning.set(false)

        encoder?.stop()

        _state.value = ProcessorState.READY
        Timber.i("$TAG: Frame processing stopped. Processed: ${processedFrames.get()}, Dropped: ${droppedFrames.get()}")
    }

    /**
     * Pause processing (frames continue but encoding pauses)
     */
    fun pause() {
        if (_state.value != ProcessorState.PROCESSING) return

        encoder?.pause()
        _state.value = ProcessorState.PAUSED
        Timber.d("$TAG: Frame processing paused")
    }

    /**
     * Resume processing
     */
    fun resume() {
        if (_state.value != ProcessorState.PAUSED) return

        encoder?.resume()
        _state.value = ProcessorState.PROCESSING
        Timber.d("$TAG: Frame processing resumed")
    }

    /**
     * Release all resources
     */
    fun release() {
        isRunning.set(false)

        encoder?.release()
        encoder = null
        encoderSurface = null

        frameListeners.clear()

        scope.cancel()

        _state.value = ProcessorState.IDLE
        Timber.i("$TAG: Frame processor released")
    }

    /**
     * Get the encoder input surface
     * Camera should render frames to this surface
     */
    fun getEncoderSurface(): Surface? = encoderSurface

    /**
     * Add a listener for encoded frames
     */
    fun addFrameListener(listener: (EncodedFrame) -> Unit) {
        synchronized(frameListeners) {
            frameListeners.add(listener)
        }
    }

    /**
     * Remove a frame listener
     */
    fun removeFrameListener(listener: (EncodedFrame) -> Unit) {
        synchronized(frameListeners) {
            frameListeners.remove(listener)
        }
    }

    /**
     * Request a keyframe (IDR frame)
     */
    fun requestKeyFrame() {
        encoder?.requestKeyFrame()
    }

    /**
     * Update encoding bitrate
     */
    fun updateBitrate(newBitrateBps: Int) {
        currentBitrate = newBitrateBps.coerceIn(minBitrate, maxBitrate)
        encoder?.updateBitrate(currentBitrate)
    }

    /**
     * Enable/disable adaptive bitrate
     */
    fun setAdaptiveBitrate(enabled: Boolean, minBps: Int = minBitrate, maxBps: Int = maxBitrate) {
        adaptiveBitrateEnabled = enabled
        minBitrate = minBps
        maxBitrate = maxBps
        Timber.i("$TAG: Adaptive bitrate ${if (enabled) "enabled" else "disabled"}, range: $minBps - $maxBps")
    }

    /**
     * Notify of network congestion (for adaptive bitrate)
     */
    fun onNetworkCongestion() {
        if (!adaptiveBitrateEnabled) return

        val newBitrate = (currentBitrate * 0.8).toInt().coerceAtLeast(minBitrate)
        if (newBitrate != currentBitrate) {
            updateBitrate(newBitrate)
            Timber.i("$TAG: Reduced bitrate due to congestion: $newBitrate")
        }
    }

    /**
     * Notify of good network conditions (for adaptive bitrate)
     */
    fun onNetworkImproved() {
        if (!adaptiveBitrateEnabled) return

        val newBitrate = (currentBitrate * 1.1).toInt().coerceAtMost(maxBitrate)
        if (newBitrate != currentBitrate) {
            updateBitrate(newBitrate)
            Timber.i("$TAG: Increased bitrate due to good network: $newBitrate")
        }
    }

    /**
     * Get encoder statistics
     */
    fun getEncoderStats(): EncoderStats? = encoder?.stats?.value

    /**
     * Get current encoder state
     */
    fun getEncoderState(): EncoderState = encoder?.state?.value ?: EncoderState.IDLE

    /**
     * Get SPS data for streaming setup
     */
    fun getSps(): ByteArray? = encoder?.getSps()

    /**
     * Get PPS data for streaming setup
     */
    fun getPps(): ByteArray? = encoder?.getPps()

    /**
     * Get VPS data for H.265 streaming setup
     */
    fun getVps(): ByteArray? = encoder?.getVps()

    /**
     * Check if codec config data is available
     */
    fun hasConfigData(): Boolean = encoder?.hasConfigData() ?: false

    /**
     * Get combined config data
     */
    fun getConfigData(): ByteArray? = encoder?.getConfigData()

    /**
     * Get encoder configuration
     */
    fun getConfig(): EncoderConfig = encoderConfig

    /**
     * Get encoder output format (for muxer)
     */
    fun getOutputFormat(): MediaFormat? = encoder?.getOutputFormat()

    /**
     * Get current bitrate
     */
    fun getCurrentBitrate(): Int = currentBitrate

    /**
     * Handle encoded frame from encoder
     */
    private fun handleEncodedFrame(frame: EncodedFrame) {
        processedFrames.incrementAndGet()

        // Emit to flow
        scope.launch {
            val emitted = _encodedFrames.tryEmit(frame)
            if (!emitted) {
                droppedFrames.incrementAndGet()
                Timber.v("$TAG: Frame dropped due to backpressure")
            }
        }

        // Notify listeners
        synchronized(frameListeners) {
            frameListeners.forEach { listener ->
                try {
                    listener(frame)
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Error in frame listener")
                }
            }
        }
    }
}

/**
 * Factory for creating frame processors with common configurations
 */
object FrameProcessorFactory {
    /**
     * Create processor for streaming
     */
    fun createForStreaming(
        resolution: Size = Size(1920, 1080),
        bitrateBps: Int = 4_000_000,
        frameRate: Int = 30
    ): FrameProcessor {
        val config = EncoderConfig(
            codec = VideoCodec.H264,
            resolution = resolution,
            bitrateBps = bitrateBps,
            frameRate = frameRate,
            keyframeIntervalSec = 2,
            bitrateMode = BitrateMode.CBR,
            h264Profile = H264Profile.BASELINE, // Better compatibility for streaming
            lowLatency = true
        )
        return FrameProcessor(config)
    }

    /**
     * Create processor for recording
     */
    fun createForRecording(
        resolution: Size = Size(1920, 1080),
        bitrateBps: Int = 8_000_000,
        frameRate: Int = 30
    ): FrameProcessor {
        val config = EncoderConfig(
            codec = VideoCodec.H264,
            resolution = resolution,
            bitrateBps = bitrateBps,
            frameRate = frameRate,
            keyframeIntervalSec = 1,
            bitrateMode = BitrateMode.VBR,
            h264Profile = H264Profile.HIGH, // Better quality for recording
            lowLatency = false
        )
        return FrameProcessor(config)
    }

    /**
     * Create processor for low bandwidth streaming
     */
    fun createForLowBandwidth(): FrameProcessor {
        return FrameProcessor(EncoderConfig.PRESET_LOW_BANDWIDTH)
    }

    /**
     * Create processor with H.265 (if supported)
     */
    fun createHevc(
        resolution: Size = Size(1920, 1080),
        bitrateBps: Int = 3_000_000,
        frameRate: Int = 30
    ): FrameProcessor? {
        if (!VideoEncoder.isCodecSupported(VideoCodec.H265)) {
            Timber.w("FrameProcessorFactory: H.265 not supported on this device")
            return null
        }

        val config = EncoderConfig(
            codec = VideoCodec.H265,
            resolution = resolution,
            bitrateBps = bitrateBps,
            frameRate = frameRate,
            keyframeIntervalSec = 2,
            lowLatency = true
        )
        return FrameProcessor(config)
    }
}
