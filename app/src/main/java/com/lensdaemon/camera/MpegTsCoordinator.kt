package com.lensdaemon.camera

import com.lensdaemon.encoder.EncodedFrame
import com.lensdaemon.encoder.VideoCodec
import com.lensdaemon.output.MpegTsMode
import com.lensdaemon.output.MpegTsUdpConfig
import com.lensdaemon.output.MpegTsUdpPublisher
import com.lensdaemon.output.MpegTsUdpStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Coordinates MPEG-TS/UDP publisher lifecycle and frame distribution.
 *
 * Extracted from CameraService to keep transport concerns isolated.
 */
class MpegTsCoordinator {

    private var publisher: MpegTsUdpPublisher? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Frame listener that forwards encoded frames to the publisher */
    val frameListener: (EncodedFrame) -> Unit = { frame ->
        publisher?.sendFrame(frame)
    }

    fun start(
        config: MpegTsUdpConfig = MpegTsUdpConfig(),
        codec: VideoCodec = VideoCodec.H264,
        sps: ByteArray? = null,
        pps: ByteArray? = null,
        vps: ByteArray? = null
    ): Boolean {
        if (publisher?.isRunning() == true) {
            Timber.w("MPEG-TS/UDP publisher already running")
            return true
        }

        publisher = MpegTsUdpPublisher(config).also {
            it.setCodecConfig(codec, sps, pps, vps)
        }

        val success = publisher?.start() ?: false
        if (success) {
            _running.value = true
            Timber.i("MPEG-TS/UDP publisher started on port ${config.port}")
        } else {
            _running.value = false
            Timber.e("Failed to start MPEG-TS/UDP publisher")
        }
        return success
    }

    fun stop() {
        publisher?.stop()
        publisher = null
        _running.value = false
        Timber.i("MPEG-TS/UDP publisher stopped")
    }

    fun isRunning(): Boolean = publisher?.isRunning() ?: false

    fun getStats(): MpegTsUdpStats? = publisher?.getStats()
}
