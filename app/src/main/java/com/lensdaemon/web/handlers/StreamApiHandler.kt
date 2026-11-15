package com.lensdaemon.web.handlers

import com.lensdaemon.camera.CameraService
import com.lensdaemon.camera.StreamDefaults
import com.lensdaemon.encoder.EncoderConfig
import com.lensdaemon.encoder.VideoCodec
import com.lensdaemon.output.SegmentDuration
import com.lensdaemon.output.MpegTsUdpConfig
import com.lensdaemon.output.MpegTsMode
import com.lensdaemon.storage.RecordingFile
import com.lensdaemon.web.WebServer
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * API handler for stream, RTSP, recording, and storage endpoints.
 *
 * Handles:
 * - /api/stream     - Encoding start/stop/status
 * - /api/rtsp       - RTSP server control
 * - /api/mpegts     - MPEG-TS/UDP publisher control
 * - /api/audio      - Phone microphone on/off and status
 * - /api/recording  - Local recording control
 * - /api/recordings - Recording file management
 * - /api/storage    - Storage status and cleanup
 */
class StreamApiHandler {

    private companion object {
        const val MIME_MP4 = "video/mp4"
    }

    var cameraService: CameraService? = null

    /** Held while POST /api/recording/start checks for an open recording and starts one. */
    private val recordingStartLock = Any()

    /**
     * Handle request if URI matches. Returns null for unhandled URIs.
     */
    fun handleRequest(uri: String, method: NanoHTTPD.Method, body: JSONObject?): NanoHTTPD.Response? {
        return when {
            // Stream control
            uri == "/api/stream/start" && method == NanoHTTPD.Method.POST -> startStream(body)
            uri == "/api/stream/stop" && method == NanoHTTPD.Method.POST -> stopStream()
            uri == "/api/stream/status" && method == NanoHTTPD.Method.GET -> getStreamStatus()

            // RTSP control
            uri == "/api/rtsp/start" && method == NanoHTTPD.Method.POST -> startRtsp(body)
            uri == "/api/rtsp/stop" && method == NanoHTTPD.Method.POST -> stopRtsp()
            uri == "/api/rtsp/status" && method == NanoHTTPD.Method.GET -> getRtspStatus()

            // MPEG-TS/UDP control
            uri == "/api/mpegts/start" && method == NanoHTTPD.Method.POST -> startMpegTs(body)
            uri == "/api/mpegts/stop" && method == NanoHTTPD.Method.POST -> stopMpegTs()
            uri == "/api/mpegts/status" && method == NanoHTTPD.Method.GET -> getMpegTsStatus()

            // Audio
            uri == "/api/audio" && method == NanoHTTPD.Method.GET -> getAudio()
            uri == "/api/audio" && method == NanoHTTPD.Method.POST -> setAudio(body)

            // Recording control
            uri == "/api/recording/start" && method == NanoHTTPD.Method.POST -> startRecording(body)
            uri == "/api/recording/stop" && method == NanoHTTPD.Method.POST -> stopRecording()
            uri == "/api/recording/pause" && method == NanoHTTPD.Method.POST -> pauseRecording()
            uri == "/api/recording/resume" && method == NanoHTTPD.Method.POST -> resumeRecording()
            uri == "/api/recording/status" && method == NanoHTTPD.Method.GET -> getRecordingStatus()

            // Recordings management
            uri == "/api/recordings" && method == NanoHTTPD.Method.GET -> listRecordings()
            uri.startsWith("/api/recordings/") && method == NanoHTTPD.Method.DELETE -> deleteRecording(uri)
            uri.startsWith("/api/recordings/") && method == NanoHTTPD.Method.GET -> downloadRecording(uri)

            // Storage
            uri == "/api/storage/status" && method == NanoHTTPD.Method.GET -> getStorageStatus()
            uri == "/api/storage/cleanup" && method == NanoHTTPD.Method.POST -> enforceRetention()

            else -> null
        }
    }

    // ==================== Stream Control ====================

    /**
     * POST /api/stream/start: start the encoder (no output). RTSP, MPEG-TS and
     * recording are started by their own endpoints, and share this encoder.
     */
    private fun startStream(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val success = camera.startStreaming(encoderConfigFrom(body, camera.streamDefaults()))

        val json = JSONObject().apply {
            put("success", success)
            if (success) {
                put("message", "Encoder started")
            } else {
                put("message", "Failed to start the encoder")
                put("error", "Failed to start the encoder")
            }
            camera.getEncoderConfig()?.let { put("config", encoderConfigJson(it)) }
        }

        return NanoHTTPD.newFixedLengthResponse(
            if (success) Status.OK else Status.INTERNAL_ERROR,
            WebServer.MIME_JSON, json.toString()
        )
    }

