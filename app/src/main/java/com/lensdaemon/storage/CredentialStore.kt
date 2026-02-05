package com.lensdaemon.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Storage backend types
 */
enum class StorageBackend {
    SMB,
    S3,
    BACKBLAZE_B2,
    MINIO,
    CLOUDFLARE_R2
}

/**
 * S3-compatible storage credentials
 */
data class S3Credentials(
    val endpoint: String,
    val region: String,
    val bucket: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val pathPrefix: String = "",
    val useHttps: Boolean = true,
    val backend: StorageBackend = StorageBackend.S3
) {
    /** Full endpoint URL */
    val endpointUrl: String
        get() = "${if (useHttps) "https" else "http"}://$endpoint"

    /** Check if credentials are valid (non-empty) */
    fun isValid(): Boolean {
        return endpoint.isNotEmpty() &&
                bucket.isNotEmpty() &&
                accessKeyId.isNotEmpty() &&
                secretAccessKey.isNotEmpty()
    }

    companion object {
        /** Create AWS S3 credentials */
        fun forAwsS3(
            region: String,
            bucket: String,
            accessKeyId: String,
            secretAccessKey: String,
            pathPrefix: String = ""
        ) = S3Credentials(
            endpoint = "s3.$region.amazonaws.com",
            region = region,
            bucket = bucket,
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            pathPrefix = pathPrefix,
            backend = StorageBackend.S3
        )

        /** Create Backblaze B2 credentials */
        fun forBackblazeB2(
            region: String,
            bucket: String,
            keyId: String,
            applicationKey: String,
            pathPrefix: String = ""
        ) = S3Credentials(
            endpoint = "s3.$region.backblazeb2.com",
            region = region,
            bucket = bucket,
            accessKeyId = keyId,
            secretAccessKey = applicationKey,
            pathPrefix = pathPrefix,
            backend = StorageBackend.BACKBLAZE_B2
        )

        /** Create MinIO credentials */
        fun forMinIO(
            endpoint: String,
            bucket: String,
            accessKey: String,
            secretKey: String,
            useHttps: Boolean = true,
            pathPrefix: String = ""
        ) = S3Credentials(
            endpoint = endpoint,
            region = "us-east-1",
            bucket = bucket,
            accessKeyId = accessKey,
            secretAccessKey = secretKey,
            useHttps = useHttps,
            pathPrefix = pathPrefix,
            backend = StorageBackend.MINIO
        )

        /** Create Cloudflare R2 credentials */
        fun forCloudflareR2(
            accountId: String,
            bucket: String,
            accessKeyId: String,
            secretAccessKey: String,
            pathPrefix: String = ""
        ) = S3Credentials(
            endpoint = "$accountId.r2.cloudflarestorage.com",
            region = "auto",
            bucket = bucket,
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            pathPrefix = pathPrefix,
            backend = StorageBackend.CLOUDFLARE_R2
        )
    }
}

/**
 * SMB/CIFS share credentials
 */
data class SmbCredentials(
    val server: String,
    val share: String,
    val username: String,
    val password: String,
    val domain: String = "",
    val port: Int = 445,
    val pathPrefix: String = ""
) {
    /** Full SMB URL */
    val smbUrl: String
        get() = "smb://$server:$port/$share"

    /** Check if credentials are valid (non-empty) */
    fun isValid(): Boolean {
        return server.isNotEmpty() &&
                share.isNotEmpty() &&
                username.isNotEmpty() &&
                password.isNotEmpty()
    }
}

/**
 * Encrypted credential store using Android Keystore
 *
 * Features:
 * - AES-256-GCM encryption
 * - Android Keystore for key storage
 * - Secure storage for S3 and SMB credentials
 * - Credential validation
 */
