package com.lensdaemon.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
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

// Characters SigV4 leaves as they are (RFC 3986 unreserved)
private const val UNRESERVED_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
private const val HEX_DIGITS = "0123456789ABCDEF"
private val S3_ERROR_CODE = Regex("<Code>([^<]+)</Code>")

/**
 * URI-encode [value] the way AWS Signature V4 wants it: A-Z, a-z, 0-9, '-', '_', '.' and
 * '~' stay as they are (and '/' when [keepSlash]), every other UTF-8 byte becomes %XX
 * with upper-case hex digits. A space becomes %20, never '+'.
 */
internal fun uriEncode(value: String, keepSlash: Boolean): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    val out = StringBuilder(bytes.size * 3)
    for (byte in bytes) {
        val code = byte.toInt() and 0xFF
        val c = code.toChar()
        if (c in UNRESERVED_CHARS || (keepSlash && c == '/')) {
            out.append(c)
        } else {
            out.append('%').append(HEX_DIGITS[code shr 4]).append(HEX_DIGITS[code and 0x0F])
        }
    }
    return out.toString()
}

/**
 * The SigV4 canonical query string for a raw (encoded) query: every parameter decoded and
 * encoded again with [uriEncode], a parameter without a value written as `name=`, sorted
 * by name and then value. "uploads" gives "uploads=", null or "" gives "".
 */
internal fun canonicalQueryString(rawQuery: String?): String {
    if (rawQuery.isNullOrEmpty()) return ""
    return rawQuery.split('&')
        .filter { it.isNotEmpty() }
        .map { param ->
            val eq = param.indexOf('=')
            val name = if (eq < 0) param else param.substring(0, eq)
            val value = if (eq < 0) "" else param.substring(eq + 1)
            uriEncode(percentDecode(name), keepSlash = false) to uriEncode(percentDecode(value), keepSlash = false)
        }
        .sortedWith(compareBy<Pair<String, String>>({ it.first }, { it.second }))
        .joinToString("&") { (name, value) -> "$name=$value" }
}

// Decode %XX sequences only ('+' stays a plus sign, as in a URI query)
private fun percentDecode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")

/**
 * The failure text for an S3 error answer: "HTTP 403 SignatureDoesNotMatch", or just
 * "HTTP 403" when the body carries no S3 error code.
 */
internal fun s3ErrorText(responseCode: Int, body: String?): String {
    val code = body?.let { S3_ERROR_CODE.find(it)?.groupValues?.get(1) }
    return if (code.isNullOrBlank()) "HTTP $responseCode" else "HTTP $responseCode $code"
}

