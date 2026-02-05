package com.lensdaemon.web.handlers

import com.lensdaemon.camera.CameraService
import com.lensdaemon.encoder.EncoderConfig
import com.lensdaemon.encoder.VideoCodec
import com.lensdaemon.output.SegmentDuration
import com.lensdaemon.output.SrtConfig
import com.lensdaemon.output.SrtMode
import com.lensdaemon.web.WebServer
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject

/**
 * API handler for stream, RTSP, recording, and storage endpoints.
 *
 * Handles:
 * - /api/stream/*     - Encoding start/stop/status
 * - /api/rtsp/*       - RTSP server control
 * - /api/srt/*        - SRT publisher control
 * - /api/recording/*  - Local recording control
 * - /api/recordings   - Recording file management
 * - /api/storage/*    - Storage status and cleanup
 */
class StreamApiHandler {

    var cameraService: CameraService? = null

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

            // SRT control
            uri == "/api/srt/start" && method == NanoHTTPD.Method.POST -> startSrt(body)
            uri == "/api/srt/stop" && method == NanoHTTPD.Method.POST -> stopSrt()
            uri == "/api/srt/status" && method == NanoHTTPD.Method.GET -> getSrtStatus()

            // Recording control
            uri == "/api/recording/start" && method == NanoHTTPD.Method.POST -> startRecording(body)
            uri == "/api/recording/stop" && method == NanoHTTPD.Method.POST -> stopRecording()
            uri == "/api/recording/pause" && method == NanoHTTPD.Method.POST -> pauseRecording()
            uri == "/api/recording/resume" && method == NanoHTTPD.Method.POST -> resumeRecording()
            uri == "/api/recording/status" && method == NanoHTTPD.Method.GET -> getRecordingStatus()

            // Recordings management
            uri == "/api/recordings" && method == NanoHTTPD.Method.GET -> listRecordings()
            uri.startsWith("/api/recordings/") && method == NanoHTTPD.Method.DELETE -> deleteRecording(uri)

            // Storage
            uri == "/api/storage/status" && method == NanoHTTPD.Method.GET -> getStorageStatus()
            uri == "/api/storage/cleanup" && method == NanoHTTPD.Method.POST -> enforceRetention()