class CredentialStore(
    private val context: Context
) {
    companion object {
        private const val TAG = "CredentialStore"
        private const val KEYSTORE_ALIAS = "LensDaemonCredentialKey"
        private const val PREFS_NAME = "lensdaemon_credentials"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        // Preference keys
        private const val KEY_S3_ENDPOINT = "s3_endpoint"
        private const val KEY_S3_REGION = "s3_region"
        private const val KEY_S3_BUCKET = "s3_bucket"
        private const val KEY_S3_ACCESS_KEY = "s3_access_key"
        private const val KEY_S3_SECRET_KEY = "s3_secret_key"
        private const val KEY_S3_PATH_PREFIX = "s3_path_prefix"
        private const val KEY_S3_USE_HTTPS = "s3_use_https"
        private const val KEY_S3_BACKEND = "s3_backend"

        private const val KEY_SMB_SERVER = "smb_server"
        private const val KEY_SMB_SHARE = "smb_share"
        private const val KEY_SMB_USERNAME = "smb_username"
        private const val KEY_SMB_PASSWORD = "smb_password"
        private const val KEY_SMB_DOMAIN = "smb_domain"
        private const val KEY_SMB_PORT = "smb_port"
        private const val KEY_SMB_PATH_PREFIX = "smb_path_prefix"

        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }
    }

    init {
        ensureKeyExists()
    }

    /**
     * Store S3-compatible credentials
     */
    fun storeS3Credentials(credentials: S3Credentials) {
        prefs.edit().apply {
            putString(KEY_S3_ENDPOINT, encrypt(credentials.endpoint))
            putString(KEY_S3_REGION, encrypt(credentials.region))
            putString(KEY_S3_BUCKET, encrypt(credentials.bucket))
            putString(KEY_S3_ACCESS_KEY, encrypt(credentials.accessKeyId))
            putString(KEY_S3_SECRET_KEY, encrypt(credentials.secretAccessKey))
            putString(KEY_S3_PATH_PREFIX, credentials.pathPrefix)
            putBoolean(KEY_S3_USE_HTTPS, credentials.useHttps)
            putString(KEY_S3_BACKEND, credentials.backend.name)
            apply()
        }
        Timber.tag(TAG).d("S3 credentials stored")
    }

    /**
     * Retrieve S3-compatible credentials
     */
    fun getS3Credentials(): S3Credentials? {
        val endpoint = decrypt(prefs.getString(KEY_S3_ENDPOINT, null) ?: return null)
        val region = decrypt(prefs.getString(KEY_S3_REGION, null) ?: return null)
        val bucket = decrypt(prefs.getString(KEY_S3_BUCKET, null) ?: return null)
        val accessKey = decrypt(prefs.getString(KEY_S3_ACCESS_KEY, null) ?: return null)
        val secretKey = decrypt(prefs.getString(KEY_S3_SECRET_KEY, null) ?: return null)

        return S3Credentials(
            endpoint = endpoint,
            region = region,
            bucket = bucket,
            accessKeyId = accessKey,
            secretAccessKey = secretKey,
            pathPrefix = prefs.getString(KEY_S3_PATH_PREFIX, "") ?: "",
            useHttps = prefs.getBoolean(KEY_S3_USE_HTTPS, true),
            backend = try {
                StorageBackend.valueOf(prefs.getString(KEY_S3_BACKEND, "S3") ?: "S3")
            } catch (e: Exception) {
                StorageBackend.S3
            }
        )
    }

    /**
     * Check if S3 credentials are stored
     */
    fun hasS3Credentials(): Boolean {
        return prefs.contains(KEY_S3_ENDPOINT) && prefs.contains(KEY_S3_ACCESS_KEY)
    }

    /**
     * Delete S3 credentials
     */
    fun deleteS3Credentials() {
        prefs.edit().apply {
            remove(KEY_S3_ENDPOINT)
            remove(KEY_S3_REGION)
            remove(KEY_S3_BUCKET)
            remove(KEY_S3_ACCESS_KEY)
            remove(KEY_S3_SECRET_KEY)
            remove(KEY_S3_PATH_PREFIX)
            remove(KEY_S3_USE_HTTPS)
            remove(KEY_S3_BACKEND)
            apply()
        }
        Timber.tag(TAG).d("S3 credentials deleted")
    }

    /**
     * Store SMB credentials
     */
    fun storeSmbCredentials(credentials: SmbCredentials) {
        prefs.edit().apply {
            putString(KEY_SMB_SERVER, encrypt(credentials.server))
            putString(KEY_SMB_SHARE, encrypt(credentials.share))
            putString(KEY_SMB_USERNAME, encrypt(credentials.username))
            putString(KEY_SMB_PASSWORD, encrypt(credentials.password))
            putString(KEY_SMB_DOMAIN, credentials.domain)
            putInt(KEY_SMB_PORT, credentials.port)
            putString(KEY_SMB_PATH_PREFIX, credentials.pathPrefix)
            apply()
        }
        Timber.tag(TAG).d("SMB credentials stored")
    }

    /**
     * Retrieve SMB credentials
     */
    fun getSmbCredentials(): SmbCredentials? {
        val server = decrypt(prefs.getString(KEY_SMB_SERVER, null) ?: return null)
        val share = decrypt(prefs.getString(KEY_SMB_SHARE, null) ?: return null)
        val username = decrypt(prefs.getString(KEY_SMB_USERNAME, null) ?: return null)
        val password = decrypt(prefs.getString(KEY_SMB_PASSWORD, null) ?: return null)

        return SmbCredentials(
            server = server,
            share = share,
            username = username,
            password = password,
            domain = prefs.getString(KEY_SMB_DOMAIN, "") ?: "",
            port = prefs.getInt(KEY_SMB_PORT, 445),
            pathPrefix = prefs.getString(KEY_SMB_PATH_PREFIX, "") ?: ""
        )
    }

    /**
     * Check if SMB credentials are stored
     */
    fun hasSmbCredentials(): Boolean {
        return prefs.contains(KEY_SMB_SERVER) && prefs.contains(KEY_SMB_USERNAME)
    }

    /**
     * Delete SMB credentials
     */
    fun deleteSmbCredentials() {
        prefs.edit().apply {
            remove(KEY_SMB_SERVER)
            remove(KEY_SMB_SHARE)
            remove(KEY_SMB_USERNAME)
            remove(KEY_SMB_PASSWORD)
            remove(KEY_SMB_DOMAIN)
            remove(KEY_SMB_PORT)
            remove(KEY_SMB_PATH_PREFIX)
            apply()
        }
        Timber.tag(TAG).d("SMB credentials deleted")
    }

    /**
     * Delete all stored credentials
     */
    fun deleteAllCredentials() {
        deleteS3Credentials()
        deleteSmbCredentials()
    }

    /**
     * Ensure encryption key exists in keystore
     */
    private fun ensureKeyExists() {
        if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
            generateKey()
        }
    }

    /**
     * Generate a new encryption key
     */
    private fun generateKey() {
        try {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )

            val keySpec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()

            keyGenerator.init(keySpec)
            keyGenerator.generateKey()

            Timber.tag(TAG).d("Encryption key generated")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to generate encryption key")
        }
    }

    /**
     * Get the encryption key from keystore
     */
    private fun getKey(): SecretKey? {
        return try {
            keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get encryption key")
            null
        }
    }

    /**
     * Encrypt a string value
     */
    private fun encrypt(plainText: String): String {
        val key = getKey()
            ?: throw SecurityException("Encryption key unavailable - cannot store credentials securely")

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)

            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            // Combine IV and encrypted data
            val combined = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)

            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Encryption failed")
            throw SecurityException("Encryption failed - cannot store credentials securely", e)
        }
    }

    /**
     * Decrypt a string value
     */
    private fun decrypt(encryptedText: String): String {
        val key = getKey()
            ?: throw SecurityException("Encryption key unavailable - cannot decrypt credentials")

        return try {
            val combined = Base64.decode(encryptedText, Base64.NO_WRAP)

            // Extract IV and encrypted data
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val encrypted = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Decryption failed")
            throw SecurityException("Decryption failed - stored credentials may be corrupted", e)
        }
    }
}