/**
 * S3-compatible storage client
 *
 * Supports:
 * - AWS S3, Backblaze B2, MinIO, Cloudflare R2
 * - AWS Signature V4 authentication (path-style URLs, URI-encoded object keys)
 * - Single PUT upload for small files
 * - Multipart upload for large files (>5MB)
 * - Upload progress reporting
 *
 * Failures carry the HTTP status and the S3 error code, for example
 * "Upload failed: HTTP 403 SignatureDoesNotMatch".
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
        private const val CONTENT_SHA256 = "x-amz-content-sha256"

        // Immutable, so safe to share between upload threads
        private val AMZ_DATE_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US).withZone(ZoneOffset.UTC)
        private val DATESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US).withZone(ZoneOffset.UTC)
        private val WHITESPACE = Regex("\\s+")
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
            val failure = if (responseCode in 200..299) null else httpFailure(connection, responseCode)
            connection.disconnect()

            if (failure == null) {
                Timber.tag(TAG).d("S3 connection test successful")
                Result.success(true)
            } else {
                // The API puts "S3 connection test failed: " in front
                Timber.tag(TAG).e("S3 connection test failed: $failure")
                Result.failure(Exception(failure))
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
     * Single PUT upload for small files.
     * Streams file content to avoid loading entire file into memory.
     */
    private suspend fun uploadSingle(
        localFile: File,
        key: String,
        contentType: String,
        progressCallback: ProgressCallback?
    ): Result<S3UploadResult> {
        return try {
            val fileSize = localFile.length()

            // Compute SHA256 incrementally to avoid loading entire file into memory
            val contentSha256 = sha256HexFile(localFile)

            val date = Date()
            val headers = mutableMapOf(
                "Content-Type" to contentType,
                "Content-Length" to fileSize.toString(),
                CONTENT_SHA256 to contentSha256
            )

            val url = objectUrl(key)
            // The payload hash is the file's, already in x-amz-content-sha256
            val request = createSignedRequest("PUT", url, headers, ByteArray(0), date)

            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                setFixedLengthStreamingMode(fileSize)
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            // Stream file directly to connection output — never buffer full file in memory
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
            val failure = if (responseCode in 200..299) null else httpFailure(connection, responseCode)

            connection.disconnect()

            if (failure == null) {
                Timber.tag(TAG).i("Upload completed: $key")
                Result.success(S3UploadResult(true, etag, versionId))
            } else {
                val error = "Upload failed: $failure"
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
        val uploadId = initiateMultipartUpload(key, contentType).getOrElse { e ->
            return Result.failure(Exception("Failed to initiate multipart upload: ${e.message}", e))
        }

        return try {
            val parts = uploadParts(localFile, key, uploadId, progressCallback)
            val completed = completeMultipartUpload(key, uploadId, parts).getOrElse { e ->
                abortMultipartUpload(key, uploadId)
                return Result.failure(Exception("Failed to complete multipart upload: ${e.message}", e))
            }
            Timber.tag(TAG).i("Multipart upload completed: $key (${parts.size} parts)")
            Result.success(completed)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Multipart upload failed")
            abortMultipartUpload(key, uploadId)
            Result.failure(e)
        }
    }

    /**
     * Upload the file in [PART_SIZE] parts.
     *
     * @throws IOException when a part fails, with the part number and the S3 error
     */
    private fun uploadParts(
        localFile: File,
        key: String,
        uploadId: String,
        progressCallback: ProgressCallback?
    ): List<UploadedPart> {
        val fileSize = localFile.length()
        val parts = mutableListOf<UploadedPart>()
        var partNumber = 1
        var bytesUploaded = 0L

        FileInputStream(localFile).use { input ->
            val buffer = ByteArray(PART_SIZE.toInt())
            var bytesRead = fillBuffer(input, buffer)
            while (bytesRead > 0) {
                val partData = if (bytesRead < buffer.size) buffer.copyOf(bytesRead) else buffer
                val etag = uploadPart(key, uploadId, partNumber, partData).getOrElse { e ->
                    throw IOException("Failed to upload part $partNumber: ${e.message}", e)
                }

                parts.add(UploadedPart(partNumber, etag))
                bytesUploaded += bytesRead
                progressCallback?.invoke(bytesUploaded, fileSize)

                partNumber++
                bytesRead = fillBuffer(input, buffer)
            }
        }
        return parts
    }

    /**
     * Initiate multipart upload
     *
     * @return the upload ID, or the failure with the HTTP status and S3 error code
     */
    private fun initiateMultipartUpload(key: String, contentType: String): Result<String> {
        return try {
            val date = Date()
            val headers = mutableMapOf(
                "Content-Type" to contentType,
                CONTENT_SHA256 to sha256Hex(ByteArray(0))
            )

            val url = "${objectUrl(key)}?uploads"
            val request = createSignedRequest("POST", url, headers, ByteArray(0), date)

            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            val responseCode = connection.responseCode
            val result = if (responseCode in 200..299) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                // Parse upload ID from XML response
                val uploadId = "<UploadId>([^<]+)</UploadId>".toRegex().find(response)?.groupValues?.get(1)
                if (uploadId != null) Result.success(uploadId) else Result.failure(IOException("No UploadId in the answer"))
            } else {
                Result.failure(IOException(httpFailure(connection, responseCode)))
            }
            connection.disconnect()

            result.onFailure { Timber.tag(TAG).e("Failed to initiate multipart upload: ${it.message}") }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to initiate multipart upload")
            Result.failure(e)
        }
    }

    /**
     * Upload a part
     *
     * @return the part's ETag, or the failure with the HTTP status and S3 error code
     */
    private fun uploadPart(key: String, uploadId: String, partNumber: Int, data: ByteArray): Result<String> {
        return try {
            val contentSha256 = sha256Hex(data)
            val date = Date()
            val headers = mutableMapOf(
                "Content-Length" to data.size.toString(),
                CONTENT_SHA256 to contentSha256
            )

            val url = "${objectUrl(key)}?partNumber=$partNumber&uploadId=${uriEncode(uploadId, keepSlash = false)}"
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
            val result = when {
                responseCode !in 200..299 -> Result.failure(IOException(httpFailure(connection, responseCode)))
                etag == null -> Result.failure(IOException("No ETag in the answer"))
                else -> Result.success(etag)
            }
            connection.disconnect()

            result.onFailure { Timber.tag(TAG).e("Failed to upload part $partNumber: ${it.message}") }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to upload part $partNumber")
            Result.failure(e)
        }
    }

    /**
     * Complete multipart upload
     *
     * @return the result, or the failure with the HTTP status and S3 error code
     */
    private fun completeMultipartUpload(
        key: String,
        uploadId: String,
        parts: List<UploadedPart>
    ): Result<S3UploadResult> {
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
                CONTENT_SHA256 to contentSha256
            )

            val url = "${objectUrl(key)}?uploadId=${uriEncode(uploadId, keepSlash = false)}"
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
            val result = if (responseCode in 200..299) {
                // S3 can answer 200 and still report an error in the body
                val answer = connection.inputStream.bufferedReader().use { it.readText() }
                if (answer.contains("<Error>")) {
                    Result.failure(IOException(s3ErrorText(responseCode, answer)))
                } else {
                    Result.success(S3UploadResult(true, etag, versionId))
                }
            } else {
                Result.failure(IOException(httpFailure(connection, responseCode)))
            }
            connection.disconnect()

            result.onFailure { Timber.tag(TAG).e("Failed to complete multipart upload: ${it.message}") }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to complete multipart upload")
            Result.failure(e)
        }
    }

    /**
     * Abort multipart upload
     */
    private fun abortMultipartUpload(key: String, uploadId: String) {
        try {
            val date = Date()
            val headers = mutableMapOf<String, String>()

            val url = "${objectUrl(key)}?uploadId=${uriEncode(uploadId, keepSlash = false)}"
            val request = createSignedRequest("DELETE", url, headers, ByteArray(0), date)

            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                Timber.tag(TAG).w("Abort of multipart upload $uploadId failed: ${httpFailure(connection, responseCode)}")
            }
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
            val headers = mutableMapOf<String, String>()

            val url = objectUrl(key)
            val request = createSignedRequest("DELETE", url, headers, ByteArray(0), date)

            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }

            val responseCode = connection.responseCode
            val deleted = responseCode in 200..299 || responseCode == 404
            val failure = if (deleted) null else httpFailure(connection, responseCode)
            connection.disconnect()

            if (failure == null) {
                Result.success(true)
            } else {
                Result.failure(Exception("Delete failed: $failure"))
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

    /**
     * Path-style object URL with the key URI-encoded once ('/' kept), so the URL path is
     * also the SigV4 canonical URI
     */
    private fun objectUrl(key: String): String =
        "${credentials.endpointUrl}/${credentials.bucket}/${uriEncode(key, keepSlash = true)}"

    /**
     * The failure text of an error answer, with the S3 error code read from its body
     */
    private fun httpFailure(connection: HttpURLConnection, responseCode: Int): String {
        val body = try {
            connection.errorStream?.bufferedReader()?.use { it.readText() }
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "Could not read the S3 error answer")
            null
        }
        return s3ErrorText(responseCode, body)
    }

    // ==================== AWS Signature V4 ====================

    /**
     * A request signed with AWS Signature V4: the URL to call, every header to send
     * (Authorization included) and the canonical request that was signed.
     */
    internal data class SignedRequest(
        val url: String,
        val headers: Map<String, String>,
        val canonicalRequest: String
    )

    /**
     * Sign a request whose payload hash is x-amz-content-sha256 when the caller set it,
     * otherwise the SHA-256 of [payload]. The header is always sent and signed: S3 requires
     * it on every SigV4 request.
     */
    internal fun createSignedRequest(
        method: String,
        url: String,
        headers: MutableMap<String, String>,
        payload: ByteArray,
        date: Date
    ): SignedRequest {
        val payloadHash = headers.getOrPut(CONTENT_SHA256) { sha256Hex(payload) }
        return signRequest(method, url, headers, payloadHash, date)
    }

    /**
     * AWS Signature V4 for S3. Adds Host, x-amz-date and Authorization to [headers] and
     * signs every header in it.
     *
     * @param url the URL as it will be sent: path and query already URI-encoded (see
     *   [uriEncode]); the canonical URI is its path as written, not encoded again
     * @param payloadHash hex SHA-256 of the body (the x-amz-content-sha256 value)
     */
    internal fun signRequest(
        method: String,
        url: String,
        headers: MutableMap<String, String>,
        payloadHash: String,
        date: Date
    ): SignedRequest {
        val instant = date.toInstant()
        val amzDate = AMZ_DATE_FORMAT.format(instant)
        val datestamp = DATESTAMP_FORMAT.format(instant)

        // java.net.URL keeps the path and query exactly as written (still encoded), and
        // accepts host names java.net.URI rejects (such as "my_minio")
        val parsedUrl = URL(url)
        val host = parsedUrl.host + (if (parsedUrl.port > 0) ":${parsedUrl.port}" else "")

        headers["Host"] = host
        headers["x-amz-date"] = amzDate

        // Lower-case names, values trimmed with inner runs of spaces folded to one
        val canonicalHeaderMap = headers.entries
            .associate { (name, value) -> name.lowercase(Locale.US) to value.trim().replace(WHITESPACE, " ") }
            .toSortedMap()
        val signedHeadersStr = canonicalHeaderMap.keys.joinToString(";")
        val canonicalHeaders = canonicalHeaderMap.entries.joinToString("") { (name, value) -> "$name:$value\n" }

        val canonicalRequest = listOf(
            method,
            parsedUrl.path.ifEmpty { "/" },
            canonicalQueryString(parsedUrl.query),
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

        return SignedRequest(url, headers.toMap(), canonicalRequest)
    }

    private fun calculateSignature(datestamp: String, stringToSign: String): String {
        val kDate = hmacSha256("AWS4${credentials.secretAccessKey}".toByteArray(Charsets.UTF_8), datestamp)
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

    private fun sha256HexFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        FileInputStream(file).use { input ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
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
