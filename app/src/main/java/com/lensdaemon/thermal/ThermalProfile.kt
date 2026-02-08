package com.lensdaemon.thermal

import android.content.Context
import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import timber.log.Timber

/**
 * Device-specific thermal profile
 *
 * Different SoCs throttle at different temperatures. A Snapdragon 888 hits thermal
 * throttle at 42°C, while a Snapdragon 855 is fine at 50°C. This class maps device
 * models to tuned thermal thresholds.
 */
data class ThermalProfile(
    val deviceModel: String,
    val socModel: String = "",
    val displayName: String = deviceModel,
    val cpuWarnC: Float = 50f,
    val cpuCriticalC: Float = 55f,
    val cpuEmergencyC: Float = 60f,
    val batteryWarnC: Float = 42f,
    val batteryCriticalC: Float = 45f,
    val batteryEmergencyC: Float = 48f,
    val sustainableBitrateKbps: Int = 4000,
    val sustainableResolution: String = "1080p",
    val sustainableFps: Int = 30,
    val notes: String = ""
) {
    companion object {
        /** Conservative default for unknown devices */
        val DEFAULT = ThermalProfile(
            deviceModel = "generic",
            displayName = "Generic (Conservative)",
            cpuWarnC = 50f,
            cpuCriticalC = 55f,
            cpuEmergencyC = 60f,
            batteryWarnC = 42f,
            batteryCriticalC = 45f,
            batteryEmergencyC = 48f,
            sustainableBitrateKbps = 4000,
            sustainableResolution = "1080p",
            sustainableFps = 30
        )
    }

    /**
     * Generate a CpuThermalConfig from this profile
     */
    fun toCpuThermalConfig(hysteresisC: Float = 3.0f): CpuThermalConfig {
        return CpuThermalConfig(
            normalMaxC = cpuWarnC - 5f,
            warningThresholdC = cpuWarnC,
            criticalThresholdC = cpuCriticalC,
            emergencyThresholdC = cpuEmergencyC,
            hysteresisC = hysteresisC
        )
    }

    /**
     * Generate a BatteryThermalConfig from this profile
     */
    fun toBatteryThermalConfig(hysteresisC: Float = 2.0f): BatteryThermalConfig {
        return BatteryThermalConfig(
            normalMaxC = batteryWarnC - 4f,
            warningThresholdC = batteryWarnC,
            criticalThresholdC = batteryCriticalC,
            emergencyThresholdC = batteryEmergencyC,
            hysteresisC = hysteresisC
        )
    }

    /**
     * Convert this profile to a ThermalConfig
     */
    fun toThermalConfig(base: ThermalConfig = ThermalConfig.DEFAULT): ThermalConfig {
        return base.copy(
            cpuConfig = toCpuThermalConfig(base.cpuConfig.hysteresisC),
            batteryConfig = toBatteryThermalConfig(base.batteryConfig.hysteresisC)
        )
    }
}

/**
 * Manages device thermal profiles with auto-detection and user overrides.
 *
 * Loads a built-in profile database from assets/thermal_profiles.json,
 * auto-detects the current device, and allows user overrides stored in
 * SharedPreferences.
 */
class ThermalProfileManager(private val context: Context) {

    companion object {
        private const val TAG = "ThermalProfileManager"
        private const val PROFILES_ASSET = "thermal_profiles.json"
        private const val PREFS_NAME = "thermal_profile_prefs"
        private const val PREF_OVERRIDE_JSON = "override_profile_json"
        private const val PREF_OVERRIDE_ENABLED = "override_enabled"
    }

    private val gson = Gson()
    private val builtInProfiles = mutableListOf<ThermalProfile>()
    private var detectedProfile: ThermalProfile = ThermalProfile.DEFAULT
    private var overrideProfile: ThermalProfile? = null

    init {
        loadBuiltInProfiles()
        detectDeviceProfile()
        loadOverride()
    }

