package com.lensdaemon.web

import android.content.Context
import android.os.Build
import com.lensdaemon.camera.CameraService
import com.lensdaemon.camera.LensType
import com.lensdaemon.encoder.EncoderConfig
import com.lensdaemon.encoder.EncoderState
import com.lensdaemon.encoder.VideoCodec
import com.lensdaemon.output.SegmentDuration
import com.lensdaemon.storage.RecordingFile
import com.lensdaemon.storage.S3Credentials
import com.lensdaemon.storage.SmbCredentials
import com.lensdaemon.storage.StorageBackend
import com.lensdaemon.storage.UploadDestination
import com.lensdaemon.storage.UploadService
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayInputStream

/**
 * REST API routes handler for LensDaemon web interface
 */
class ApiRoutes(
    private val context: Context
) {
    companion object {
        private const val TAG = "ApiRoutes"
    }

    // Camera service reference (set by WebServerService)
    var cameraService: CameraService? = null

    // Upload service reference (set by WebServerService)
    var uploadService: UploadService? = null

    // Snapshot callback
    var onSnapshotRequest: (() -> ByteArray?)? = null

    /**
     * Handle API request
     */
    fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri
        val method = session.method

        // Parse request body for POST/PUT
        val body = if (method == NanoHTTPD.Method.POST || method == NanoHTTPD.Method.PUT) {
            parseBody(session)
        } else null

        return when {
            // Status endpoints
            uri == "/api/status" && method == NanoHTTPD.Method.GET -> getStatus()
            uri == "/api/device" && method == NanoHTTPD.Method.GET -> getDeviceInfo()

            // Stream control
            uri == "/api/stream/start" && method == NanoHTTPD.Method.POST -> startStream(body)
            uri == "/api/stream/stop" && method == NanoHTTPD.Method.POST -> stopStream()
            uri == "/api/stream/status" && method == NanoHTTPD.Method.GET -> getStreamStatus()

            // RTSP control
            uri == "/api/rtsp/start" && method == NanoHTTPD.Method.POST -> startRtsp(body)
            uri == "/api/rtsp/stop" && method == NanoHTTPD.Method.POST -> stopRtsp()
            uri == "/api/rtsp/status" && method == NanoHTTPD.Method.GET -> getRtspStatus()

            // Recording control
            uri == "/api/recording/start" && method == NanoHTTPD.Method.POST -> startRecording(body)
            uri == "/api/recording/stop" && method == NanoHTTPD.Method.POST -> stopRecording()
            uri == "/api/recording/pause" && method == NanoHTTPD.Method.POST -> pauseRecording()
            uri == "/api/recording/resume" && method == NanoHTTPD.Method.POST -> resumeRecording()
            uri == "/api/recording/status" && method == NanoHTTPD.Method.GET -> getRecordingStatus()

            // Recordings management
            uri == "/api/recordings" && method == NanoHTTPD.Method.GET -> listRecordings()
            uri.startsWith("/api/recordings/") && method == NanoHTTPD.Method.DELETE -> deleteRecording(uri)

            // Storage status
            uri == "/api/storage/status" && method == NanoHTTPD.Method.GET -> getStorageStatus()
            uri == "/api/storage/cleanup" && method == NanoHTTPD.Method.POST -> enforceRetention()

            // Lens control
            uri.startsWith("/api/lens/") && method == NanoHTTPD.Method.POST -> switchLens(uri)
            uri == "/api/lenses" && method == NanoHTTPD.Method.GET -> getLenses()

            // Camera control
            uri == "/api/zoom" && method == NanoHTTPD.Method.POST -> setZoom(body)
            uri == "/api/focus" && method == NanoHTTPD.Method.POST -> triggerFocus(body)
            uri == "/api/exposure" && method == NanoHTTPD.Method.POST -> setExposure(body)

            // Snapshot
            uri == "/api/snapshot" && method == NanoHTTPD.Method.POST -> captureSnapshot()
            uri == "/api/snapshot" && method == NanoHTTPD.Method.GET -> captureSnapshot()

            // Configuration
            uri == "/api/config" && method == NanoHTTPD.Method.GET -> getConfig()
            uri == "/api/config" && method == NanoHTTPD.Method.PUT -> updateConfig(body)

            // Encoder
            uri == "/api/encoder/capabilities" && method == NanoHTTPD.Method.GET -> getEncoderCapabilities()

            // Upload service
            uri == "/api/upload/status" && method == NanoHTTPD.Method.GET -> getUploadStatus()
            uri == "/api/upload/queue" && method == NanoHTTPD.Method.GET -> getUploadQueue()
            uri == "/api/upload/start" && method == NanoHTTPD.Method.POST -> startUploads()
            uri == "/api/upload/stop" && method == NanoHTTPD.Method.POST -> stopUploads()
            uri == "/api/upload/enqueue" && method == NanoHTTPD.Method.POST -> enqueueUpload(body)
            uri.startsWith("/api/upload/task/") && method == NanoHTTPD.Method.DELETE -> cancelUploadTask(uri)
            uri == "/api/upload/retry" && method == NanoHTTPD.Method.POST -> retryFailedUploads()
            uri == "/api/upload/clear" && method == NanoHTTPD.Method.POST -> clearPendingUploads()

            // S3 configuration
            uri == "/api/upload/s3/config" && method == NanoHTTPD.Method.GET -> getS3Config()
            uri == "/api/upload/s3/config" && method == NanoHTTPD.Method.POST -> configureS3(body)
            uri == "/api/upload/s3/config" && method == NanoHTTPD.Method.DELETE -> clearS3Config()
            uri == "/api/upload/s3/test" && method == NanoHTTPD.Method.POST -> testS3Connection()

            // SMB configuration
            uri == "/api/upload/smb/config" && method == NanoHTTPD.Method.GET -> getSmbConfig()
            uri == "/api/upload/smb/config" && method == NanoHTTPD.Method.POST -> configureSmb(body)
            uri == "/api/upload/smb/config" && method == NanoHTTPD.Method.DELETE -> clearSmbConfig()
            uri == "/api/upload/smb/test" && method == NanoHTTPD.Method.POST -> testSmbConnection()

            // Not found
            else -> NanoHTTPD.newFixedLengthResponse(
                Status.NOT_FOUND,
                WebServer.MIME_JSON,
                """{"error": "Endpoint not found", "uri": "$uri", "method": "${method.name}"}"""
            )
        }
    }

    /**
     * Parse request body
     */
    private fun parseBody(session: NanoHTTPD.IHTTPSession): JSONObject? {
        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: return null
            JSONObject(postData)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error parsing request body")
            null
        }
    }

    // ==================== Status Endpoints ====================

    /**
     * GET /api/status - Get overall system status
     */
    private fun getStatus(): NanoHTTPD.Response {
        val camera = cameraService
        val json = JSONObject().apply {
            put("status", "ok")
            put("timestamp", System.currentTimeMillis())

            // Camera status
            put("camera", JSONObject().apply {
                put("previewActive", camera?.isPreviewActive() ?: false)
                put("streamingActive", camera?.isStreaming() ?: false)
                val currentLens = camera?.getCurrentLens()?.value
                put("currentLens", currentLens?.lensType?.name ?: "none")
                put("zoom", camera?.currentZoom?.value ?: 1.0f)
            })

            // Encoder status
            put("encoder", JSONObject().apply {
                val state = camera?.encoderState?.value ?: EncoderState.IDLE
                put("state", state.name)
                val stats = camera?.getEncoderStats()
                if (stats != null) {
                    put("framesEncoded", stats.framesEncoded)
                    put("currentBitrate", stats.currentBitrateBps)
                    put("currentFps", stats.currentFps)
                }
            })

            // RTSP status
            put("rtsp", JSONObject().apply {
                put("running", camera?.isRtspServerRunning() ?: false)
                put("url", camera?.getRtspUrl() ?: "")
                put("clients", camera?.getRtspClientCount() ?: 0)
                put("playing", camera?.getRtspPlayingCount() ?: 0)
            })
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    /**
     * GET /api/device - Get device information
     */
    private fun getDeviceInfo(): NanoHTTPD.Response {
        val json = JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkVersion", Build.VERSION.SDK_INT)
            put("appVersion", "1.0.0")
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    // ==================== Stream Control ====================

    /**
     * POST /api/stream/start - Start encoding
     */
    private fun startStream(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        // Parse config from body
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

    /**
     * POST /api/stream/stop - Stop encoding
     */
    private fun stopStream(): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()
        camera.stopStreaming()

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK,
            WebServer.MIME_JSON,
            """{"success": true, "message": "Streaming stopped"}"""
        )
    }

    /**
     * GET /api/stream/status - Get stream status
     */
    private fun getStreamStatus(): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

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

    /**
     * POST /api/rtsp/start - Start RTSP server
     */
    private fun startRtsp(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        val port = body?.optInt("port", 8554) ?: 8554

        // Parse encoder config
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
            WebServer.MIME_JSON,
            json.toString()
        )
    }

    /**
     * POST /api/rtsp/stop - Stop RTSP server
     */
    private fun stopRtsp(): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()
        camera.stopRtspStreaming()

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK,
            WebServer.MIME_JSON,
            """{"success": true, "message": "RTSP streaming stopped"}"""
        )
    }

    /**
     * GET /api/rtsp/status - Get RTSP status
     */
    private fun getRtspStatus(): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

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

    // ==================== Recording Control ====================

    /**
     * POST /api/recording/start - Start local recording
     */
    private fun startRecording(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        // Parse config
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
            WebServer.MIME_JSON,
            json.toString()
        )
    }

    /**
     * POST /api/recording/stop - Stop local recording
     */
    private fun stopRecording(): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

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

    /**
     * POST /api/recording/pause - Pause recording
     */
    private fun pauseRecording(): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        val success = camera.pauseRecording()

        return NanoHTTPD.newFixedLengthResponse(
            if (success) Status.OK else Status.BAD_REQUEST,
            WebServer.MIME_JSON,
            """{"success": $success, "message": "${if (success) "Recording paused" else "Not recording"}"}"""
        )
    }

    /**
     * POST /api/recording/resume - Resume recording
     */
    private fun resumeRecording(): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        val success = camera.resumeRecording()

        return NanoHTTPD.newFixedLengthResponse(
            if (success) Status.OK else Status.BAD_REQUEST,
            WebServer.MIME_JSON,
            """{"success": $success, "message": "${if (success) "Recording resumed" else "Not paused"}"}"""
        )
    }

    /**
     * GET /api/recording/status - Get recording status
     */
    private fun getRecordingStatus(): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

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

    /**
     * GET /api/recordings - List all recordings
     */
    private fun listRecordings(): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

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
     * DELETE /api/recordings/{filename} - Delete a recording
     */
    private fun deleteRecording(uri: String): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        val filename = uri.substringAfterLast("/")
        if (filename.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(
                Status.BAD_REQUEST,
                WebServer.MIME_JSON,
                """{"error": "Filename required"}"""
            )
        }

        // Find the recording
        val recordings = camera.listRecordings()
        val recording = recordings.find { it.name == filename }
            ?: return NanoHTTPD.newFixedLengthResponse(
                Status.NOT_FOUND,
                WebServer.MIME_JSON,
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

    /**
     * GET /api/storage/status - Get storage status
     */
    private fun getStorageStatus(): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

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
     * POST /api/storage/cleanup - Enforce retention policy
     */
    private fun enforceRetention(): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        camera.enforceRetention()

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK,
            WebServer.MIME_JSON,
            """{"success": true, "message": "Retention enforcement started"}"""
        )
    }

    // ==================== Lens Control ====================

    /**
     * POST /api/lens/{type} - Switch camera lens
     */
    private fun switchLens(uri: String): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        val lensName = uri.substringAfterLast("/")
        val lensType = when (lensName.lowercase()) {
            "wide", "ultrawide" -> LensType.WIDE
            "main", "primary" -> LensType.MAIN
            "tele", "telephoto", "zoom" -> LensType.TELEPHOTO
            else -> return NanoHTTPD.newFixedLengthResponse(
                Status.BAD_REQUEST,
                WebServer.MIME_JSON,
                """{"error": "Unknown lens type: $lensName"}"""
            )
        }

        camera.switchLens(lensType)

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK,
            WebServer.MIME_JSON,
            """{"success": true, "lens": "${lensType.name}"}"""
        )
    }

    /**
     * GET /api/lenses - Get available lenses
     */
    private fun getLenses(): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        val lenses = camera.getAvailableLenses()
        val currentLens = camera.getCurrentLens().value

        val json = JSONObject().apply {
            put("current", currentLens?.lensType?.name ?: "none")
            put("available", JSONArray().apply {
                lenses.forEach { lens ->
                    put(JSONObject().apply {
                        put("type", lens.lensType.name)
                        put("displayName", lens.lensType.displayName)
                        put("cameraId", lens.cameraId)
                        put("focalLength", lens.focalLength)
                        put("aperture", lens.aperture)
                    })
                }
            })
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    // ==================== Camera Control ====================

    /**
     * POST /api/zoom - Set zoom level
     */
    private fun setZoom(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        val zoom = body?.optDouble("zoom", 1.0) ?: 1.0

        camera.setZoom(zoom.toFloat())

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK,
            WebServer.MIME_JSON,
            """{"success": true, "zoom": $zoom}"""
        )
    }

    /**
     * POST /api/focus - Trigger focus at point
     */
    private fun triggerFocus(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        val x = body?.optDouble("x", 0.5) ?: 0.5
        val y = body?.optDouble("y", 0.5) ?: 0.5

        // Trigger tap-to-focus at normalized coordinates
        camera.triggerTapToFocus(
            x.toFloat(),
            y.toFloat(),
            android.util.Size(1920, 1080) // Assume 1080p
        )

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK,
            WebServer.MIME_JSON,
            """{"success": true, "x": $x, "y": $y}"""
        )
    }

    /**
     * POST /api/exposure - Set exposure compensation
     */
    private fun setExposure(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        val ev = body?.optInt("ev", 0) ?: 0

        camera.setExposureCompensation(ev)

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK,
            WebServer.MIME_JSON,
            """{"success": true, "ev": $ev}"""
        )
    }

    // ==================== Snapshot ====================

    /**
     * GET/POST /api/snapshot - Capture JPEG snapshot
     */
    private fun captureSnapshot(): NanoHTTPD.Response {
        val callback = onSnapshotRequest ?: return NanoHTTPD.newFixedLengthResponse(
            Status.SERVICE_UNAVAILABLE,
            WebServer.MIME_JSON,
            """{"error": "Snapshot not available"}"""
        )

        val jpegData = callback.invoke() ?: return NanoHTTPD.newFixedLengthResponse(
            Status.INTERNAL_ERROR,
            WebServer.MIME_JSON,
            """{"error": "Failed to capture snapshot"}"""
        )

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK,
            WebServer.MIME_JPEG,
            ByteArrayInputStream(jpegData),
            jpegData.size.toLong()
        )
    }

    // ==================== Configuration ====================

    /**
     * GET /api/config - Get current configuration
     */
    private fun getConfig(): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        val encoderConfig = camera.getEncoderConfig()
        val captureConfig = camera.getConfig()

        val json = JSONObject().apply {
            put("encoder", JSONObject().apply {
                if (encoderConfig != null) {
                    put("width", encoderConfig.width)
                    put("height", encoderConfig.height)
                    put("bitrate", encoderConfig.bitrateBps)
                    put("frameRate", encoderConfig.frameRate)
                    put("codec", encoderConfig.codec.name)
                    put("keyframeInterval", encoderConfig.keyframeIntervalSec)
                }
            })
            put("camera", JSONObject().apply {
                put("resolution", "${captureConfig.resolution.width}x${captureConfig.resolution.height}")
                put("focusMode", captureConfig.focusMode.name)
                put("whiteBalance", captureConfig.whiteBalance.name)
                put("exposureCompensation", captureConfig.exposureCompensation)
                put("zoom", captureConfig.zoomRatio)
            })
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    /**
     * PUT /api/config - Update configuration
     */
    private fun updateConfig(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        body ?: return NanoHTTPD.newFixedLengthResponse(
            Status.BAD_REQUEST,
            WebServer.MIME_JSON,
            """{"error": "Request body required"}"""
        )

        // Update encoder config if streaming
        if (body.has("bitrate")) {
            camera.updateEncoderBitrate(body.getInt("bitrate"))
        }

        // Update camera config
        if (body.has("zoom")) {
            camera.setZoom(body.getDouble("zoom").toFloat())
        }
        if (body.has("exposureCompensation")) {
            camera.setExposureCompensation(body.getInt("exposureCompensation"))
        }

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK,
            WebServer.MIME_JSON,
            """{"success": true, "message": "Configuration updated"}"""
        )
    }

    // ==================== Encoder ====================

    /**
     * GET /api/encoder/capabilities - Get encoder capabilities
     */
    private fun getEncoderCapabilities(): NanoHTTPD.Response {
        val json = JSONObject().apply {
            put("codecs", JSONArray().apply {
                put("H264")
                put("H265")
            })
            put("resolutions", JSONArray().apply {
                put(JSONObject().apply { put("name", "720p"); put("width", 1280); put("height", 720) })
                put(JSONObject().apply { put("name", "1080p"); put("width", 1920); put("height", 1080) })
                put(JSONObject().apply { put("name", "1080p60"); put("width", 1920); put("height", 1080) })
                put(JSONObject().apply { put("name", "4K"); put("width", 3840); put("height", 2160) })
            })
            put("frameRates", JSONArray().apply { put(15); put(24); put(30); put(60) })
            put("bitrateRange", JSONObject().apply {
                put("min", 500000)
                put("max", 20000000)
            })
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    // ==================== Upload Service ====================

    /**
     * GET /api/upload/status - Get upload service status
     */
    private fun getUploadStatus(): NanoHTTPD.Response {
        val upload = uploadService ?: return uploadServiceUnavailable()

        val stats = upload.stats.value
        val queueStats = stats.queueStats

        val json = JSONObject().apply {
            put("state", stats.state.name)
            put("isS3Configured", stats.isS3Configured)
            put("isSmbConfigured", stats.isSmbConfigured)
            put("currentFile", stats.currentFileName ?: "")
            put("currentProgress", stats.currentProgress)
            put("queue", JSONObject().apply {
                put("totalCount", queueStats.totalCount)
                put("pendingCount", queueStats.pendingCount)
                put("uploadingCount", queueStats.uploadingCount)
                put("completedCount", queueStats.completedCount)
                put("failedCount", queueStats.failedCount)
                put("cancelledCount", queueStats.cancelledCount)
                put("totalBytes", queueStats.totalBytes)
                put("uploadedBytes", queueStats.uploadedBytes)
                put("currentProgress", queueStats.currentProgress)
            })
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    /**
     * GET /api/upload/queue - Get upload queue details
     */
    private fun getUploadQueue(): NanoHTTPD.Response {
        val upload = uploadService ?: return uploadServiceUnavailable()

        val pending = upload.getPendingTasks()
        val completed = upload.getCompletedTasks()

        val json = JSONObject().apply {
            put("pending", JSONArray().apply {
                pending.forEach { task ->
                    put(JSONObject().apply {
                        put("id", task.id)
                        put("fileName", task.fileName)
                        put("localPath", task.localPath)
                        put("remotePath", task.remotePath)
                        put("destination", task.destination.name)
                        put("status", task.status.name)
                        put("fileSize", task.fileSize)
                        put("bytesUploaded", task.bytesUploaded)
                        put("progress", task.progress)
                        put("retryCount", task.retryCount)
                        put("error", task.error ?: "")
                    })
                }
            })
            put("completed", JSONArray().apply {
                completed.forEach { task ->
                    put(JSONObject().apply {
                        put("id", task.id)
                        put("fileName", task.fileName)
                        put("destination", task.destination.name)
                        put("status", task.status.name)
                        put("fileSize", task.fileSize)
                    })
                }
            })
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    /**
     * POST /api/upload/start - Start upload processing
     */
    private fun startUploads(): NanoHTTPD.Response {
        val upload = uploadService ?: return uploadServiceUnavailable()
        upload.startUploads()

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK,
            WebServer.MIME_JSON,
            """{"success": true, "message": "Upload processing started"}"""
        )
    }

    /**
     * POST /api/upload/stop - Stop upload processing
     */
    private fun stopUploads(): NanoHTTPD.Response {
        val upload = uploadService ?: return uploadServiceUnavailable()
        upload.stopUploads()

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK,
            WebServer.MIME_JSON,
            """{"success": true, "message": "Upload processing stopped"}"""
        )
    }

    /**
     * POST /api/upload/enqueue - Enqueue a file for upload
     */
    private fun enqueueUpload(body: JSONObject?): NanoHTTPD.Response {
        val upload = uploadService ?: return uploadServiceUnavailable()

        body ?: return NanoHTTPD.newFixedLengthResponse(
            Status.BAD_REQUEST,
            WebServer.MIME_JSON,
            """{"error": "Request body required"}"""
        )

        val filePath = body.optString("filePath", "")
        val remotePath = body.optString("remotePath", "")
        val destinationStr = body.optString("destination", "S3")
        val deleteAfter = body.optBoolean("deleteAfterUpload", false)

        if (filePath.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(
                Status.BAD_REQUEST,
                WebServer.MIME_JSON,
                """{"error": "filePath is required"}"""
            )
        }

        val destination = try {
            UploadDestination.valueOf(destinationStr.uppercase())
        } catch (e: Exception) {
            return NanoHTTPD.newFixedLengthResponse(
                Status.BAD_REQUEST,
                WebServer.MIME_JSON,
                """{"error": "Invalid destination: $destinationStr. Use S3 or SMB"}"""
            )
        }

        // Use filename as remote path if not specified
        val effectiveRemotePath = if (remotePath.isEmpty()) {
            filePath.substringAfterLast("/")
        } else {
            remotePath
        }

        val task = upload.enqueueFile(filePath, effectiveRemotePath, destination, deleteAfter)

        return if (task != null) {
            val json = JSONObject().apply {
                put("success", true)
                put("taskId", task.id)
                put("fileName", task.fileName)
                put("destination", destination.name)
                put("message", "File enqueued for upload")
            }
            NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
        } else {
            NanoHTTPD.newFixedLengthResponse(
                Status.BAD_REQUEST,
                WebServer.MIME_JSON,
                """{"error": "Failed to enqueue file. Check if file exists."}"""
            )
        }
    }

    /**
     * DELETE /api/upload/task/{id} - Cancel an upload task
     */
    private fun cancelUploadTask(uri: String): NanoHTTPD.Response {
        val upload = uploadService ?: return uploadServiceUnavailable()

        val taskId = uri.substringAfterLast("/")
        if (taskId.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(
                Status.BAD_REQUEST,
                WebServer.MIME_JSON,
                """{"error": "Task ID required"}"""
            )
        }

        val success = upload.cancelTask(taskId)

        return NanoHTTPD.newFixedLengthResponse(
            if (success) Status.OK else Status.NOT_FOUND,
            WebServer.MIME_JSON,
            """{"success": $success, "taskId": "$taskId"}"""
        )
    }

    /**
     * POST /api/upload/retry - Retry all failed uploads
     */
    private fun retryFailedUploads(): NanoHTTPD.Response {
        val upload = uploadService ?: return uploadServiceUnavailable()
        upload.retryFailed()

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK,
            WebServer.MIME_JSON,
            """{"success": true, "message": "Retrying failed uploads"}"""
        )
    }

    /**
     * POST /api/upload/clear - Clear all pending uploads
     */
    private fun clearPendingUploads(): NanoHTTPD.Response {
        val upload = uploadService ?: return uploadServiceUnavailable()
        upload.clearPending()

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK,
            WebServer.MIME_JSON,
            """{"success": true, "message": "Pending uploads cleared"}"""
        )
    }

    // ==================== S3 Configuration ====================

    /**
     * GET /api/upload/s3/config - Get S3 configuration (masked)
     */
    private fun getS3Config(): NanoHTTPD.Response {
        val upload = uploadService ?: return uploadServiceUnavailable()

        val creds = upload.getS3CredentialsSafe()

        if (creds == null) {
            return NanoHTTPD.newFixedLengthResponse(
                Status.OK,
                WebServer.MIME_JSON,
                """{"configured": false}"""
            )
        }

        val json = JSONObject().apply {
            put("configured", true)
            put("endpoint", creds.endpoint)
            put("region", creds.region)
            put("bucket", creds.bucket)
            put("accessKeyId", creds.accessKeyId)
            put("pathPrefix", creds.pathPrefix)
            put("useHttps", creds.useHttps)
            put("backend", creds.backend.name)
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    /**
     * POST /api/upload/s3/config - Configure S3 credentials
     */
    private fun configureS3(body: JSONObject?): NanoHTTPD.Response {
        val upload = uploadService ?: return uploadServiceUnavailable()

        body ?: return NanoHTTPD.newFixedLengthResponse(
            Status.BAD_REQUEST,
            WebServer.MIME_JSON,
            """{"error": "Request body required"}"""
        )

        val endpoint = body.optString("endpoint", "")
        val region = body.optString("region", "us-east-1")
        val bucket = body.optString("bucket", "")
        val accessKeyId = body.optString("accessKeyId", "")
        val secretAccessKey = body.optString("secretAccessKey", "")
        val pathPrefix = body.optString("pathPrefix", "")
        val useHttps = body.optBoolean("useHttps", true)
        val backendStr = body.optString("backend", "S3")

        if (bucket.isEmpty() || accessKeyId.isEmpty() || secretAccessKey.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(
                Status.BAD_REQUEST,
                WebServer.MIME_JSON,
                """{"error": "bucket, accessKeyId, and secretAccessKey are required"}"""
            )
        }

        val backend = try {
            StorageBackend.valueOf(backendStr.uppercase())
        } catch (e: Exception) {
            StorageBackend.S3
        }

        // Build endpoint if not provided
        val effectiveEndpoint = if (endpoint.isEmpty()) {
            when (backend) {
                StorageBackend.S3 -> "s3.$region.amazonaws.com"
                StorageBackend.BACKBLAZE_B2 -> "s3.$region.backblazeb2.com"
                else -> ""
            }
        } else {
            endpoint
        }

        if (effectiveEndpoint.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(
                Status.BAD_REQUEST,
                WebServer.MIME_JSON,
                """{"error": "endpoint is required for this backend type"}"""
            )
        }

        val credentials = S3Credentials(
            endpoint = effectiveEndpoint,
            region = region,
            bucket = bucket,
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            pathPrefix = pathPrefix,
            useHttps = useHttps,
            backend = backend
        )

        upload.configureS3(credentials)

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK,
            WebServer.MIME_JSON,
            """{"success": true, "message": "S3 configured", "backend": "${backend.name}", "bucket": "$bucket"}"""
        )
    }

    /**
     * DELETE /api/upload/s3/config - Clear S3 credentials
     */
    private fun clearS3Config(): NanoHTTPD.Response {
        val upload = uploadService ?: return uploadServiceUnavailable()
        upload.clearS3Credentials()

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK,
            WebServer.MIME_JSON,
            """{"success": true, "message": "S3 credentials cleared"}"""
        )
    }

    /**
     * POST /api/upload/s3/test - Test S3 connection
     */
    private fun testS3Connection(): NanoHTTPD.Response {
        val upload = uploadService ?: return uploadServiceUnavailable()

        if (!upload.isS3Configured()) {
            return NanoHTTPD.newFixedLengthResponse(
                Status.BAD_REQUEST,
                WebServer.MIME_JSON,
                """{"success": false, "error": "S3 not configured"}"""
            )
        }

        return runBlocking {
            val result = upload.testS3Connection()
            if (result.isSuccess) {
                NanoHTTPD.newFixedLengthResponse(
                    Status.OK,
                    WebServer.MIME_JSON,
                    """{"success": true, "message": "S3 connection successful"}"""
                )
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                NanoHTTPD.newFixedLengthResponse(
                    Status.OK,
                    WebServer.MIME_JSON,
                    """{"success": false, "error": "$error"}"""
                )
            }
        }
    }

    // ==================== SMB Configuration ====================

    /**
     * GET /api/upload/smb/config - Get SMB configuration (masked)
     */
    private fun getSmbConfig(): NanoHTTPD.Response {
        val upload = uploadService ?: return uploadServiceUnavailable()

        val creds = upload.getSmbCredentialsSafe()

        if (creds == null) {
            return NanoHTTPD.newFixedLengthResponse(
                Status.OK,
                WebServer.MIME_JSON,
                """{"configured": false}"""
            )
        }

        val json = JSONObject().apply {
            put("configured", true)
            put("server", creds.server)
            put("share", creds.share)
            put("username", creds.username)
            put("domain", creds.domain)
            put("port", creds.port)
            put("pathPrefix", creds.pathPrefix)
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    /**
     * POST /api/upload/smb/config - Configure SMB credentials
     */
    private fun configureSmb(body: JSONObject?): NanoHTTPD.Response {
        val upload = uploadService ?: return uploadServiceUnavailable()

        body ?: return NanoHTTPD.newFixedLengthResponse(
            Status.BAD_REQUEST,
            WebServer.MIME_JSON,
            """{"error": "Request body required"}"""
        )

        val server = body.optString("server", "")
        val share = body.optString("share", "")
        val username = body.optString("username", "")
        val password = body.optString("password", "")
        val domain = body.optString("domain", "")
        val port = body.optInt("port", 445)
        val pathPrefix = body.optString("pathPrefix", "")

        if (server.isEmpty() || share.isEmpty() || username.isEmpty() || password.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(
                Status.BAD_REQUEST,
                WebServer.MIME_JSON,
                """{"error": "server, share, username, and password are required"}"""
            )
        }

        val credentials = SmbCredentials(
            server = server,
            share = share,
            username = username,
            password = password,
            domain = domain,
            port = port,
            pathPrefix = pathPrefix
        )

        upload.configureSmb(credentials)

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK,
            WebServer.MIME_JSON,
            """{"success": true, "message": "SMB configured", "server": "$server", "share": "$share"}"""
        )
    }

    /**
     * DELETE /api/upload/smb/config - Clear SMB credentials
     */
    private fun clearSmbConfig(): NanoHTTPD.Response {
        val upload = uploadService ?: return uploadServiceUnavailable()
        upload.clearSmbCredentials()

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK,
            WebServer.MIME_JSON,
            """{"success": true, "message": "SMB credentials cleared"}"""
        )
    }

    /**
     * POST /api/upload/smb/test - Test SMB connection
     */
    private fun testSmbConnection(): NanoHTTPD.Response {
        val upload = uploadService ?: return uploadServiceUnavailable()

        if (!upload.isSmbConfigured()) {
            return NanoHTTPD.newFixedLengthResponse(
                Status.BAD_REQUEST,
                WebServer.MIME_JSON,
                """{"success": false, "error": "SMB not configured"}"""
            )
        }

        return runBlocking {
            val result = upload.testSmbConnection()
            if (result.isSuccess) {
                NanoHTTPD.newFixedLengthResponse(
                    Status.OK,
                    WebServer.MIME_JSON,
                    """{"success": true, "message": "SMB connection successful"}"""
                )
            } else {
                val error = result.exceptionOrNull()?.message ?: "Unknown error"
                NanoHTTPD.newFixedLengthResponse(
                    Status.OK,
                    WebServer.MIME_JSON,
                    """{"success": false, "error": "$error"}"""
                )
            }
        }
    }

    // ==================== Helpers ====================

    private fun uploadServiceUnavailable(): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(
            Status.SERVICE_UNAVAILABLE,
            WebServer.MIME_JSON,
            """{"error": "Upload service not available"}"""
        )
    }

    private fun serviceUnavailable(): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(
            Status.SERVICE_UNAVAILABLE,
            WebServer.MIME_JSON,
            """{"error": "Camera service not available"}"""
        )
    }
}
