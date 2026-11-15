package com.lensdaemon

/**
 * Which of the phone's stream controls to show, from what is running. Plain
 * values, so the rules are tested on the JVM.
 *
 * During a thermal pause nothing streams, but the outputs come back by
 * themselves when the phone cools. Start is hidden (a start would cancel the
 * pause and bring back only RTSP), and Stop stays as "Stop all", because
 * stopping cancels the automatic restart.
 */
internal data class StreamControls(
    /** The outputs were stopped by a thermal pause and restart when the phone cools. */
    val thermalPaused: Boolean,
    val startVisible: Boolean,
    val stopVisible: Boolean,
    /** Stop ends more than RTSP (MPEG-TS, a recording, a thermal pause): label it "Stop all". */
    val stopEndsAll: Boolean,
    val infoVisible: Boolean
) {
    companion object {
        /**
         * @param streaming the encoder runs
         * @param rtspOn the RTSP server runs
         * @param thermalPaused the camera service holds a thermal pause
         * @param otherOutputs MPEG-TS or a recording runs besides RTSP
         */
        fun of(streaming: Boolean, rtspOn: Boolean, thermalPaused: Boolean, otherOutputs: Boolean): StreamControls {
            val paused = thermalPaused && !streaming
            return StreamControls(
                thermalPaused = paused,
                startVisible = !rtspOn && !paused,
                stopVisible = streaming || paused,
                stopEndsAll = otherOutputs || paused,
                infoVisible = streaming || paused
            )
        }
    }
}