            else -> null
        }
    }

    // ==================== Stream Control ====================

    private fun startStream(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val width = body?.optInt("width", 1920) ?: 1920
        val height = body?.optInt("height", 1080) ?: 1080
        val bitrate = body?.optInt("bitrate", 4_000_000) ?: 4_000_000
        val frameRate = body?.optInt("frameRate", 30) ?: 30
        val codecStr = body?.optString("codec", "H264") ?: "H264"
        val codec = try { VideoCodec.valueOf(codecStr) } catch (e: Exception) { VideoCodec.H264 }

        val config = EncoderConfig(
            codec = codec,
            resolution = android.util.Size(width, height),
            bitrateBps = bitrate,
            frameRate = frameRate
        )

        camera.startStreaming(config)

        val json = JSONObject().apply {
            put("success", true)
            put("message", "Streaming started")
            put("config", JSONObject().apply {
                put("width", width)
                put("height", height)
                put("bitrate", bitrate)
                put("frameRate", frameRate)
                put("codec", codec.name)
            })
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
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
                put("config", JSONObject().apply {
                    put("width", config.width)
                    put("height", config.height)
                    put("bitrate", config.bitrateBps)
                    put("frameRate", config.frameRate)
                    put("codec", config.codec.name)
                })
            }
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    // ==================== RTSP Control ====================

    private fun startRtsp(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val port = body?.optInt("port", 8554) ?: 8554
        val width = body?.optInt("width", 1920) ?: 1920
        val height = body?.optInt("height", 1080) ?: 1080
        val bitrate = body?.optInt("bitrate", 4_000_000) ?: 4_000_000
        val frameRate = body?.optInt("frameRate", 30) ?: 30

        val config = EncoderConfig(
            resolution = android.util.Size(width, height),
            bitrateBps = bitrate,
            frameRate = frameRate
        )

        val success = camera.startRtspStreaming(config, port)

        val json = JSONObject().apply {
            put("success", success)
            if (success) {
                put("url", camera.getRtspUrl())
                put("message", "RTSP streaming started")
            } else {
                put("message", "Failed to start RTSP streaming")
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

    // ==================== SRT Control ====================

    private fun startSrt(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val port = body?.optInt("port", 9000) ?: 9000
        val modeStr = body?.optString("mode", "listener") ?: "listener"
        val targetHost = body?.optString("targetHost", "") ?: ""
        val targetPort = body?.optInt("targetPort", 9000) ?: 9000
        val latencyMs = body?.optInt("latencyMs", 120) ?: 120
        val width = body?.optInt("width", 1920) ?: 1920
        val height = body?.optInt("height", 1080) ?: 1080
        val bitrate = body?.optInt("bitrate", 4_000_000) ?: 4_000_000
        val frameRate = body?.optInt("frameRate", 30) ?: 30

        val mode = if (modeStr.equals("caller", ignoreCase = true)) SrtMode.CALLER else SrtMode.LISTENER

        val srtConfig = SrtConfig(
            port = port,
            mode = mode,
            targetHost = targetHost,
            targetPort = targetPort,
            latencyMs = latencyMs
        )

        val encoderConfig = EncoderConfig(
            resolution = android.util.Size(width, height),
            bitrateBps = bitrate,
            frameRate = frameRate
        )

        val success = camera.startSrtStreaming(encoderConfig, srtConfig)

        val json = JSONObject().apply {
            put("success", success)
            if (success) {
                put("message", "SRT streaming started")
                put("port", port)
                put("mode", mode.name)
            } else {
                put("message", "Failed to start SRT streaming")
            }
        }

        return NanoHTTPD.newFixedLengthResponse(
            if (success) Status.OK else Status.INTERNAL_ERROR,
            WebServer.MIME_JSON, json.toString()
        )
    }

    private fun stopSrt(): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()
        camera.stopSrtStreaming()

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "SRT streaming stopped"}"""
        )
    }

    private fun getSrtStatus(): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val stats = camera.getSrtStats()

        val json = JSONObject().apply {
            put("running", camera.isSrtRunning())
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

    // ==================== Recording Control ====================

    private fun startRecording(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val width = body?.optInt("width", 1920) ?: 1920
        val height = body?.optInt("height", 1080) ?: 1080
        val bitrate = body?.optInt("bitrate", 4_000_000) ?: 4_000_000
        val frameRate = body?.optInt("frameRate", 30) ?: 30
        val segmentMinutes = body?.optInt("segmentMinutes", 5) ?: 5

        val segmentDuration = when (segmentMinutes) {
            0 -> SegmentDuration.CONTINUOUS
            1 -> SegmentDuration.ONE_MINUTE
            5 -> SegmentDuration.FIVE_MINUTES
            15 -> SegmentDuration.FIFTEEN_MINUTES
            30 -> SegmentDuration.THIRTY_MINUTES
            60 -> SegmentDuration.SIXTY_MINUTES
            else -> SegmentDuration.FIVE_MINUTES
        }

        val config = EncoderConfig(
            resolution = android.util.Size(width, height),
            bitrateBps = bitrate,
            frameRate = frameRate
        )

        val success = camera.startRecording(config, segmentDuration)

        val json = JSONObject().apply {
            put("success", success)
            if (success) {
                put("message", "Recording started")
                put("segmentDuration", segmentDuration.displayName)
                put("outputPath", camera.getRecordingsPath())
            } else {
                put("message", "Failed to start recording")
            }
        }

        return NanoHTTPD.newFixedLengthResponse(
            if (success) Status.OK else Status.INTERNAL_ERROR,
            WebServer.MIME_JSON, json.toString()
        )
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

        return NanoHTTPD.newFixedLengthResponse(
            if (success) Status.OK else Status.BAD_REQUEST,
            WebServer.MIME_JSON,
            """{"success": $success, "message": "${if (success) "Recording paused" else "Not recording"}"}"""
        )
    }

    private fun resumeRecording(): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val success = camera.resumeRecording()

        return NanoHTTPD.newFixedLengthResponse(
            if (success) Status.OK else Status.BAD_REQUEST,
            WebServer.MIME_JSON,
            """{"success": $success, "message": "${if (success) "Recording resumed" else "Not paused"}"}"""
        )
    }

    private fun getRecordingStatus(): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val stats = camera.getRecordingStats()

        val json = JSONObject().apply {
            put("recording", camera.isRecording())
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

    private fun deleteRecording(uri: String): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        val filename = uri.substringAfterLast("/")
        if (filename.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(
                Status.BAD_REQUEST, WebServer.MIME_JSON,
                """{"error": "Filename required"}"""
            )
        }

        val recordings = camera.listRecordings()
        val recording = recordings.find { it.name == filename }
            ?: return NanoHTTPD.newFixedLengthResponse(
                Status.NOT_FOUND, WebServer.MIME_JSON,
                """{"error": "Recording not found: $filename"}"""
            )

        val success = camera.deleteRecording(recording)

        return NanoHTTPD.newFixedLengthResponse(
            if (success) Status.OK else Status.INTERNAL_ERROR,
            WebServer.MIME_JSON,
            """{"success": $success, "filename": "$filename"}"""
        )
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

    private fun enforceRetention(): NanoHTTPD.Response {
        val camera = cameraService ?: return cameraUnavailable()

        camera.enforceRetention()

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "Retention enforcement started"}"""
        )
    }

    // ==================== Helpers ====================

    private fun cameraUnavailable(): NanoHTTPD.Response {
        return ApiHandlerUtils.serviceUnavailable("Camera service")
    }
}
