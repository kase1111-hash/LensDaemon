package com.lensdaemon.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * S3 upload result
 */
data class S3UploadResult(
    val success: Boolean,
    val etag: String? = null,
    val versionId: String? = null,
    val error: String? = null
)

/**
 * Multipart upload state
 */
data class MultipartUploadState(
    val uploadId: String,
    val bucket: String,
    val key: String,
    val parts: MutableList<UploadedPart> = mutableListOf()
)

/**
 * Uploaded part info
 */
data class UploadedPart(
    val partNumber: Int,
    val etag: String
)

/**
 * S3-compatible storage client
 *
 * Supports:
 * - AWS S3, Backblaze B2, MinIO, Cloudflare R2
 * - AWS Signature V4 authentication
 * - Single PUT upload for small files
 * - Multipart upload for large files (>5MB)
 * - Upload progress reporting
 */
class S3Client(
    private val credentials: S3Credentials
) {
    companion object {
        private const val TAG = "S3Client"
        private const val MULTIPART_THRESHOLD = 5 * 1024 * 1024L // 5MB
        private const val PART_SIZE = 5 * 1024 * 1024L // 5MB parts
        private const val CONNECT_TIMEOUT = 30_000
        private const val READ_TIMEOUT = 60_000

        private const val AWS_ALGORITHM = "AWS4-HMAC-SHA256"
        private const val AWS_SERVICE = "s3"
        private const val AWS_REQUEST = "aws4_request"
    }

    private val dateFormat = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private val datestampFormat = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Test connection to S3 bucket
     */
    suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val date = Date()
            val headers = mutableMapOf<String, String>()

            val url = "${credentials.endpointUrl}/${credentials.bucket}?max-keys=1"
            val request = createSignedRequest("GET", url, headers, ByteArray(0), date)

            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            val responseCode = connection.responseCode
            connection.disconnect()

            if (responseCode in 200..299) {
                Timber.tag(TAG).d("S3 connection test successful")
                Result.success(true)
            } else {
                val error = "S3 connection failed: HTTP $responseCode"
                Timber.tag(TAG).e(error)
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "S3 connection test failed")
            Result.failure(e)
        }
    }

    /**
     * Upload a file to S3
     */
    suspend fun uploadFile(
        localFile: File,
        remotePath: String,
        contentType: String = "application/octet-stream",
        progressCallback: ProgressCallback? = null
    ): Result<S3UploadResult> = withContext(Dispatchers.IO) {
        if (!localFile.exists()) {
            return@withContext Result.failure(Exception("File not found: ${localFile.absolutePath}"))
        }

        val fileSize = localFile.length()
        val key = buildKey(remotePath)

        Timber.tag(TAG).d("Uploading ${localFile.name} ($fileSize bytes) to $key")

        // Use multipart upload for large files
        if (fileSize > MULTIPART_THRESHOLD) {
            uploadMultipart(localFile, key, contentType, progressCallback)
        } else {
            uploadSingle(localFile, key, contentType, progressCallback)
        }
    }

    /**
     * Single PUT upload for small files
     */
    private suspend fun uploadSingle(
        localFile: File,
        key: String,
        contentType: String,
        progressCallback: ProgressCallback?
    ): Result<S3UploadResult> {
        return try {
            val fileSize = localFile.length()
            val fileBytes = localFile.readBytes()
            val contentSha256 = sha256Hex(fileBytes)

            val date = Date()
            val headers = mutableMapOf(
                "Content-Type" to contentType,
                "Content-Length" to fileSize.toString(),
                "x-amz-content-sha256" to contentSha256
            )

            val url = "${credentials.endpointUrl}/${credentials.bucket}/$key"
            val request = createSignedRequest("PUT", url, headers, fileBytes, date)

            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            // Write data with progress
            connection.outputStream.use { output ->
                var bytesWritten = 0L
                val buffer = ByteArray(8192)
                FileInputStream(localFile).use { input ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesWritten += read
                        progressCallback?.invoke(bytesWritten, fileSize)
                    }
                }
            }

            val responseCode = connection.responseCode
            val etag = connection.getHeaderField("ETag")?.trim('"')
            val versionId = connection.getHeaderField("x-amz-version-id")

            connection.disconnect()

            if (responseCode in 200..299) {
                Timber.tag(TAG).i("Upload completed: $key")
                Result.success(S3UploadResult(true, etag, versionId))
            } else {
                val error = "Upload failed: HTTP $responseCode"
                Timber.tag(TAG).e(error)
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Single upload failed")
            Result.failure(e)
        }
    }

    /**
     * Multipart upload for large files
     */
    private suspend fun uploadMultipart(
        localFile: File,
        key: String,
        contentType: String,
        progressCallback: ProgressCallback?
    ): Result<S3UploadResult> {
        val fileSize = localFile.length()
        var uploadId: String? = null

        try {
            // Initiate multipart upload
            uploadId = initiateMultipartUpload(key, contentType)
            if (uploadId == null) {
                return Result.failure(Exception("Failed to initiate multipart upload"))
            }

            val parts = mutableListOf<UploadedPart>()
            var partNumber = 1
            var bytesUploaded = 0L

            FileInputStream(localFile).use { input ->
                val buffer = ByteArray(PART_SIZE.toInt())

                while (true) {
                    val bytesRead = fillBuffer(input, buffer)
                    if (bytesRead <= 0) break

                    val partData = if (bytesRead < buffer.size) {
                        buffer.copyOf(bytesRead)
                    } else {
                        buffer
                    }

                    // Upload part
                    val etag = uploadPart(key, uploadId, partNumber, partData)
                    if (etag == null) {
                        abortMultipartUpload(key, uploadId)
                        return Result.failure(Exception("Failed to upload part $partNumber"))
                    }

                    parts.add(UploadedPart(partNumber, etag))
                    bytesUploaded += bytesRead
                    progressCallback?.invoke(bytesUploaded, fileSize)

                    partNumber++
                }
            }

            // Complete multipart upload
            val result = completeMultipartUpload(key, uploadId, parts)
            if (result != null) {
                Timber.tag(TAG).i("Multipart upload completed: $key (${parts.size} parts)")
                return Result.success(result)
            } else {
                abortMultipartUpload(key, uploadId)
                return Result.failure(Exception("Failed to complete multipart upload"))
            }

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Multipart upload failed")
            uploadId?.let { abortMultipartUpload(key, it) }
            return Result.failure(e)
        }
    }

    /**
     * Initiate multipart upload
     */
    private fun initiateMultipartUpload(key: String, contentType: String): String? {
        return try {
            val date = Date()
            val headers = mutableMapOf(
                "Content-Type" to contentType,
                "x-amz-content-sha256" to sha256Hex(ByteArray(0))
            )

            val url = "${credentials.endpointUrl}/${credentials.bucket}/$key?uploads"
            val request = createSignedRequest("POST", url, headers, ByteArray(0), date)

            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            val responseCode = connection.responseCode
            val response = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                null
            }
            connection.disconnect()

            // Parse upload ID from XML response
            response?.let {
                val regex = "<UploadId>([^<]+)</UploadId>".toRegex()
                regex.find(it)?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to initiate multipart upload")
            null
        }
    }

    /**
     * Upload a part
     */
    private fun uploadPart(key: String, uploadId: String, partNumber: Int, data: ByteArray): String? {
        return try {
            val contentSha256 = sha256Hex(data)
            val date = Date()
            val headers = mutableMapOf(
                "Content-Length" to data.size.toString(),
                "x-amz-content-sha256" to contentSha256
            )

            val url = "${credentials.endpointUrl}/${credentials.bucket}/$key?partNumber=$partNumber&uploadId=$uploadId"
            val request = createSignedRequest("PUT", url, headers, data, date)

            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            connection.outputStream.use { it.write(data) }

            val responseCode = connection.responseCode
            val etag = connection.getHeaderField("ETag")?.trim('"')
            connection.disconnect()

            if (responseCode in 200..299) etag else null
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to upload part $partNumber")
            null
        }
    }

    /**
     * Complete multipart upload
     */
    private fun completeMultipartUpload(
        key: String,
        uploadId: String,
        parts: List<UploadedPart>
    ): S3UploadResult? {
        return try {
            // Build completion XML
            val xml = buildString {
                append("<CompleteMultipartUpload>")
                parts.sortedBy { it.partNumber }.forEach { part ->
                    append("<Part>")
                    append("<PartNumber>${part.partNumber}</PartNumber>")
                    append("<ETag>\"${part.etag}\"</ETag>")
                    append("</Part>")
                }
                append("</CompleteMultipartUpload>")
            }

            val body = xml.toByteArray(Charsets.UTF_8)
            val contentSha256 = sha256Hex(body)
            val date = Date()

            val headers = mutableMapOf(
                "Content-Type" to "application/xml",
                "Content-Length" to body.size.toString(),
                "x-amz-content-sha256" to contentSha256
            )

            val url = "${credentials.endpointUrl}/${credentials.bucket}/$key?uploadId=$uploadId"
            val request = createSignedRequest("POST", url, headers, body, date)

            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            connection.outputStream.use { it.write(body) }

            val responseCode = connection.responseCode
            val etag = connection.getHeaderField("ETag")?.trim('"')
            val versionId = connection.getHeaderField("x-amz-version-id")
            connection.disconnect()

            if (responseCode in 200..299) {
                S3UploadResult(true, etag, versionId)
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to complete multipart upload")
            null
        }
    }

    /**
     * Abort multipart upload
     */
    private fun abortMultipartUpload(key: String, uploadId: String) {
        try {
            val date = Date()
            val headers = mutableMapOf(
                "x-amz-content-sha256" to sha256Hex(ByteArray(0))
            )

            val url = "${credentials.endpointUrl}/${credentials.bucket}/$key?uploadId=$uploadId"
            val request = createSignedRequest("DELETE", url, headers, ByteArray(0), date)

            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            connection.responseCode
            connection.disconnect()

            Timber.tag(TAG).d("Aborted multipart upload: $uploadId")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to abort multipart upload")
        }
    }

    /**
     * Delete an object
     */
    suspend fun deleteObject(remotePath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val key = buildKey(remotePath)
            val date = Date()
            val headers = mutableMapOf(
                "x-amz-content-sha256" to sha256Hex(ByteArray(0))
            )

            val url = "${credentials.endpointUrl}/${credentials.bucket}/$key"
            val request = createSignedRequest("DELETE", url, headers, ByteArray(0), date)

            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            val responseCode = connection.responseCode
            connection.disconnect()

            if (responseCode in 200..299 || responseCode == 404) {
                Result.success(true)
            } else {
                Result.failure(Exception("Delete failed: HTTP $responseCode"))
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Delete failed")
            Result.failure(e)
        }
    }

    /**
     * Build the full object key with path prefix
     */
    private fun buildKey(remotePath: String): String {
        val cleanPath = remotePath.trimStart('/')
        return if (credentials.pathPrefix.isNotEmpty()) {
            "${credentials.pathPrefix.trimEnd('/')}/$cleanPath"
        } else {
            cleanPath
        }
    }

    // ==================== AWS Signature V4 ====================

    private data class SignedRequest(
        val url: String,
        val headers: Map<String, String>
    )

    private fun createSignedRequest(
        method: String,
        url: String,
        headers: MutableMap<String, String>,
        payload: ByteArray,
        date: Date
    ): SignedRequest {
        val amzDate = dateFormat.format(date)
        val datestamp = datestampFormat.format(date)

        val parsedUrl = URL(url)
        val host = parsedUrl.host + (if (parsedUrl.port > 0) ":${parsedUrl.port}" else "")

        headers["Host"] = host
        headers["x-amz-date"] = amzDate

        // Create canonical request
        val canonicalUri = parsedUrl.path.ifEmpty { "/" }
        val canonicalQueryString = parsedUrl.query ?: ""

        val signedHeaders = headers.keys.map { it.lowercase() }.sorted()
        val signedHeadersStr = signedHeaders.joinToString(";")

        val canonicalHeaders = signedHeaders.joinToString("") { key ->
            "$key:${headers.entries.find { it.key.lowercase() == key }?.value?.trim()}\n"
        }

        val payloadHash = headers["x-amz-content-sha256"] ?: sha256Hex(payload)

        val canonicalRequest = listOf(
            method,
            canonicalUri,
            canonicalQueryString,
            canonicalHeaders,
            signedHeadersStr,
            payloadHash
        ).joinToString("\n")

        // Create string to sign
        val credentialScope = "$datestamp/${credentials.region}/$AWS_SERVICE/$AWS_REQUEST"
        val stringToSign = listOf(
            AWS_ALGORITHM,
            amzDate,
            credentialScope,
            sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))
        ).joinToString("\n")

        // Calculate signature
        val signature = calculateSignature(datestamp, stringToSign)

        // Build authorization header
        val authorization = "$AWS_ALGORITHM Credential=${credentials.accessKeyId}/$credentialScope, " +
                "SignedHeaders=$signedHeadersStr, Signature=$signature"

        headers["Authorization"] = authorization

        return SignedRequest(url, headers.toMap())
    }

    private fun calculateSignature(datestamp: String, stringToSign: String): String {
        val kDate = hmacSha256("AWS4${credentials.secretAccessKey}".toByteArray(), datestamp)
        val kRegion = hmacSha256(kDate, credentials.region)
        val kService = hmacSha256(kRegion, AWS_SERVICE)
        val kSigning = hmacSha256(kService, AWS_REQUEST)
        return hmacSha256(kSigning, stringToSign).toHex()
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).toHex()
    }

    private fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }

    private fun fillBuffer(input: InputStream, buffer: ByteArray): Int {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = input.read(buffer, totalRead, buffer.size - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return totalRead
    }
}
