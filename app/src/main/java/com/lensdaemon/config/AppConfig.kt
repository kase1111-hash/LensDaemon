package com.lensdaemon.config

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Application configuration data classes.
 * Serialized to/from JSON for REST API and persistent storage.
 */

data class AppConfig(
    @SerializedName("stream")
    val stream: StreamConfig = StreamConfig(),

    @SerializedName("storage")
    val storage: StorageConfig = StorageConfig(),

    @SerializedName("thermal")
    val thermal: ThermalConfig = ThermalConfig(),

    @SerializedName("display")
    val display: DisplayConfig = DisplayConfig(),

    @SerializedName("security")
    val security: SecurityConfig = SecurityConfig()
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromJson(json: String): AppConfig = Gson().fromJson(json, AppConfig::class.java)
    }
}

data class StreamConfig(
    @SerializedName("resolution")
    val resolution: String = "1920x1080",

    @SerializedName("framerate")
    val framerate: Int = 30,

    @SerializedName("bitrate")
    val bitrate: Int = 6_000_000,  // 6 Mbps

    @SerializedName("codec")
    val codec: String = "h264",  // h264 or h265

    @SerializedName("keyframe_interval")
    val keyframeInterval: Int = 2,  // seconds

    @SerializedName("autostart")
    val autostart: Boolean = false,

    @SerializedName("rtsp_enabled")
    val rtspEnabled: Boolean = true,

    @SerializedName("rtsp_port")
    val rtspPort: Int = 8554,

    @SerializedName("srt_enabled")
    val srtEnabled: Boolean = false,

    @SerializedName("srt_port")
    val srtPort: Int = 9000
)

data class StorageConfig(
    @SerializedName("local_enabled")
    val localEnabled: Boolean = true,

    @SerializedName("segment_minutes")
    val segmentMinutes: Int = 5,

    @SerializedName("max_local_size_mb")
    val maxLocalSizeMb: Int = 10_000,  // 10 GB

    @SerializedName("network_type")
    val networkType: String? = null,  // "smb" or "s3"

    @SerializedName("smb")
    val smb: SmbConfig? = null,

    @SerializedName("s3")
    val s3: S3Config? = null
)

data class SmbConfig(
    @SerializedName("host")
    val host: String = "",

    @SerializedName("share")
    val share: String = "",

    @SerializedName("username")
    val username: String = "",

    @SerializedName("password")
    val password: String = "",  // Should be encrypted in storage

    @SerializedName("path")
    val path: String = ""
)

data class S3Config(
    @SerializedName("endpoint")
    val endpoint: String = "s3.amazonaws.com",

    @SerializedName("bucket")
    val bucket: String = "",

    @SerializedName("prefix")
    val prefix: String = "",

    @SerializedName("access_key")
    val accessKey: String = "",

    @SerializedName("secret_key")
    val secretKey: String = "",  // Should be encrypted in storage

    @SerializedName("region")
    val region: String = "us-east-1"
)

data class ThermalConfig(
    @SerializedName("cpu_throttle_threshold")
    val cpuThrottleThreshold: Int = 45,  // °C

    @SerializedName("cpu_reduce_resolution_threshold")
    val cpuReduceResolutionThreshold: Int = 50,

    @SerializedName("cpu_reduce_framerate_threshold")
    val cpuReduceFramerateThreshold: Int = 55,

    @SerializedName("cpu_pause_threshold")
    val cpuPauseThreshold: Int = 60,

    @SerializedName("battery_charge_limit")
    val batteryChargeLimit: Int = 50,  // Percent

    @SerializedName("battery_warning_threshold")
    val batteryWarningThreshold: Int = 42,  // °C

    @SerializedName("battery_critical_threshold")
    val batteryCriticalThreshold: Int = 45
)

data class DisplayConfig(
    @SerializedName("screen_mode")
    val screenMode: String = "preview",  // "off", "dim", "preview"

    @SerializedName("brightness")
    val brightness: Int = 50,  // Percent

    @SerializedName("osd_enabled")
    val osdEnabled: Boolean = true,

    @SerializedName("show_thermal")
    val showThermal: Boolean = true
)

data class SecurityConfig(
    @SerializedName("pin_enabled")
    val pinEnabled: Boolean = false,

    @SerializedName("pin")
    val pin: String = "",  // Should be hashed

    @SerializedName("allowed_ips")
    val allowedIps: List<String> = emptyList(),  // Empty = allow all local

    @SerializedName("kiosk_enabled")
    val kioskEnabled: Boolean = false
)
