package com.lensdaemon.storage

import com.hierynomus.mserref.NtStatus
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * The path on the share for [remotePath] under the folder [prefix]. Both '/' and '\'
 * separate folders; empty components (leading, trailing or doubled separators) are
 * dropped and the rest joined with '\'. ("lensdaemon/", "a.mp4") gives "lensdaemon\a.mp4",
 * ("", "/a.mp4") gives "a.mp4" and ("lensdaemon", "") the folder itself.
 */
internal fun smbPath(prefix: String, remotePath: String): String =
    (smbPathComponents(prefix) + smbPathComponents(remotePath)).joinToString("\\")

private fun smbPathComponents(path: String): List<String> =
    path.replace('/', '\\').split('\\').filter { it.isNotEmpty() }

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
 *
 * Uploads create missing folders under the path prefix. A failed upload drops the
 * connection and is tried once more on a fresh one, so a session the server dropped
 * (restart, Wi-Fi change, idle timeout) doesn't break every later upload.
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
     * Test connection to SMB share: connect, then check the upload folder (the path
     * prefix) and create it when missing, so a wrong path or a read-only share fails here
     * rather than on the first upload. The failure's message says why.
     */
    suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            connect()
            val diskShare = share ?: throw IOException("Not connected")
            ensureFolders(diskShare, smbPath(credentials.pathPrefix, ""))
            Result.success(true)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "SMB connection test failed")
            Result.failure(e)
        } finally {
            disconnect()
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
     * Upload a file to SMB share. Missing folders are created first. When the attempt
     * fails, the connection is dropped and the upload tried once more on a new one.
     */
    suspend fun uploadFile(
        localFile: File,
        remotePath: String,
        progressCallback: ProgressCallback? = null
    ): Result<SmbUploadResult> = withContext(Dispatchers.IO) {
        if (!localFile.exists()) {
            return@withContext Result.failure(Exception("File not found: ${localFile.absolutePath}"))
        }

        val fullPath = smbPath(credentials.pathPrefix, remotePath)
        try {
            Result.success(uploadOnce(localFile, fullPath, progressCallback))
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "SMB upload failed; retrying on a new connection")
            disconnect()
            try {
                Result.success(uploadOnce(localFile, fullPath, progressCallback))
            } catch (retryError: Exception) {
                Timber.tag(TAG).e(retryError, "SMB upload failed")
                disconnect()
                Result.failure(retryError)
            }
        }
    }

    /**
     * One upload attempt: connect when needed, create the missing folders, write the file.
     */
    private fun uploadOnce(localFile: File, fullPath: String, progressCallback: ProgressCallback?): SmbUploadResult {
        if (state != SmbConnectionState.AUTHENTICATED) {
            connect()
        }

        val diskShare = share ?: throw IOException("Not connected")
        val fileSize = localFile.length()
        ensureFolders(diskShare, fullPath.substringBeforeLast('\\', ""))

        Timber.tag(TAG).d("Uploading ${localFile.name} ($fileSize bytes) to $fullPath")

        val remoteFile = diskShare.openFile(
            fullPath,
            EnumSet.of(AccessMask.GENERIC_WRITE),
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
        return SmbUploadResult(true, bytesWritten)
    }

    /**
     * Create each missing folder of [dir] (a path from [smbPath]), parent first.
     *
     * @throws IOException when a folder can't be checked or created (for example
     *   STATUS_ACCESS_DENIED), naming the folder
     */
    private fun ensureFolders(diskShare: DiskShare, dir: String) {
        var path = ""
        for (component in dir.split('\\').filter { it.isNotEmpty() }) {
            path = if (path.isEmpty()) component else "$path\\$component"
            try {
                if (!diskShare.folderExists(path)) {
                    diskShare.mkdir(path)
                }
            } catch (e: SMBApiException) {
                // A collision means another upload created it in the meantime
                if (e.status != NtStatus.STATUS_OBJECT_NAME_COLLISION) {
                    throw IOException("Cannot create folder '$path' on the share: ${e.message}", e)
                }
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
            val fullPath = smbPath(credentials.pathPrefix, remotePath)
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
            val fullPath = smbPath(credentials.pathPrefix, remotePath)
            val entries = diskShare.list(fullPath)
            val names = entries.map { it.fileName }.filter { it != "." && it != ".." }

            Result.success(names)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
