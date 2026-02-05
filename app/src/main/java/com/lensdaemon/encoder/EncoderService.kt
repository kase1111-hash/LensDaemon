package com.lensdaemon.encoder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.media.MediaFormat
import android.util.Size
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.lensdaemon.MainActivity
import com.lensdaemon.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Foreground service for video encoding
 * Manages the lifecycle of the video encoder and frame processor
 */
class EncoderService : Service() {

    companion object {
        private const val TAG = "EncoderService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "encoder_service_channel"

        // Intent actions
        const val ACTION_START_ENCODING = "com.lensdaemon.action.START_ENCODING"
        const val ACTION_STOP_ENCODING = "com.lensdaemon.action.STOP_ENCODING"
        const val ACTION_REQUEST_KEYFRAME = "com.lensdaemon.action.REQUEST_KEYFRAME"

        // Intent extras
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_BITRATE = "extra_bitrate"
        const val EXTRA_FRAMERATE = "extra_framerate"
        const val EXTRA_CODEC = "extra_codec"

        /**
         * Create intent to start encoding
         */
        fun createStartIntent(
            context: Context,
            width: Int = 1920,
            height: Int = 1080,
            bitrateBps: Int = 4_000_000,
            frameRate: Int = 30,
            codec: VideoCodec = VideoCodec.H264
        ): Intent {
            return Intent(context, EncoderService::class.java).apply {
                action = ACTION_START_ENCODING
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_BITRATE, bitrateBps)
                putExtra(EXTRA_FRAMERATE, frameRate)
                putExtra(EXTRA_CODEC, codec.name)
            }
        }

