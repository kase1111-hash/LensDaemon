package com.lensdaemon.camera

import com.lensdaemon.encoder.EncodedFrame
import com.lensdaemon.encoder.EncoderConfig
import com.lensdaemon.encoder.VideoCodec
import com.lensdaemon.output.RtspServer
import com.lensdaemon.output.RtspServerState
import com.lensdaemon.output.RtspServerStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Coordinates RTSP server lifecycle and frame distribution.
 *
 * Extracted from CameraService to keep RTSP concerns isolated.
 */
class RtspCoordinator {

    private var rtspServer: RtspServer? = null

    private val _serverState = MutableStateFlow(RtspServerState.STOPPED)
    val serverState: StateFlow<RtspServerState> = _serverState.asStateFlow()

    /** Frame listener that forwards encoded frames to RTSP clients */
    val frameListener: (EncodedFrame) -> Unit = { frame ->
        rtspServer?.sendFrame(frame)
    }

    /** Callback invoked when an RTSP client requests a keyframe */
    var onKeyframeRequest: (() -> Unit)? = null

    fun start(port: Int = 8554): Boolean {
        if (rtspServer?.isRunning() == true) {
            Timber.w("RTSP server already running")
            return true
        }

        rtspServer = RtspServer(port)

        rtspServer?.onKeyframeRequest = {
            onKeyframeRequest?.invoke()
        }

        val success = rtspServer?.start() ?: false
        if (success) {
            _serverState.value = RtspServerState.RUNNING
            Timber.i("RTSP server started: ${rtspServer?.getRtspUrl()}")
        } else {
            _serverState.value = RtspServerState.ERROR
        }

        return success
    }

    fun stop() {
        rtspServer?.stop()
        rtspServer = null
        _serverState.value = RtspServerState.STOPPED
        Timber.i("RTSP server stopped")
    }

    fun updateCodecConfig(codec: VideoCodec, sps: ByteArray?, pps: ByteArray?, vps: ByteArray?) {
        rtspServer?.setCodecConfig(codec, sps, pps, vps)
    }

    fun getRtspUrl(): String? = rtspServer?.getRtspUrl()

    fun getStats(): RtspServerStats? = rtspServer?.getStats()

    fun getActiveConnections(): Int = rtspServer?.getActiveConnections() ?: 0

    fun getPlayingClients(): Int = rtspServer?.getPlayingClients() ?: 0

    fun isRunning(): Boolean = rtspServer?.isRunning() ?: false
}