    /**
     * Get the active thermal profile (override > detected > default)
     */
    fun getActiveProfile(): ThermalProfile {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_OVERRIDE_ENABLED, false)) {
            overrideProfile?.let { return it }
        }
        return detectedProfile
    }

    /**
     * Get the auto-detected profile for this device
     */
    fun getDetectedProfile(): ThermalProfile = detectedProfile

    /**
     * Get all built-in profiles
     */
    fun getBuiltInProfiles(): List<ThermalProfile> = builtInProfiles.toList()

    /**
     * Get current device identification
     */
    fun getDeviceIdentification(): DeviceIdentification {
        return DeviceIdentification(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            board = Build.BOARD,
            socModel = getSocModel(),
            sdkVersion = Build.VERSION.SDK_INT
        )
    }

    /**
     * Set a user override profile
     */
    fun setOverrideProfile(profile: ThermalProfile) {
        overrideProfile = profile
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(PREF_OVERRIDE_JSON, gson.toJson(profile))
            .putBoolean(PREF_OVERRIDE_ENABLED, true)
            .apply()
        Timber.tag(TAG).i("Override profile set: ${profile.displayName}")
    }

    /**
     * Clear user override, revert to auto-detected profile
     */
    fun clearOverride() {
        overrideProfile = null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(PREF_OVERRIDE_JSON)
            .putBoolean(PREF_OVERRIDE_ENABLED, false)
            .apply()
        Timber.tag(TAG).i("Override cleared, using detected profile")
    }

    /**
     * Check if an override is active
     */
    fun isOverrideActive(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_OVERRIDE_ENABLED, false) && overrideProfile != null
    }

    /**
     * Build a ThermalConfig from the active profile
     */
    fun buildThermalConfig(base: ThermalConfig = ThermalConfig.DEFAULT): ThermalConfig {
        return getActiveProfile().toThermalConfig(base)
    }

    // ==================== Private ====================

    private fun loadBuiltInProfiles() {
        try {
            val json = context.assets.open(PROFILES_ASSET).bufferedReader().use { it.readText() }
            val listType = object : TypeToken<List<ThermalProfile>>() {}.type
            val profiles: List<ThermalProfile> = gson.fromJson(json, listType)
            builtInProfiles.addAll(profiles)
            Timber.tag(TAG).i("Loaded ${builtInProfiles.size} built-in thermal profiles")
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to load thermal profiles from assets: ${e.message}")
        }
    }

    private fun detectDeviceProfile() {
        val model = Build.MODEL.lowercase()
        val device = Build.DEVICE.lowercase()
        val socModel = getSocModel().lowercase()

        // Try matching by model first, then device name, then SoC
        val match = builtInProfiles.firstOrNull { profile ->
            profile.deviceModel.lowercase() == model
        } ?: builtInProfiles.firstOrNull { profile ->
            profile.deviceModel.lowercase() == device
        } ?: builtInProfiles.firstOrNull { profile ->
            profile.socModel.isNotEmpty() && socModel.contains(profile.socModel.lowercase())
        }

        if (match != null) {
            detectedProfile = match
            Timber.tag(TAG).i("Detected thermal profile: ${match.displayName} (matched from ${match.deviceModel})")
        } else {
            detectedProfile = ThermalProfile.DEFAULT
            Timber.tag(TAG).i("No matching profile found for model=$model device=$device soc=$socModel, using defaults")
        }
    }

    private fun loadOverride() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(PREF_OVERRIDE_JSON, null)
            if (json != null && prefs.getBoolean(PREF_OVERRIDE_ENABLED, false)) {
                overrideProfile = gson.fromJson(json, ThermalProfile::class.java)
                Timber.tag(TAG).i("Loaded override profile: ${overrideProfile?.displayName}")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to load override profile: ${e.message}")
        }
    }

    @Suppress("PrivateApi")
    private fun getSocModel(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL
            } else {
                // Fallback for older Android versions
                val clazz = Class.forName("android.os.SystemProperties")
                val method = clazz.getMethod("get", String::class.java, String::class.java)
                method.invoke(null, "ro.hardware.chipname", "") as? String ?: ""
            }
        } catch (e: Exception) {
            ""
        }
    }
}

/**
 * Device identification info
 */
data class DeviceIdentification(
    val manufacturer: String,
    val model: String,
    val device: String,
    val board: String,
    val socModel: String,
    val sdkVersion: Int
)
