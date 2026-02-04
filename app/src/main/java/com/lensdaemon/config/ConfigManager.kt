package com.lensdaemon.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import timber.log.Timber

/**
 * Manages application configuration persistence.
 * Stores config as JSON in SharedPreferences.
 */
class ConfigManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "lensdaemon_config"
        private const val KEY_CONFIG = "app_config"

        @Volatile
        private var instance: ConfigManager? = null

        fun getInstance(context: Context): ConfigManager {
            return instance ?: synchronized(this) {
                instance ?: ConfigManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var _config: AppConfig = loadConfig()
    val config: AppConfig get() = _config

    private fun loadConfig(): AppConfig {
        return try {
            val json = prefs.getString(KEY_CONFIG, null)
            if (json != null) {
                AppConfig.fromJson(json)
            } else {
                Timber.i("No saved config, using defaults")
                AppConfig()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load config, using defaults")
            AppConfig()
        }
    }

    fun saveConfig(config: AppConfig) {
        _config = config
        prefs.edit {
            putString(KEY_CONFIG, config.toJson())
        }
        Timber.i("Config saved")
    }

    fun updateConfig(update: (AppConfig) -> AppConfig) {
        saveConfig(update(_config))
    }

    // Convenience accessors
    val streamConfig: StreamConfig get() = _config.stream
    val storageConfig: StorageConfig get() = _config.storage
    val thermalConfig: ThermalConfig get() = _config.thermal
    val displayConfig: DisplayConfig get() = _config.display
    val securityConfig: SecurityConfig get() = _config.security

    fun resetToDefaults() {
        saveConfig(AppConfig())
        Timber.i("Config reset to defaults")
    }
}
