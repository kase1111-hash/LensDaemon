package com.lensdaemon.camera

import com.lensdaemon.encoder.AudioConfig
import com.lensdaemon.encoder.EncodedAudioFrame
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

    /** Forwards encoded AAC frames to the publisher */
    val audioListener: (EncodedAudioFrame) -> Unit = { frame ->
        publisher?.sendAudio(frame)
    }

    /** The audio carried with new and running publishers, or null for video only. */
    private var audioConfig: AudioConfig? = null

    /** Asks the encoder for a keyframe when a receiver starts. */
    var onKeyframeRequest: (() -> Unit)? = null

    /**
     * Start publishing with [config]. A publisher already running with the
     * same config is kept; one running with a different config (another
     * target, port or mode) is replaced.
     */
    fun start(
        config: MpegTsUdpConfig = MpegTsUdpConfig(),
        codec: VideoCodec = VideoCodec.H264,
        sps: ByteArray? = null,
        pps: ByteArray? = null,
        vps: ByteArray? = null
    ): Boolean {
        val running = publisher
        if (running?.isRunning() == true) {
            if (running.config == config) {
                Timber.w("MPEG-TS/UDP publisher already running")
                return true
            }
            Timber.i("MPEG-TS/UDP settings changed; restarting the publisher")
            stop()
        }

        publisher = MpegTsUdpPublisher(config).also {
            it.setCodecConfig(codec, sps, pps, vps)
            it.setAudioConfig(audioConfig)
            it.onKeyframeRequest = { onKeyframeRequest?.invoke() }
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

    /** Carry [config]'s AAC stream (none when null), now and in publishers started later. */
    fun setAudioConfig(config: AudioConfig?) {
        audioConfig = config
        publisher?.setAudioConfig(config)
    }

    /** Switch the running publisher to [codec]; its parameter sets follow from the stream. */
    fun updateCodec(codec: VideoCodec) {
        publisher?.setCodecConfig(codec, null, null, null)
    }

    fun isRunning(): Boolean = publisher?.isRunning() ?: false

    fun getStats(): MpegTsUdpStats? = publisher?.getStats()
}
