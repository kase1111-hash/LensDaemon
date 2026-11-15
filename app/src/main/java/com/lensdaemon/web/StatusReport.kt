package com.lensdaemon.web

import com.lensdaemon.camera.CameraService
import com.lensdaemon.director.DirectorManager
import com.lensdaemon.director.TakeManager
import com.lensdaemon.encoder.EncoderState
import com.lensdaemon.thermal.ThermalGovernor
import org.json.JSONArray
import org.json.JSONObject

/**
 * The sections of GET /api/status, which the dashboard polls every two seconds.
 */
internal object StatusReport {

    /** A take scoring at least this counts as good in the dashboard's stats: the director's own rule. */
    private const val GOOD_TAKE_SCORE = TakeManager.GOOD_SCORE

    fun camera(camera: CameraService?): JSONObject = JSONObject().apply {
        put("previewActive", camera?.isPreviewActive() ?: false)
        put("streamingActive", camera?.isStreaming() ?: false)
        put("currentLens", camera?.getCurrentLens()?.value?.lensType?.name ?: "none")
        // The phone got too hot: the outputs it stopped come back once it has cooled down
        put("thermalPaused", camera?.isThermalPaused() ?: false)
        put("zoom", camera?.currentZoom?.value ?: 1.0f)
        if (camera != null) {
            put("maxZoom", camera.getMaxZoom())
            // Exposure compensation is set in steps of the camera's own size (often 1/6 or 1/3 EV)
            val range = camera.getExposureCompensationRange()
            put("exposure", JSONObject().apply {
                put("value", camera.getConfig().exposureCompensation)
                put("min", range.lower)
                put("max", range.upper)
                put("step", camera.getExposureCompensationStep())
            })
        }
    }

    fun encoder(camera: CameraService?): JSONObject = JSONObject().apply {
        put("state", (camera?.encoderState?.value ?: EncoderState.IDLE).name)
        camera?.getEncoderStats()?.let { stats ->
            put("framesEncoded", stats.framesEncoded)
            put("currentBitrate", stats.currentBitrateBps)
            put("currentFps", stats.currentFps)
        }
        camera?.getEncoderConfig()?.let { config ->
            put("width", config.width)
            put("height", config.height)
            put("frameRate", config.frameRate)
            put("bitrate", config.bitrateBps)
            put("codec", config.codec.name)
        }
    }

    fun rtsp(camera: CameraService?): JSONObject = JSONObject().apply {
        put("running", camera?.isRtspServerRunning() ?: false)
        put("url", camera?.getRtspUrl() ?: "")
        put("clients", camera?.getRtspClientCount() ?: 0)
        put("playing", camera?.getRtspPlayingCount() ?: 0)
    }

    fun mpegTs(camera: CameraService?): JSONObject = JSONObject().apply {
        put("running", camera?.isMpegTsRunning() ?: false)
        camera?.getMpegTsStats()?.let { stats ->
            put("mode", stats.mode.name)
            put("connected", stats.isConnected)
            put("remoteAddress", stats.remoteAddress)
            put("port", stats.port)
        }
    }

    /** "active" while a recording is open, running or paused; "paused" while it is paused. */
    fun recording(camera: CameraService?): JSONObject {
        val paused = camera?.isRecordingPaused() ?: false
        return JSONObject().apply {
            put("active", (camera?.isRecording() ?: false) || paused)
            put("paused", paused)
        }
    }

    /** Temperatures and throttling at a glance; GET /api/thermal/status has the detail. */
    fun thermal(governor: ThermalGovernor): JSONObject {
        val status = governor.status.value
        return JSONObject().apply {
            put("cpuTemperatureC", status.cpuTemperatureC)
            put("batteryTemperatureC", status.batteryTemperatureC)
            put("overallLevel", status.overallLevel.name)
            put("isThrottling", status.isThrottling)
        }
    }

    /**
     * The director's state, where it is in the script (scene and cue, counted
     * from 0, with the scene names for jumping between them) and its session stats.
     */
    fun director(director: DirectorManager): JSONObject {
        val status = director.getStatus()
        val session = director.currentSession.value
        val takeManager = director.getTakeManager()
        val takes = takeManager.recordedTakes.value
        return JSONObject().apply {
            put("enabled", status.enabled)
            put("state", status.state.name)
            put("currentScene", status.currentScene ?: "")
            put("currentCue", status.currentCue ?: "")
            put("currentTake", status.takeNumber)
            put("totalCues", status.totalCues)
            put("executedCues", status.executedCues)
            if (session != null) {
                put("sceneIndex", session.currentSceneIndex)
                put("sceneCount", session.script.scenes.size)
                put("cueIndex", session.currentCueIndex)
                put("cueCount", session.currentScene?.cues?.size ?: 0)
                put("sceneLabels", JSONArray(session.script.scenes.map { it.label }))
            }
            put("stats", JSONObject().apply {
                put("totalTakes", takes.size)
                put("goodTakes", takes.count { it.qualityScore >= GOOD_TAKE_SCORE })
                put("averageScore", if (takes.isNotEmpty()) takes.map { it.qualityScore }.average() else 0.0)
                put("sessionTime", director.getSessionDuration())
                put("cueSuccessRate", takeManager.getSessionStats().cueSuccessRate)
            })
        }
    }
}
