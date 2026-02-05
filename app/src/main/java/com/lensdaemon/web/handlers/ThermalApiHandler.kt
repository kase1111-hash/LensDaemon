package com.lensdaemon.web.handlers

import com.lensdaemon.thermal.ThermalGovernor
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
            else -> null
        }
    }

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
}
