package com.lensdaemon.web.handlers

import com.lensdaemon.storage.S3Credentials
import com.lensdaemon.storage.SmbCredentials
import com.lensdaemon.storage.StorageBackend
import com.lensdaemon.storage.UploadDestination
import com.lensdaemon.storage.UploadService
import com.lensdaemon.web.WebServer
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
            uri == "/api/upload/s3/test" && method == NanoHTTPD.Method.POST -> testS3Connection(body)
            uri == "/api/upload/smb/config" && method == NanoHTTPD.Method.GET -> getSmbConfig()
            uri == "/api/upload/smb/config" && method == NanoHTTPD.Method.POST -> configureSmb(body)
            uri == "/api/upload/smb/config" && method == NanoHTTPD.Method.DELETE -> clearSmbConfig()
            uri == "/api/upload/smb/test" && method == NanoHTTPD.Method.POST -> testSmbConnection(body)
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
                        put("error", task.lastError ?: "")
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
        val credentials = s3CredentialsFrom(body).getOrElse {
            return ApiHandlerUtils.errorJson(Status.BAD_REQUEST, it.message ?: "Invalid S3 settings")
        }
        upload.configureS3(credentials)
        val json = JSONObject().apply {
            put("success", true)
            put("message", "S3 configured")
            put("backend", credentials.backend.name)
            put("bucket", credentials.bucket)
            put("endpoint", credentials.endpoint)
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    /**
     * S3 settings from a request. The endpoint is a host name: one given as a URL
     * ("https://s3.example.com/") is reduced to its host, http:// turning HTTPS off,
     * since the client adds the scheme itself.
     */
    private fun s3CredentialsFrom(body: JSONObject): Result<S3Credentials> {
        val rawEndpoint = body.optString("endpoint", "").trim()
        val useHttps = when {
            rawEndpoint.startsWith("http://", ignoreCase = true) -> false
            rawEndpoint.startsWith("https://", ignoreCase = true) -> true
            else -> body.optBoolean("useHttps", true)
        }
        val endpoint = rawEndpoint.replace(URL_SCHEME, "").trimEnd('/')
        val region = body.optString("region", "").trim().ifEmpty { "us-east-1" }
        val bucket = body.optString("bucket", "").trim()
        val accessKeyId = body.optString("accessKeyId", "").trim()
        val secretAccessKey = body.optString("secretAccessKey", "")
        if (bucket.isEmpty() || accessKeyId.isEmpty() || secretAccessKey.isEmpty()) {
            return Result.failure(IllegalArgumentException("bucket, accessKeyId, and secretAccessKey are required"))
        }
        val backendName = body.optString("backend", "S3")
        val backend = StorageBackend.entries.find { it.name.equals(backendName, ignoreCase = true) } ?: StorageBackend.S3
        val effectiveEndpoint = endpoint.ifEmpty {
            when (backend) {
                StorageBackend.S3 -> "s3.$region.amazonaws.com"
                StorageBackend.BACKBLAZE_B2 -> "s3.$region.backblazeb2.com"
                else -> ""
            }
        }
        if (effectiveEndpoint.isEmpty()) {
            return Result.failure(IllegalArgumentException("endpoint is required for this backend type"))
        }
        return Result.success(
            S3Credentials(
                endpoint = effectiveEndpoint, region = region, bucket = bucket,
                accessKeyId = accessKeyId, secretAccessKey = secretAccessKey,
                pathPrefix = body.optString("pathPrefix", "").trim(), useHttps = useHttps, backend = backend
            )
        )
    }

    private fun clearS3Config(): NanoHTTPD.Response {
        val upload = uploadService ?: return ApiHandlerUtils.serviceUnavailable("Upload service")
        upload.clearS3Credentials()
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, """{"success": true, "message": "S3 credentials cleared"}""")
    }

    /**
     * Test S3 settings sent with the request as given, before they are saved
     * (the setup wizard and the dashboard test what is in the form); without
     * any, test the saved settings.
     */
    private fun testS3Connection(body: JSONObject?): NanoHTTPD.Response {
        val upload = uploadService ?: return ApiHandlerUtils.serviceUnavailable("Upload service")
        if (body != null && body.has("bucket")) {
            val credentials = s3CredentialsFrom(body).getOrElse {
                return testResult(Status.BAD_REQUEST, false, it.message)
            }
            return runConnectionTest("S3") { upload.testS3Connection(credentials) }
        }
        if (!upload.isS3Configured()) {
            return testResult(Status.BAD_REQUEST, false, "S3 not configured")
        }
        return runConnectionTest("S3") { upload.testS3Connection() }
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
        val credentials = smbCredentialsFrom(body).getOrElse {
            return ApiHandlerUtils.errorJson(Status.BAD_REQUEST, it.message ?: "Invalid SMB settings")
        }
        upload.configureSmb(credentials)
        val json = JSONObject().apply {
            put("success", true)
            put("message", "SMB configured")
            put("server", credentials.server)
            put("share", credentials.share)
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    /** SMB settings from a request; a server given as smb://host or \\\\host is reduced to the host. */
    private fun smbCredentialsFrom(body: JSONObject): Result<SmbCredentials> {
        val server = body.optString("server", "").trim().replace(SMB_PREFIX, "").trimEnd('/', '\\')
        val share = body.optString("share", "").trim().trim('/', '\\')
        val username = body.optString("username", "").trim()
        val password = body.optString("password", "")
        if (listOf(server, share, username, password).any { it.isEmpty() }) {
            return Result.failure(IllegalArgumentException("server, share, username, and password are required"))
        }
        return Result.success(
            SmbCredentials(
                server = server, share = share, username = username, password = password,
                domain = body.optString("domain", "").trim(),
                port = body.optInt("port", SMB_DEFAULT_PORT),
                pathPrefix = body.optString("pathPrefix", "").trim()
            )
        )
    }

    private fun clearSmbConfig(): NanoHTTPD.Response {
        val upload = uploadService ?: return ApiHandlerUtils.serviceUnavailable("Upload service")
        upload.clearSmbCredentials()
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, """{"success": true, "message": "SMB credentials cleared"}""")
    }

    /** Test SMB settings sent with the request as given, before they are saved; without any, the saved settings. */
    private fun testSmbConnection(body: JSONObject?): NanoHTTPD.Response {
        val upload = uploadService ?: return ApiHandlerUtils.serviceUnavailable("Upload service")
        if (body != null && body.has("server")) {
            val credentials = smbCredentialsFrom(body).getOrElse {
                return testResult(Status.BAD_REQUEST, false, it.message)
            }
            return runConnectionTest("SMB") { upload.testSmbConnection(credentials) }
        }
        if (!upload.isSmbConfigured()) {
            return testResult(Status.BAD_REQUEST, false, "SMB not configured")
        }
        return runConnectionTest("SMB") { upload.testSmbConnection() }
    }

    /** Run a connection test, answering with whether it worked and, if not, why. */
    private fun runConnectionTest(name: String, test: suspend () -> Result<Boolean>): NanoHTTPD.Response = try {
        runBlocking {
            withTimeout(CONNECTION_TEST_TIMEOUT_MS) {
                val result = test()
                if (result.getOrDefault(false)) {
                    testResult(Status.OK, true, "$name connection successful")
                } else {
                    val reason = result.exceptionOrNull()?.message
                    Timber.tag(TAG).w("$name connection test failed: $reason")
                    val message = if (reason.isNullOrBlank()) "$name connection test failed" else "$name connection test failed: $reason"
                    testResult(Status.OK, false, message)
                }
            }
        }
    } catch (e: TimeoutCancellationException) {
        Timber.tag(TAG).w(e, "$name connection test timed out")
        testResult(Status.OK, false, "$name connection test timed out")
    }

    private fun testResult(status: Status, success: Boolean, message: String?): NanoHTTPD.Response {
        val json = JSONObject().put("success", success).put(if (success) "message" else "error", message ?: "Invalid settings")
        return NanoHTTPD.newFixedLengthResponse(status, WebServer.MIME_JSON, json.toString())
    }

    companion object {
        private const val TAG = "UploadApiHandler"
        private const val CONNECTION_TEST_TIMEOUT_MS = 15_000L
        private const val SMB_DEFAULT_PORT = 445
        private val URL_SCHEME = Regex("^https?://", RegexOption.IGNORE_CASE)
        private val SMB_PREFIX = Regex("""^(smb://|\\\\|//)""", RegexOption.IGNORE_CASE)
    }
}
