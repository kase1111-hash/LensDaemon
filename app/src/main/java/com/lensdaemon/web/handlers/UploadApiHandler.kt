package com.lensdaemon.web.handlers

import com.lensdaemon.storage.S3Credentials
import com.lensdaemon.storage.SmbCredentials
import com.lensdaemon.storage.StorageBackend
import com.lensdaemon.storage.UploadDestination
import com.lensdaemon.storage.UploadService
import com.lensdaemon.web.WebServer
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Handles all /api/upload/ routes (including S3 and SMB sub-routes).
 *
 * Extracted from ApiRoutes to separate upload API concerns into a dedicated handler.
 */
class UploadApiHandler {

    var uploadService: UploadService? = null

    /**
     * Handle an upload API request.
     *
     * @param uri The request URI
     * @param method The HTTP method
     * @param body The parsed JSON body (if any)
     * @return A NanoHTTPD.Response if the URI was handled, or null for unhandled URIs
     */
    fun handleRequest(uri: String, method: NanoHTTPD.Method, body: JSONObject?): NanoHTTPD.Response? {
        return when {
            uri == "/api/upload/status" && method == NanoHTTPD.Method.GET -> getUploadStatus()
            uri == "/api/upload/queue" && method == NanoHTTPD.Method.GET -> getUploadQueue()
            uri == "/api/upload/start" && method == NanoHTTPD.Method.POST -> startUploads()
            uri == "/api/upload/stop" && method == NanoHTTPD.Method.POST -> stopUploads()
            uri == "/api/upload/enqueue" && method == NanoHTTPD.Method.POST -> enqueueUpload(body)
            uri.startsWith("/api/upload/task/") && method == NanoHTTPD.Method.DELETE -> cancelUploadTask(uri)
            uri == "/api/upload/retry" && method == NanoHTTPD.Method.POST -> retryFailedUploads()
            uri == "/api/upload/clear" && method == NanoHTTPD.Method.POST -> clearPendingUploads()
            uri == "/api/upload/s3/config" && method == NanoHTTPD.Method.GET -> getS3Config()
            uri == "/api/upload/s3/config" && method == NanoHTTPD.Method.POST -> configureS3(body)
            uri == "/api/upload/s3/config" && method == NanoHTTPD.Method.DELETE -> clearS3Config()
            uri == "/api/upload/s3/test" && method == NanoHTTPD.Method.POST -> testS3Connection()
            uri == "/api/upload/smb/config" && method == NanoHTTPD.Method.GET -> getSmbConfig()
            uri == "/api/upload/smb/config" && method == NanoHTTPD.Method.POST -> configureSmb(body)
            uri == "/api/upload/smb/config" && method == NanoHTTPD.Method.DELETE -> clearSmbConfig()
            uri == "/api/upload/smb/test" && method == NanoHTTPD.Method.POST -> testSmbConnection()
            else -> null
        }
    }

    private fun getUploadStatus(): NanoHTTPD.Response {
        val upload = uploadService ?: return ApiHandlerUtils.serviceUnavailable("Upload service")
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

    private fun getUploadQueue(): NanoHTTPD.Response {
        val upload = uploadService ?: return ApiHandlerUtils.serviceUnavailable("Upload service")
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

    private fun startUploads(): NanoHTTPD.Response {
        val upload = uploadService ?: return ApiHandlerUtils.serviceUnavailable("Upload service")
        upload.startUploads()
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, """{"success": true, "message": "Upload processing started"}""")
    }

    private fun stopUploads(): NanoHTTPD.Response {
        val upload = uploadService ?: return ApiHandlerUtils.serviceUnavailable("Upload service")
        upload.stopUploads()
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, """{"success": true, "message": "Upload processing stopped"}""")
    }