        /**
         * Create intent to stop encoding
         */
        fun createStopIntent(context: Context): Intent {
            return Intent(context, EncoderService::class.java).apply {
                action = ACTION_STOP_ENCODING
            }
        }
    }

    // Service binder
    private val binder = EncoderBinder()

    inner class EncoderBinder : Binder() {
        fun getService(): EncoderService = this@EncoderService
    }

    // Coroutine scope
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Frame processor
    private var frameProcessor: FrameProcessor? = null

    // State
    private val _isEncoding = MutableStateFlow(false)
    val isEncoding: StateFlow<Boolean> = _isEncoding.asStateFlow()

    private val _encoderState = MutableStateFlow(EncoderState.IDLE)
    val encoderState: StateFlow<EncoderState> = _encoderState.asStateFlow()

    // Current configuration
    private var currentConfig: EncoderConfig? = null

    // Frame listeners (for RTSP server, file writer, etc.)
    private val frameListeners = mutableListOf<(EncodedFrame) -> Unit>()

    override fun onCreate() {
        super.onCreate()
        Timber.i("$TAG: Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("$TAG: onStartCommand action=${intent?.action}")

        when (intent?.action) {
            ACTION_START_ENCODING -> {
                val width = intent.getIntExtra(EXTRA_WIDTH, 1920)
                val height = intent.getIntExtra(EXTRA_HEIGHT, 1080)
                val bitrate = intent.getIntExtra(EXTRA_BITRATE, 4_000_000)
                val frameRate = intent.getIntExtra(EXTRA_FRAMERATE, 30)
                val codecName = intent.getStringExtra(EXTRA_CODEC) ?: VideoCodec.H264.name
                val codec = VideoCodec.valueOf(codecName)

                val config = EncoderConfig(
                    codec = codec,
                    resolution = Size(width, height),
                    bitrateBps = bitrate,
                    frameRate = frameRate
                )

                startForeground(NOTIFICATION_ID, createNotification("Initializing encoder..."))
                initializeEncoder(config)
            }
            ACTION_STOP_ENCODING -> {
                stopEncoding()
            }
            ACTION_REQUEST_KEYFRAME -> {
                frameProcessor?.requestKeyFrame()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseEncoder()
        serviceScope.cancel()
        Timber.i("$TAG: Service destroyed")
    }

    /**
     * Initialize encoder with configuration
     * @return Surface for camera to render to
     */
    fun initializeEncoder(config: EncoderConfig): Surface? {
        Timber.i("$TAG: Initializing encoder with ${config.width}x${config.height} @ ${config.bitrateBps}bps")

        // Release existing encoder
        releaseEncoder()

        currentConfig = config
        _encoderState.value = EncoderState.CONFIGURING

        // Create frame processor
        frameProcessor = FrameProcessor(config)

        // Initialize and get surface
        val result = frameProcessor!!.initialize()

        return if (result.isSuccess) {
            _encoderState.value = EncoderState.READY
            updateNotification("Encoder ready: ${config.width}x${config.height}")

            // Set up frame listener to dispatch to registered listeners
            frameProcessor!!.addFrameListener { frame ->
                dispatchFrame(frame)
            }

            // Observe encoder state
            observeEncoderState()

            result.getOrNull()
        } else {
            _encoderState.value = EncoderState.ERROR
            Timber.e("$TAG: Failed to initialize encoder: ${result.exceptionOrNull()}")
            null
        }
    }

    /**
     * Start encoding
     */
    fun startEncoding() {
        if (frameProcessor == null) {
            Timber.w("$TAG: Cannot start - encoder not initialized")
            return
        }

        frameProcessor?.start()
        _isEncoding.value = true
        _encoderState.value = EncoderState.ENCODING
        updateNotification("Encoding: ${currentConfig?.width}x${currentConfig?.height}")
        Timber.i("$TAG: Encoding started")
    }

    /**
     * Stop encoding
     */
    fun stopEncoding() {
        frameProcessor?.stop()
        _isEncoding.value = false
        _encoderState.value = EncoderState.READY
        updateNotification("Encoder paused")
        Timber.i("$TAG: Encoding stopped")
    }

    /**
     * Pause encoding
     */
    fun pauseEncoding() {
        frameProcessor?.pause()
        updateNotification("Encoding paused")
    }

    /**
     * Resume encoding
     */
    fun resumeEncoding() {
        frameProcessor?.resume()
        updateNotification("Encoding: ${currentConfig?.width}x${currentConfig?.height}")
    }

    /**
     * Release encoder resources
     */
    fun releaseEncoder() {
        frameProcessor?.release()
        frameProcessor = null
        _isEncoding.value = false
        _encoderState.value = EncoderState.IDLE
        currentConfig = null
        Timber.i("$TAG: Encoder released")
    }

    /**
     * Get encoder surface for camera to render to
     */
    fun getEncoderSurface(): Surface? = frameProcessor?.getEncoderSurface()

    /**
     * Request a keyframe
     */
    fun requestKeyFrame() {
        frameProcessor?.requestKeyFrame()
    }

    /**
     * Update encoding bitrate
     */
    fun updateBitrate(newBitrateBps: Int) {
        frameProcessor?.updateBitrate(newBitrateBps)
        updateNotification("Encoding: ${currentConfig?.width}x${currentConfig?.height} @ ${newBitrateBps / 1000}kbps")
    }

    /**
     * Enable adaptive bitrate
     */
    fun setAdaptiveBitrate(enabled: Boolean, minBps: Int, maxBps: Int) {
        frameProcessor?.setAdaptiveBitrate(enabled, minBps, maxBps)
    }

    /**
     * Notify of network congestion
     */
    fun onNetworkCongestion() {
        frameProcessor?.onNetworkCongestion()
    }

    /**
     * Notify of network improvement
     */
    fun onNetworkImproved() {
        frameProcessor?.onNetworkImproved()
    }

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
     * Get SPS data
     */
    fun getSps(): ByteArray? = frameProcessor?.getSps()

    /**
     * Get PPS data
     */
    fun getPps(): ByteArray? = frameProcessor?.getPps()

    /**
     * Get VPS data (H.265)
     */
    fun getVps(): ByteArray? = frameProcessor?.getVps()

    /**
     * Check if config data is available
     */
    fun hasConfigData(): Boolean = frameProcessor?.hasConfigData() ?: false

    /**
     * Get combined config data
     */
    fun getConfigData(): ByteArray? = frameProcessor?.getConfigData()

    /**
     * Get encoder statistics
     */
    fun getStats(): EncoderStats? = frameProcessor?.getEncoderStats()

    /**
     * Get current configuration
     */
    fun getConfig(): EncoderConfig? = currentConfig

    /**
     * Get output format from encoder (for muxer configuration)
     */
    fun getOutputFormat(): MediaFormat? = frameProcessor?.getOutputFormat()

    /**
     * Get current bitrate
     */
    fun getCurrentBitrate(): Int = frameProcessor?.getCurrentBitrate() ?: 0

    /**
     * Get encoder capabilities for a codec
     */
    fun getEncoderCapabilities(codec: VideoCodec): EncoderCapabilities? {
        return VideoEncoder.getCapabilities(codec)
    }

    /**
     * Check if codec is supported
     */
    fun isCodecSupported(codec: VideoCodec): Boolean {
        return VideoEncoder.isCodecSupported(codec)
    }

    /**
     * Dispatch encoded frame to all listeners
     */
    private fun dispatchFrame(frame: EncodedFrame) {
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

    /**
     * Observe encoder state changes
     */
    private fun observeEncoderState() {
        serviceScope.launch {
            frameProcessor?.state?.collect { state ->
                when (state) {
                    ProcessorState.ERROR -> {
                        _encoderState.value = EncoderState.ERROR
                        updateNotification("Encoder error")
                    }
                    ProcessorState.PROCESSING -> {
                        _encoderState.value = EncoderState.ENCODING
                    }
                    ProcessorState.PAUSED -> {
                        updateNotification("Encoding paused")
                    }
                    else -> { /* ignore */ }
                }
            }
        }
    }

    /**
     * Create notification channel
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Encoder Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Video encoding status"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Create notification
     */
    private fun createNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LensDaemon Encoder")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * Update notification
     */
    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(status))
    }
}
