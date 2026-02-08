package com.lensdaemon.web.handlers

import com.lensdaemon.thermal.ThermalGovernor
import com.lensdaemon.thermal.ThermalProfile
import com.lensdaemon.web.WebServer
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject

/**
 * Handles all /api/thermal/ routes.
 *
 * Extracted from ApiRoutes to separate thermal API concerns into a dedicated handler.
 */
class ThermalApiHandler(
    var thermalGovernor: ThermalGovernor? = null
) {

    /**
     * Handle a thermal API request.
     *
     * @param uri The request URI
     * @param method The HTTP method
     * @param body The parsed JSON body (if any)
     * @return A NanoHTTPD.Response if the URI was handled, or null for unhandled URIs
     */
    fun handleRequest(uri: String, method: NanoHTTPD.Method, body: JSONObject?): NanoHTTPD.Response? {
        return when {
            uri == "/api/thermal/status" && method == NanoHTTPD.Method.GET -> getThermalStatus()
            uri == "/api/thermal/history" && method == NanoHTTPD.Method.GET -> getThermalHistory()
            uri == "/api/thermal/stats" && method == NanoHTTPD.Method.GET -> getThermalStats()
            uri == "/api/thermal/events" && method == NanoHTTPD.Method.GET -> getThermalEvents()
            uri == "/api/thermal/battery" && method == NanoHTTPD.Method.GET -> getBatteryBypassStatus()
            uri == "/api/thermal/battery/disable" && method == NanoHTTPD.Method.POST -> disableBatteryCharging()
            uri == "/api/thermal/battery/enable" && method == NanoHTTPD.Method.POST -> enableBatteryCharging()
            uri == "/api/thermal/profile" && method == NanoHTTPD.Method.GET -> getActiveProfile()
            uri == "/api/thermal/profile" && method == NanoHTTPD.Method.PUT -> setProfileOverride(body)
            uri == "/api/thermal/profile" && method == NanoHTTPD.Method.DELETE -> clearProfileOverride()
            uri == "/api/thermal/profile/device" && method == NanoHTTPD.Method.GET -> getDeviceInfo()
            uri == "/api/thermal/profiles" && method == NanoHTTPD.Method.GET -> listProfiles()
            uri == "/api/thermal/stress-test" && method == NanoHTTPD.Method.POST -> startStressTest(body)
            uri == "/api/thermal/stress-test" && method == NanoHTTPD.Method.GET -> getStressTestStatus()
            uri == "/api/thermal/stress-test" && method == NanoHTTPD.Method.DELETE -> stopStressTest()
            else -> null
        }
    }

    // Stress test reference (set externally by WebServerService or similar)
    var stressTest: com.lensdaemon.thermal.ThermalStressTest? = null

    private fun getThermalStatus(): NanoHTTPD.Response {
        val thermal = thermalGovernor ?: return ApiHandlerUtils.serviceUnavailable("Thermal service")
        val status = thermal.status.value
        val json = JSONObject().apply {
            put("cpuTemperatureC", status.cpuTemperatureC)
            put("batteryTemperatureC", status.batteryTemperatureC)
            put("cpuLevel", status.cpuLevel.name)
            put("batteryLevel", status.batteryLevel.name)
            put("overallLevel", status.overallLevel.name)
            put("statusText", status.statusText)
            put("isThrottling", status.isThrottling)
            put("batteryPercent", status.batteryPercent)
            put("isCharging", status.isCharging)
            put("chargingDisabled", status.chargingDisabled)
            put("bitrateReductionPercent", status.bitrateReductionPercent)
            put("resolutionReduced", status.resolutionReduced)
            put("framerateReduced", status.framerateReduced)
            put("activeActions", JSONArray().apply {
                status.activeActions.forEach { put(it.name) }
            })
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    private fun getThermalHistory(): NanoHTTPD.Response {
        val thermal = thermalGovernor ?: return ApiHandlerUtils.serviceUnavailable("Thermal service")
        val history = thermal.getHistory()
        val entries = history.getGraphData(100)
        val json = JSONObject().apply {
            put("count", entries.size)
            put("data", JSONArray().apply {
                entries.forEach { entry ->
                    put(JSONObject().apply {
                        put("timestamp", entry.timestamp)
                        put("cpuTemp", entry.cpuTemperatureC)
                        put("batteryTemp", entry.batteryTemperatureC)
                        put("cpuLevel", entry.cpuLevel.name)
                        put("batteryLevel", entry.batteryLevel.name)
                    })
                }
            })
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    private fun getThermalStats(): NanoHTTPD.Response {
        val thermal = thermalGovernor ?: return ApiHandlerUtils.serviceUnavailable("Thermal service")
        val stats = thermal.getStats()
        val json = JSONObject().apply {
            put("periodStartMs", stats.periodStartMs)
            put("periodEndMs", stats.periodEndMs)
            put("cpu", JSONObject().apply {
                put("minTemp", stats.cpuTempMin)
                put("maxTemp", stats.cpuTempMax)
                put("avgTemp", stats.cpuTempAvg)
            })
            put("battery", JSONObject().apply {
                put("minTemp", stats.batteryTempMin)
                put("maxTemp", stats.batteryTempMax)
                put("avgTemp", stats.batteryTempAvg)
            })
            put("timeInWarningMs", stats.timeInWarningMs)
            put("timeInCriticalMs", stats.timeInCriticalMs)
            put("timeInEmergencyMs", stats.timeInEmergencyMs)
            put("throttleEventCount", stats.throttleEventCount)
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    private fun getThermalEvents(): NanoHTTPD.Response {
        val thermal = thermalGovernor ?: return ApiHandlerUtils.serviceUnavailable("Thermal service")
        val history = thermal.getHistory()
        val events = history.getEvents(24)
        val json = JSONObject().apply {
            put("count", events.size)
            put("events", JSONArray().apply {
                events.forEach { event ->
                    put(JSONObject().apply {
                        put("timestamp", event.timestamp)
                        put("source", event.source.name)
                        put("oldLevel", event.oldLevel.name)
                        put("newLevel", event.newLevel.name)
                        put("temperatureC", event.temperatureC)
                        put("actions", JSONArray().apply {
                            event.actionsTriggered.forEach { put(it.name) }
                        })
                    })
                }
            })
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    private fun getBatteryBypassStatus(): NanoHTTPD.Response {
        val thermal = thermalGovernor ?: return ApiHandlerUtils.serviceUnavailable("Thermal service")
        val status = thermal.status.value
        val json = JSONObject().apply {
            put("batteryPercent", status.batteryPercent)
            put("batteryTemperatureC", status.batteryTemperatureC)
            put("isCharging", status.isCharging)
            put("chargingDisabled", status.chargingDisabled)
            put("batteryLevel", status.batteryLevel.name)
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    private fun disableBatteryCharging(): NanoHTTPD.Response {
        val thermal = thermalGovernor ?: return ApiHandlerUtils.serviceUnavailable("Thermal service")
        return NanoHTTPD.newFixedLengthResponse(
            Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "Charging disabled (manual override)"}"""
        )
    }

    private fun enableBatteryCharging(): NanoHTTPD.Response {
        val thermal = thermalGovernor ?: return ApiHandlerUtils.serviceUnavailable("Thermal service")
        return NanoHTTPD.newFixedLengthResponse(
            Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "Charging enabled"}"""
        )
    }

    // ==================== Profile API ====================

    private fun getActiveProfile(): NanoHTTPD.Response {
        val thermal = thermalGovernor ?: return ApiHandlerUtils.serviceUnavailable("Thermal service")
        val pm = thermal.profileManager
        val profile = pm.getActiveProfile()
        val json = JSONObject().apply {
            put("profile", profileToJson(profile))
            put("source", if (pm.isOverrideActive()) "override" else "auto-detected")
            put("config", JSONObject().apply {
                val config = thermal.getConfig()
                put("cpuNormalMaxC", config.cpuConfig.normalMaxC)
                put("cpuWarningC", config.cpuConfig.warningThresholdC)
                put("cpuCriticalC", config.cpuConfig.criticalThresholdC)
                put("cpuEmergencyC", config.cpuConfig.emergencyThresholdC)
                put("batteryWarningC", config.batteryConfig.warningThresholdC)
                put("batteryCriticalC", config.batteryConfig.criticalThresholdC)
                put("batteryEmergencyC", config.batteryConfig.emergencyThresholdC)
            })
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    private fun setProfileOverride(body: JSONObject?): NanoHTTPD.Response {
        val thermal = thermalGovernor ?: return ApiHandlerUtils.serviceUnavailable("Thermal service")
        body ?: return ApiHandlerUtils.bodyRequired()

        val profile = ThermalProfile(
            deviceModel = body.optString("deviceModel", "custom"),
            displayName = body.optString("displayName", "Custom Override"),
            cpuWarnC = body.optDouble("cpuWarnC", 50.0).toFloat(),
            cpuCriticalC = body.optDouble("cpuCriticalC", 55.0).toFloat(),
            cpuEmergencyC = body.optDouble("cpuEmergencyC", 60.0).toFloat(),
            batteryWarnC = body.optDouble("batteryWarnC", 42.0).toFloat(),
            batteryCriticalC = body.optDouble("batteryCriticalC", 45.0).toFloat(),
            batteryEmergencyC = body.optDouble("batteryEmergencyC", 48.0).toFloat(),
            sustainableBitrateKbps = body.optInt("sustainableBitrateKbps", 4000),
            sustainableResolution = body.optString("sustainableResolution", "1080p"),
            sustainableFps = body.optInt("sustainableFps", 30),
            notes = body.optString("notes", "User-defined override")
        )

        // Validate thresholds are in ascending order
        if (profile.cpuWarnC >= profile.cpuCriticalC || profile.cpuCriticalC >= profile.cpuEmergencyC) {
            return ApiHandlerUtils.errorJson(
                Status.BAD_REQUEST,
                "CPU thresholds must be in ascending order: warn < critical < emergency"
            )
        }
        if (profile.batteryWarnC >= profile.batteryCriticalC || profile.batteryCriticalC >= profile.batteryEmergencyC) {
            return ApiHandlerUtils.errorJson(
                Status.BAD_REQUEST,
                "Battery thresholds must be in ascending order: warn < critical < emergency"
            )
        }

        thermal.profileManager.setOverrideProfile(profile)
        thermal.applyActiveProfile()

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "Profile override applied", "profile": "${profile.displayName}"}"""
        )
    }

    private fun clearProfileOverride(): NanoHTTPD.Response {
        val thermal = thermalGovernor ?: return ApiHandlerUtils.serviceUnavailable("Thermal service")
        thermal.profileManager.clearOverride()
        thermal.applyActiveProfile()

        val detected = thermal.profileManager.getDetectedProfile()
        return NanoHTTPD.newFixedLengthResponse(
            Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "Override cleared, reverted to auto-detected profile", "profile": "${detected.displayName}"}"""
        )
    }

    private fun getDeviceInfo(): NanoHTTPD.Response {
        val thermal = thermalGovernor ?: return ApiHandlerUtils.serviceUnavailable("Thermal service")
        val device = thermal.profileManager.getDeviceIdentification()
        val json = JSONObject().apply {
            put("manufacturer", device.manufacturer)
            put("model", device.model)
            put("device", device.device)
            put("board", device.board)
            put("socModel", device.socModel)
            put("sdkVersion", device.sdkVersion)
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    private fun listProfiles(): NanoHTTPD.Response {
        val thermal = thermalGovernor ?: return ApiHandlerUtils.serviceUnavailable("Thermal service")
        val profiles = thermal.profileManager.getBuiltInProfiles()
        val active = thermal.profileManager.getActiveProfile()
        val json = JSONObject().apply {
            put("count", profiles.size)
            put("activeProfile", active.deviceModel)
            put("profiles", JSONArray().apply {
                profiles.forEach { put(profileToJson(it)) }
            })
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    private fun profileToJson(profile: ThermalProfile): JSONObject {
        return JSONObject().apply {
            put("deviceModel", profile.deviceModel)
            put("socModel", profile.socModel)
            put("displayName", profile.displayName)
            put("cpuWarnC", profile.cpuWarnC)
            put("cpuCriticalC", profile.cpuCriticalC)
            put("cpuEmergencyC", profile.cpuEmergencyC)
            put("batteryWarnC", profile.batteryWarnC)
            put("batteryCriticalC", profile.batteryCriticalC)
            put("batteryEmergencyC", profile.batteryEmergencyC)
            put("sustainableBitrateKbps", profile.sustainableBitrateKbps)
            put("sustainableResolution", profile.sustainableResolution)
            put("sustainableFps", profile.sustainableFps)
            put("notes", profile.notes)
        }
    }

    // ==================== Stress Test API ====================

    private fun startStressTest(body: JSONObject?): NanoHTTPD.Response {
        val test = stressTest ?: return ApiHandlerUtils.serviceUnavailable("Stress test")
        if (test.isRunning()) {
            return ApiHandlerUtils.errorJson(Status.CONFLICT, "Stress test already running")
        }

        val durationSec = body?.optInt("durationSeconds", 300) ?: 300
        val clampedDuration = durationSec.coerceIn(60, 600)
        test.start(clampedDuration)

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "Stress test started", "durationSeconds": $clampedDuration}"""
        )
    }

    private fun getStressTestStatus(): NanoHTTPD.Response {
        val test = stressTest ?: return ApiHandlerUtils.serviceUnavailable("Stress test")
        val result = test.getResult()
        val json = JSONObject().apply {
            put("running", test.isRunning())
            put("elapsedSeconds", test.getElapsedSeconds())
            if (result != null) {
                put("result", JSONObject().apply {
                    put("durationSeconds", result.durationSeconds)
                    put("peakCpuTempC", result.peakCpuTempC)
                    put("peakBatteryTempC", result.peakBatteryTempC)
                    put("avgCpuTempC", result.avgCpuTempC)
                    put("avgBatteryTempC", result.avgBatteryTempC)
                    put("throttleEvents", result.throttleEvents)
                    put("temperatureCurve", JSONArray().apply {
                        result.temperatureCurve.forEach { point ->
                            put(JSONObject().apply {
                                put("elapsedSec", point.elapsedSec)
                                put("cpuTempC", point.cpuTempC)
                                put("batteryTempC", point.batteryTempC)
                            })
                        }
                    })
                    put("recommendedProfile", profileToJson(result.recommendedProfile))
                })
            }
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    private fun stopStressTest(): NanoHTTPD.Response {
        val test = stressTest ?: return ApiHandlerUtils.serviceUnavailable("Stress test")
        if (!test.isRunning()) {
            return ApiHandlerUtils.errorJson(Status.CONFLICT, "No stress test running")
        }
        test.stop()
        return NanoHTTPD.newFixedLengthResponse(
            Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "Stress test stopped"}"""
        )
    }
}