    private fun enqueueUpload(body: JSONObject?): NanoHTTPD.Response {
        val upload = uploadService ?: return ApiHandlerUtils.serviceUnavailable("Upload service")
        body ?: return ApiHandlerUtils.bodyRequired()
        val filePath = body.optString("filePath", "")
        val remotePath = body.optString("remotePath", "")
        val destinationStr = body.optString("destination", "S3")
        val deleteAfter = body.optBoolean("deleteAfterUpload", false)
        if (filePath.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "filePath is required"}""")
        }
        val destination = try {
            UploadDestination.valueOf(destinationStr.uppercase())
        } catch (e: Exception) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "Invalid destination: $destinationStr. Use S3 or SMB"}""")
        }
        val effectiveRemotePath = if (remotePath.isEmpty()) { filePath.substringAfterLast("/") } else { remotePath }
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
            NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "Failed to enqueue file. Check if file exists."}""")
        }
    }

    private fun cancelUploadTask(uri: String): NanoHTTPD.Response {
        val upload = uploadService ?: return ApiHandlerUtils.serviceUnavailable("Upload service")
        val taskId = uri.substringAfterLast("/")
        if (taskId.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "Task ID required"}""")
        }
        val success = upload.cancelTask(taskId)
        return NanoHTTPD.newFixedLengthResponse(
            if (success) Status.OK else Status.NOT_FOUND, WebServer.MIME_JSON,
            """{"success": $success, "taskId": "$taskId"}"""
        )
    }

    private fun retryFailedUploads(): NanoHTTPD.Response {
        val upload = uploadService ?: return ApiHandlerUtils.serviceUnavailable("Upload service")
        upload.retryFailed()
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, """{"success": true, "message": "Retrying failed uploads"}""")
    }

    private fun clearPendingUploads(): NanoHTTPD.Response {
        val upload = uploadService ?: return ApiHandlerUtils.serviceUnavailable("Upload service")
        upload.clearPending()
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, """{"success": true, "message": "Pending uploads cleared"}""")
    }

    private fun getS3Config(): NanoHTTPD.Response {
        val upload = uploadService ?: return ApiHandlerUtils.serviceUnavailable("Upload service")
        val creds = upload.getS3CredentialsSafe()
        if (creds == null) {
            return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, """{"configured": false}""")
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

    private fun configureS3(body: JSONObject?): NanoHTTPD.Response {
        val upload = uploadService ?: return ApiHandlerUtils.serviceUnavailable("Upload service")
        body ?: return ApiHandlerUtils.bodyRequired()
        val endpoint = body.optString("endpoint", "")
        val region = body.optString("region", "us-east-1")
        val bucket = body.optString("bucket", "")
        val accessKeyId = body.optString("accessKeyId", "")
        val secretAccessKey = body.optString("secretAccessKey", "")
        val pathPrefix = body.optString("pathPrefix", "")
        val useHttps = body.optBoolean("useHttps", true)
        val backendStr = body.optString("backend", "S3")
        if (bucket.isEmpty() || accessKeyId.isEmpty() || secretAccessKey.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "bucket, accessKeyId, and secretAccessKey are required"}""")
        }
        val backend = try { StorageBackend.valueOf(backendStr.uppercase()) } catch (e: Exception) { StorageBackend.S3 }
        val effectiveEndpoint = if (endpoint.isEmpty()) {
            when (backend) {
                StorageBackend.S3 -> "s3.$region.amazonaws.com"
                StorageBackend.BACKBLAZE_B2 -> "s3.$region.backblazeb2.com"
                else -> ""
            }
        } else { endpoint }
        if (effectiveEndpoint.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "endpoint is required for this backend type"}""")
        }
        val credentials = S3Credentials(
            endpoint = effectiveEndpoint, region = region, bucket = bucket,
            accessKeyId = accessKeyId, secretAccessKey = secretAccessKey,
            pathPrefix = pathPrefix, useHttps = useHttps, backend = backend
        )
        upload.configureS3(credentials)
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "S3 configured", "backend": "${backend.name}", "bucket": "$bucket"}""")
    }

    private fun clearS3Config(): NanoHTTPD.Response {
        val upload = uploadService ?: return ApiHandlerUtils.serviceUnavailable("Upload service")
        upload.clearS3Credentials()
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, """{"success": true, "message": "S3 credentials cleared"}""")
    }

    private fun testS3Connection(): NanoHTTPD.Response {
        val upload = uploadService ?: return ApiHandlerUtils.serviceUnavailable("Upload service")
        if (!upload.isS3Configured()) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"success": false, "error": "S3 not configured"}""")
        }
        return runBlocking {
            val result = upload.testS3Connection()
            if (result.isSuccess) {
                NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, """{"success": true, "message": "S3 connection successful"}""")
            } else {
                Timber.tag(TAG).w("S3 connection test failed: ${result.exceptionOrNull()?.message}")
                NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, """{"success": false, "error": "S3 connection test failed"}""")
            }
        }
    }

    private fun getSmbConfig(): NanoHTTPD.Response {
        val upload = uploadService ?: return ApiHandlerUtils.serviceUnavailable("Upload service")
        val creds = upload.getSmbCredentialsSafe()
        if (creds == null) {
            return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, """{"configured": false}""")
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

    private fun configureSmb(body: JSONObject?): NanoHTTPD.Response {
        val upload = uploadService ?: return ApiHandlerUtils.serviceUnavailable("Upload service")
        body ?: return ApiHandlerUtils.bodyRequired()
        val server = body.optString("server", "")
        val share = body.optString("share", "")
        val username = body.optString("username", "")
        val password = body.optString("password", "")
        val domain = body.optString("domain", "")
        val port = body.optInt("port", 445)
        val pathPrefix = body.optString("pathPrefix", "")
        if (server.isEmpty() || share.isEmpty() || username.isEmpty() || password.isEmpty()) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"error": "server, share, username, and password are required"}""")
        }
        val credentials = SmbCredentials(
            server = server, share = share, username = username,
            password = password, domain = domain, port = port, pathPrefix = pathPrefix
        )
        upload.configureSmb(credentials)
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "SMB configured", "server": "$server", "share": "$share"}""")
    }

    private fun clearSmbConfig(): NanoHTTPD.Response {
        val upload = uploadService ?: return ApiHandlerUtils.serviceUnavailable("Upload service")
        upload.clearSmbCredentials()
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, """{"success": true, "message": "SMB credentials cleared"}""")
    }

    private fun testSmbConnection(): NanoHTTPD.Response {
        val upload = uploadService ?: return ApiHandlerUtils.serviceUnavailable("Upload service")
        if (!upload.isSmbConfigured()) {
            return NanoHTTPD.newFixedLengthResponse(Status.BAD_REQUEST, WebServer.MIME_JSON, """{"success": false, "error": "SMB not configured"}""")
        }
        return runBlocking {
            val result = upload.testSmbConnection()
            if (result.isSuccess) {
                NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, """{"success": true, "message": "SMB connection successful"}""")
            } else {
                Timber.tag(TAG).w("SMB connection test failed: ${result.exceptionOrNull()?.message}")
                NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, """{"success": false, "error": "SMB connection test failed"}""")
            }
        }
    }

    companion object {
        private const val TAG = "UploadApiHandler"
    }
}
