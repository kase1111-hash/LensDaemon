package com.lensdaemon.storage

import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB upload result
 */
data class SmbUploadResult(
    val success: Boolean,
    val bytesWritten: Long = 0,
    val error: String? = null
)

/**
 * SMB connection state
 */
enum class SmbConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTHENTICATED,
    ERROR
}

/**
 * SMB/CIFS client for network share uploads.
 *
 * Uses the smbj library for full SMB2/3 protocol support with proper
 * NTLM/NTLMv2 authentication, replacing the previous hand-rolled
 * implementation that had broken NTLM (zeroed DES response, MD5-for-MD4).
 */
class SmbClient(
    private val credentials: SmbCredentials
) {
    companion object {
        private const val TAG = "SmbClient"
        private const val CONNECT_TIMEOUT_SEC = 30L
        private const val READ_TIMEOUT_SEC = 60L
        private const val BUFFER_SIZE = 65536 // 64KB
    }

    private val smbConfig = SmbConfig.builder()
        .withTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .withSoTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    private val client = SMBClient(smbConfig)

    @Volatile
    var state: SmbConnectionState = SmbConnectionState.DISCONNECTED
        private set

    private var connection: com.hierynomus.smbj.connection.Connection? = null
    private var session: com.hierynomus.smbj.session.Session? = null
    private var share: DiskShare? = null

    /**
     * Test connection to SMB share
     */
    suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            connect()
            disconnect()
            Result.success(true)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "SMB connection test failed")
            Result.failure(e)
        }
    }

    /**
     * Connect to SMB server, authenticate, and open the share.
     */
    private fun connect() {
        state = SmbConnectionState.CONNECTING

        connection = client.connect(credentials.server, credentials.port)
        state = SmbConnectionState.CONNECTED

        val authContext = AuthenticationContext(
            credentials.username,
            credentials.password.toCharArray(),
            credentials.domain
        )
        session = connection!!.authenticate(authContext)

        share = session!!.connectShare(credentials.share) as DiskShare
        state = SmbConnectionState.AUTHENTICATED

        Timber.tag(TAG).d("Connected to ${credentials.server}/${credentials.share}")
    }

    /**
     * Disconnect from SMB server
     */
    fun disconnect() {
        try {
            share?.close()
            session?.close()
            connection?.close()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error during graceful disconnect")
        } finally {
            share = null
            session = null
            connection = null
            state = SmbConnectionState.DISCONNECTED
        }
    }

    /**
     * Upload a file to SMB share
     */
    suspend fun uploadFile(
        localFile: File,
        remotePath: String,
        progressCallback: ProgressCallback? = null
    ): Result<SmbUploadResult> = withContext(Dispatchers.IO) {
        if (!localFile.exists()) {
            return@withContext Result.failure(Exception("File not found: ${localFile.absolutePath}"))
        }

        try {
            if (state != SmbConnectionState.AUTHENTICATED) {
                connect()
            }

            val diskShare = share ?: return@withContext Result.failure(Exception("Not connected"))
            val fullPath = buildPath(remotePath)
            val fileSize = localFile.length()

            Timber.tag(TAG).d("Uploading ${localFile.name} ($fileSize bytes) to $fullPath")

            val remoteFile = diskShare.openFile(
                fullPath,
                EnumSet.of(
                    com.hierynomus.mssmb2.SMB2ShareAccess.FILE_SHARE_WRITE
                ),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            )

            var bytesWritten = 0L
            remoteFile.use { rf ->
                val outputStream = rf.outputStream
                FileInputStream(localFile).use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        outputStream.write(buffer, 0, read)
                        bytesWritten += read
                        progressCallback?.invoke(bytesWritten, fileSize)
                    }
                    outputStream.flush()
                }
            }

            Timber.tag(TAG).i("Upload completed: $fullPath ($bytesWritten bytes)")
            Result.success(SmbUploadResult(true, bytesWritten))

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "SMB upload failed")
            Result.failure(e)
        }
    }

    /**
     * Create directory on SMB share
     */
    suspend fun createDirectory(remotePath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (state != SmbConnectionState.AUTHENTICATED) {
                connect()
            }

            val diskShare = share ?: return@withContext Result.failure(Exception("Not connected"))
            val fullPath = buildPath(remotePath)
            diskShare.mkdir(fullPath)

            Timber.tag(TAG).d("Directory created: $fullPath")
            Result.success(true)
        } catch (e: Exception) {
            if (e.message?.contains("exists", ignoreCase = true) == true) {
                Result.success(true)
            } else {
                Result.failure(e)
            }
        }
    }

    /**
     * Delete a file on SMB share
     */
    suspend fun deleteFile(remotePath: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (state != SmbConnectionState.AUTHENTICATED) {
                connect()
            }

            val diskShare = share ?: return@withContext Result.failure(Exception("Not connected"))
            val fullPath = buildPath(remotePath)
            diskShare.rm(fullPath)

            Timber.tag(TAG).d("File deleted: $fullPath")
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * List files in a remote directory
     */
    suspend fun listFiles(remotePath: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            if (state != SmbConnectionState.AUTHENTICATED) {
                connect()
            }

            val diskShare = share ?: return@withContext Result.failure(Exception("Not connected"))
            val fullPath = buildPath(remotePath)
            val entries = diskShare.list(fullPath)
            val names = entries.map { it.fileName }.filter { it != "." && it != ".." }

            Result.success(names)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Build full path with prefix
     */
    private fun buildPath(remotePath: String): String {
        val cleanPath = remotePath.replace('/', '\\').trimStart('\\')
        return if (credentials.pathPrefix.isNotEmpty()) {
            "${credentials.pathPrefix.trimEnd('\\')}\\$cleanPath"
        } else {
            cleanPath
        }
    }
}