    private fun stopStream(): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()
        camera.stopStreaming()

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "Streaming stopped"}"""
        )
    }

    private fun getStreamStatus(): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val stats = camera.getEncoderStats()
        val config = camera.getEncoderConfig()

        val json = JSONObject().apply {
            put("active", camera.isStreaming())
            put("encoderState", camera.encoderState.value.name)
            if (stats != null) {
                put("framesEncoded", stats.framesEncoded)
                put("framesDropped", stats.framesDropped)
                put("currentBitrate", stats.currentBitrateBps)
                put("currentFps", stats.currentFps)
                put("totalBytes", stats.totalBytesEncoded)
                put("duration", stats.durationSec)
            }
            if (config != null) {
                put("config", encoderConfigJson(config))
            }
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    // ==================== RTSP Control ====================

    private fun startRtsp(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val defaults = camera.streamDefaults()
        val port = body?.optInt("port", defaults.rtspPort) ?: defaults.rtspPort

        val success = camera.startRtspStreaming(encoderConfigFrom(body, defaults), port)

        val json = JSONObject().apply {
            put("success", success)
            if (success) {
                put("url", camera.getRtspUrl())
                put("message", "RTSP streaming started")
                // A running encoder is shared, so report what is actually being sent
                camera.getEncoderConfig()?.let { put("config", encoderConfigJson(it)) }
            } else {
                put("message", "Failed to start RTSP streaming")
                put("error", "Failed to start RTSP streaming")
            }
        }

        return NanoHTTPD.newFixedLengthResponse(
            if (success) Status.OK else Status.INTERNAL_ERROR,
            WebServer.MIME_JSON, json.toString()
        )
    }

    private fun stopRtsp(): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()
        camera.stopRtspStreaming()

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "RTSP streaming stopped"}"""
        )
    }

    private fun getRtspStatus(): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val stats = camera.getRtspServerStats()

        val json = JSONObject().apply {
            put("running", camera.isRtspServerRunning())
            put("url", camera.getRtspUrl() ?: "")
            put("clients", camera.getRtspClientCount())
            put("playing", camera.getRtspPlayingCount())
            if (stats != null) {
                put("totalConnections", stats.totalConnections)
                put("totalPackets", stats.totalPacketsSent)
                put("totalBytes", stats.totalBytesSent)
                put("uptime", stats.uptimeMs)
            }
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    // ==================== MPEG-TS/UDP Control ====================

    private fun startMpegTs(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val port = body?.optInt("port", 9000) ?: 9000
        val modeStr = body?.optString("mode", "listener") ?: "listener"
        val targetHost = body?.optString("targetHost", "") ?: ""
        val targetPort = body?.optInt("targetPort", 9000) ?: 9000
        val latencyMs = body?.optInt("latencyMs", 120) ?: 120

        val mode = if (modeStr.equals("caller", ignoreCase = true)) MpegTsMode.CALLER else MpegTsMode.LISTENER
        if (mode == MpegTsMode.CALLER && targetHost.isBlank()) {
            return ApiHandlerUtils.errorJson(
                Status.BAD_REQUEST,
                "Caller mode needs targetHost: the address of the machine receiving the stream (e.g. the PC running OBS)"
            )
        }
        // Resolve here, on the request thread, rather than inside the camera
        // service's start (which holds a lock other threads wait on)
        val resolvedHost = if (mode == MpegTsMode.CALLER) {
            resolveHost(targetHost) ?: return ApiHandlerUtils.errorJson(
                Status.BAD_REQUEST,
                "Cannot resolve targetHost"
            )
        } else {
            targetHost
        }

        val mpegtsConfig = MpegTsUdpConfig(
            port = port,
            mode = mode,
            targetHost = resolvedHost,
            targetPort = targetPort,
            latencyMs = latencyMs
        )

        val success = camera.startMpegTsStreaming(encoderConfigFrom(body, camera.streamDefaults()), mpegtsConfig)

        val json = JSONObject().apply {
            put("success", success)
            if (success) {
                put("message", "MPEG-TS/UDP streaming started")
                put("port", port)
                put("mode", mode.name)
                if (mode == MpegTsMode.CALLER) put("target", "$targetHost:$targetPort")
                camera.getEncoderConfig()?.let { put("config", encoderConfigJson(it)) }
            } else {
                put("message", "Failed to start MPEG-TS/UDP streaming")
                put("error", "Failed to start MPEG-TS/UDP streaming")
            }
        }

        return NanoHTTPD.newFixedLengthResponse(
            if (success) Status.OK else Status.INTERNAL_ERROR,
            WebServer.MIME_JSON, json.toString()
        )
    }

    private fun stopMpegTs(): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()
        camera.stopMpegTsStreaming()

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "MPEG-TS/UDP streaming stopped"}"""
        )
    }

    private fun getMpegTsStatus(): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val stats = camera.getMpegTsStats()

        val json = JSONObject().apply {
            put("running", camera.isMpegTsRunning())
            if (stats != null) {
                put("connected", stats.isConnected)
                put("mode", stats.mode.name)
                put("port", stats.port)
                put("remoteAddress", stats.remoteAddress)
                put("bytesSent", stats.bytesSent)
                put("packetsSent", stats.packetsSent)
                put("framesSent", stats.framesSent)
                put("uptime", stats.uptimeMs)
            }
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    // ==================== Audio ====================

    private fun getAudio(): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, audioStatusJson(camera).toString())
    }

    /** Body: {"enabled": true|false}. Applies to running streams at once. */
    private fun setAudio(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()
        if (body == null || !body.has("enabled")) return ApiHandlerUtils.bodyRequired()
        camera.setAudioEnabled(body.optBoolean("enabled", true))
        val json = audioStatusJson(camera).put("success", true)
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    /** Microphone state: switched on, permitted, and actually being captured (with its settings). */
    fun audioStatusJson(camera: CameraService): JSONObject = JSONObject().apply {
        put("enabled", camera.audioEnabled)
        put("permission", camera.hasMicrophonePermission())
        put("active", camera.isAudioActive())
        camera.getAudioConfig()?.let { audio ->
            put("sampleRate", audio.sampleRate)
            put("channels", audio.channelCount)
            put("bitrate", audio.bitrateBps)
            put("framesEncoded", camera.getAudioFramesEncoded())
        }
    }

    // ==================== Recording Control ====================

    /**
     * POST /api/recording/start. While a recording is open (running or
     * paused) it is left as it is and the reply says so, so a double click or
     * a retry does no harm. Starts run one at a time: a second one waits for
     * the first and then finds its recording open, instead of reaching the
     * camera service while the first is still starting the encoder.
     */
    private fun startRecording(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val defaults = camera.streamDefaults()
        val segmentMinutes = body?.optInt("segmentMinutes", defaults.segmentMinutes) ?: defaults.segmentMinutes

        val segmentDuration = when (segmentMinutes) {
            0 -> SegmentDuration.CONTINUOUS
            1 -> SegmentDuration.ONE_MINUTE
            5 -> SegmentDuration.FIVE_MINUTES
            15 -> SegmentDuration.FIFTEEN_MINUTES
            30 -> SegmentDuration.THIRTY_MINUTES
            60 -> SegmentDuration.SIXTY_MINUTES
            else -> SegmentDuration.FIVE_MINUTES
        }

        val success = synchronized(recordingStartLock) {
            if (camera.isRecording() || camera.isRecordingPaused()) return alreadyRecording(camera)
            camera.startRecording(encoderConfigFrom(body, defaults), segmentDuration)
        }
        // A start that lost a race with one from elsewhere (the phone, kiosk
        // auto-start) finds that recording open
        if (!success && (camera.isRecording() || camera.isRecordingPaused())) return alreadyRecording(camera)

        val json = JSONObject().apply {
            put("success", success)
            if (success) {
                put("message", "Recording started")
                put("segmentDuration", segmentDuration.displayName)
                put("outputPath", camera.getRecordingsPath())
            } else {
                put("message", "Failed to start recording")
                put("error", "Failed to start recording")
            }
        }

        return NanoHTTPD.newFixedLengthResponse(
            if (success) Status.OK else Status.INTERNAL_ERROR,
            WebServer.MIME_JSON, json.toString()
        )
    }

    /** The reply to a start while a recording is open: success, with what to do instead when paused. */
    private fun alreadyRecording(camera: CameraService): NanoHTTPD.Response {
        val paused = camera.isRecordingPaused()
        val message = if (paused) "Recording is paused: resume it with POST /api/recording/resume" else "Already recording"
        val json = JSONObject()
            .put("success", true)
            .put("alreadyRecording", true)
            .put("paused", paused)
            .put("message", message)
            .put("outputPath", camera.getRecordingsPath())
        return ApiHandlerUtils.jsonResponse(Status.OK, json.toString())
    }

    private fun stopRecording(): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val segments = camera.stopRecording()

        val json = JSONObject().apply {
            put("success", true)
            put("message", "Recording stopped")
            put("segmentCount", segments.size)
            put("segments", JSONArray().apply {
                segments.forEach { put(it) }
            })
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    private fun pauseRecording(): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val success = camera.pauseRecording()

        val message = if (success) "Recording paused" else "Not recording"
        val json = JSONObject().put("success", success).put("message", message)
        if (!success) json.put("error", message)
        return ApiHandlerUtils.jsonResponse(if (success) Status.OK else Status.BAD_REQUEST, json.toString())
    }

    private fun resumeRecording(): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val success = camera.resumeRecording()

        val message = if (success) "Recording resumed" else "Not paused"
        val json = JSONObject().put("success", success).put("message", message)
        if (!success) json.put("error", message)
        return ApiHandlerUtils.jsonResponse(if (success) Status.OK else Status.BAD_REQUEST, json.toString())
    }

    /** GET /api/recording/status: "recording" is true while a recording is open, running or paused. */
    private fun getRecordingStatus(): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val stats = camera.getRecordingStats()

        val json = JSONObject().apply {
            put("recording", camera.isRecording() || camera.isRecordingPaused())
            put("paused", camera.isRecordingPaused())
            put("state", stats.state.name)
            put("currentFile", stats.currentFilePath ?: "")
            put("framesInSegment", stats.framesInSegment)
            put("totalFrames", stats.totalFrames)
            put("bytesInSegment", stats.bytesInSegment)
            put("totalBytes", stats.totalBytes)
            put("segmentIndex", stats.segmentIndex)
            put("durationSec", stats.durationSec)
            put("avgBitrateKbps", stats.avgBitrateKbps)
            put("completedSegments", JSONArray().apply {
                stats.completedSegments.forEach { put(it) }
            })
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    // ==================== Recordings Management ====================

    private fun listRecordings(): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val recordings = camera.listRecordings()

        val json = JSONObject().apply {
            put("count", recordings.size)
            put("totalSizeBytes", recordings.sumOf { it.sizeBytes })
            put("recordings", JSONArray().apply {
                recordings.forEach { rec ->
                    put(JSONObject().apply {
                        put("name", rec.name)
                        put("path", rec.path)
                        put("sizeBytes", rec.sizeBytes)
                        put("sizeMB", rec.sizeMB)
                        put("lastModified", rec.lastModifiedMs)
                        put("lastModifiedFormatted", rec.lastModifiedFormatted)
                        put("ageHours", rec.ageHours)
                    })
                }
            })
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    /**
     * DELETE /api/recordings/{name}: any plain name the recordings list holds
     * (so recordings named with characters such as '(' stay reachable), but
     * not the file being recorded.
     */
    private fun deleteRecording(uri: String): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val filename = uri.substringAfterLast("/")
        if (!RecordingRequests.isPlainFileName(filename)) {
            return ApiHandlerUtils.errorJson(Status.BAD_REQUEST, "Invalid filename")
        }

        // Only files the recordings list knows, so nothing outside the recordings folder
        val recording = RecordingRequests.find(camera.listRecordings(), null, filename)
            ?: return ApiHandlerUtils.errorJson(
                Status.NOT_FOUND,
                "Recording not found"
            )
        liveFileConflict(camera, recording)?.let { return it }

        val success = camera.deleteRecording(recording)

        val json = JSONObject().apply {
            put("success", success)
            put("filename", filename)
            if (!success) put("error", "Could not delete the recording")
        }

        return NanoHTTPD.newFixedLengthResponse(
            if (success) Status.OK else Status.INTERNAL_ERROR,
            WebServer.MIME_JSON,
            json.toString()
        )
    }

    /**
     * Download a recording as an MP4 file: GET /api/recordings/{name}, for the
     * same names as delete. The file being recorded is refused: it can't be
     * played until its segment closes.
     */
    private fun downloadRecording(uri: String): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val filename = uri.substringAfterLast("/")
        if (!RecordingRequests.isPlainFileName(filename)) {
            return ApiHandlerUtils.errorJson(Status.BAD_REQUEST, "Invalid filename")
        }
        // Only files the recordings list knows, so nothing outside the recordings folder
        val recording = RecordingRequests.find(camera.listRecordings(), null, filename)
            ?: return ApiHandlerUtils.errorJson(Status.NOT_FOUND, "Recording not found")
        liveFileConflict(camera, recording)?.let { return it }
        val file = File(recording.path)

        return try {
            NanoHTTPD.newFixedLengthResponse(Status.OK, MIME_MP4, FileInputStream(file), file.length()).apply {
                addHeader("Content-Disposition", RecordingRequests.contentDisposition(recording.name))
            }
        } catch (e: FileNotFoundException) {
            Timber.w(e, "Recording $filename is gone")
            ApiHandlerUtils.errorJson(Status.NOT_FOUND, "Recording not found")
        }
    }

    /**
     * 409 when [recording] is the file the recorder holds open, else null. The
     * open file decides, not the recording state: a recorder that reported an
     * error (a segment whose muxer would not start, say) keeps its file open,
     * and a later keyframe can still start writing to it.
     */
    private fun liveFileConflict(camera: CameraService, recording: RecordingFile): NanoHTTPD.Response? {
        if (!RecordingRequests.isSameFile(recording.path, camera.getRecordingStats().currentFilePath)) return null
        val json = JSONObject()
            .put("success", false)
            .put("error", "This file is being recorded: stop the recording or wait for the next file")
        return ApiHandlerUtils.jsonResponse(Status.CONFLICT, json.toString())
    }

    // ==================== Storage ====================

    private fun getStorageStatus(): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val status = camera.getStorageStatus()

        val json = JSONObject().apply {
            put("state", status.state.name)
            put("warningLevel", status.warningLevel.name)
            put("totalRecordings", status.totalRecordings)
            put("totalRecordingsSizeBytes", status.totalRecordingsSizeBytes)
            put("storage", JSONObject().apply {
                put("totalBytes", status.storageInfo.totalBytes)
                put("availableBytes", status.storageInfo.availableBytes)
                put("usedBytes", status.storageInfo.usedBytes)
                put("path", status.storageInfo.path)
                put("totalGB", status.storageInfo.totalGB)
                put("availableGB", status.storageInfo.availableGB)
                put("usagePercent", status.storageInfo.usagePercent)
                put("isLowSpace", status.storageInfo.isLowSpace)
                put("isCriticallyLowSpace", status.storageInfo.isCriticallyLowSpace)
            })
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    /**
     * POST /api/storage/cleanup: apply the retention policy (7 days, 10 GB,
     * keep 1 GB free; never the file being recorded) and answer once it has
     * finished, with what this clean-up removed.
     */
    private fun enforceRetention(): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        // Runs the clean-up here, on the request thread, and answers with this
        // run's own result. Clean-ups take turns in RetentionPolicy, so one that
        // runs at the same time (another request, the recorder's own) cannot
        // delete the same files under it.
        val result = camera.enforceRetention()

        val json = JSONObject().apply {
            put("success", true)
            put("filesDeleted", result.filesDeleted)
            put("bytesFreed", result.bytesFreed)
            put("failed", JSONArray().apply {
                result.failedFiles.forEach { put(it) }
            })
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    // ==================== Helpers ====================

    /**
     * Encoder settings from a start request: width, height, bitrate (bps),
     * frameRate and codec (H264 or H265). Each one the request leaves out comes
     * from the phone's saved stream defaults, so a start without settings
     * streams with them, and the start (which saves its settings as the new
     * defaults) leaves them as they were.
     */
    private fun encoderConfigFrom(body: JSONObject?, defaults: StreamDefaults): EncoderConfig {
        val codecStr = body?.optString("codec", defaults.codec.name) ?: defaults.codec.name
        val codec = VideoCodec.entries.find { it.name.equals(codecStr, ignoreCase = true) } ?: defaults.codec
        return EncoderConfig(
            codec = codec,
            resolution = android.util.Size(
                body?.optInt("width", defaults.width) ?: defaults.width,
                body?.optInt("height", defaults.height) ?: defaults.height
            ),
            bitrateBps = body?.optInt("bitrate", defaults.bitrateBps) ?: defaults.bitrateBps,
            frameRate = body?.optInt("frameRate", defaults.frameRate) ?: defaults.frameRate
        )
    }

    private fun resolveHost(host: String): String? = try {
        InetAddress.getByName(host).hostAddress
    } catch (e: UnknownHostException) {
        Timber.w(e, "Cannot resolve MPEG-TS target $host")
        null
    }

    private fun encoderConfigJson(config: EncoderConfig): JSONObject = JSONObject().apply {
        put("width", config.width)
        put("height", config.height)
        put("bitrate", config.bitrateBps)
        put("frameRate", config.frameRate)
        put("codec", config.codec.name)
    }

    private fun cameraUnavailable(): NanoHTTPD.Response {
        return ApiHandlerUtils.serviceUnavailable("Camera service")
    }
}
