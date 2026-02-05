package com.lensdaemon.web.handlers

import com.lensdaemon.kiosk.AutoStartConfig
import com.lensdaemon.kiosk.CrashRecoveryConfig
import com.lensdaemon.kiosk.KioskConfig
import com.lensdaemon.kiosk.KioskManager
import com.lensdaemon.kiosk.NetworkRecoveryConfig
import com.lensdaemon.kiosk.ScreenConfig
import com.lensdaemon.kiosk.ScreenMode
import com.lensdaemon.kiosk.SecurityConfig
import com.lensdaemon.web.WebServer
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject

/**
 * Handles all /api/kiosk/ routes.
 *
 * Extracted from ApiRoutes to separate kiosk API concerns into a dedicated handler.
 */
class KioskApiHandler(
    var kioskManager: KioskManager? = null
) {

    /**
     * Handle a kiosk API request.
     *
     * @param uri The request URI
     * @param method The HTTP method
     * @param body The parsed JSON body (if any)
     * @return A NanoHTTPD.Response if the URI was handled, or null for unhandled URIs
     */
    fun handleRequest(uri: String, method: NanoHTTPD.Method, body: JSONObject?): NanoHTTPD.Response? {
        return when {
            uri == "/api/kiosk/status" && method == NanoHTTPD.Method.GET -> getKioskStatus()
            uri == "/api/kiosk/enable" && method == NanoHTTPD.Method.POST -> enableKiosk()
            uri == "/api/kiosk/disable" && method == NanoHTTPD.Method.POST -> disableKiosk()
            uri == "/api/kiosk/config" && method == NanoHTTPD.Method.GET -> getKioskConfig()
            uri == "/api/kiosk/config" && method == NanoHTTPD.Method.PUT -> updateKioskConfig(body)
            uri == "/api/kiosk/preset/appliance" && method == NanoHTTPD.Method.POST -> applyAppliancePreset()
            uri == "/api/kiosk/preset/interactive" && method == NanoHTTPD.Method.POST -> applyInteractivePreset()
            uri == "/api/kiosk/events" && method == NanoHTTPD.Method.GET -> getKioskEvents()
            else -> null
        }
    }

    private fun getKioskStatus(): NanoHTTPD.Response {
        val kiosk = kioskManager ?: return ApiHandlerUtils.serviceUnavailable("Kiosk service")
        val status = kiosk.getStatus()
        val json = JSONObject().apply {
            put("state", status.state.name)
            put("isDeviceOwner", status.isDeviceOwner)
            put("isLockTaskActive", status.isLockTaskActive)
            put("isScreenLocked", status.isScreenLocked)
            put("uptimeMs", status.uptimeMs)
            put("lastRestartTime", status.lastRestartTime)
            put("restartCount", status.restartCount)
            put("kioskEnabled", status.config.enabled)
            put("autoStartEnabled", status.config.autoStart.enabled)
            put("screenMode", status.config.screen.mode.name)
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    private fun enableKiosk(): NanoHTTPD.Response {
        val kiosk = kioskManager ?: return ApiHandlerUtils.serviceUnavailable("Kiosk service")
        if (!kiosk.isDeviceOwner()) {
            return NanoHTTPD.newFixedLengthResponse(
                Status.BAD_REQUEST, WebServer.MIME_JSON,
                """{"success": false, "error": "Device Owner not set. Run: adb shell dpm set-device-owner com.lensdaemon/.AdminReceiver"}"""
            )
        }
        val json = JSONObject().apply {
            put("success", true)
            put("message", "Kiosk mode will be enabled. Restart app or use the dashboard to activate.")
            put("note", "Full kiosk activation requires activity context")
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    private fun disableKiosk(): NanoHTTPD.Response {
        val kiosk = kioskManager ?: return ApiHandlerUtils.serviceUnavailable("Kiosk service")
        val newConfig = kiosk.getConfig().copy(enabled = false)
        kiosk.updateConfig(newConfig)
        return NanoHTTPD.newFixedLengthResponse(
            Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "Kiosk mode disabled in config. Restart app to fully exit."}"""
        )
    }

    private fun getKioskConfig(): NanoHTTPD.Response {
        val kiosk = kioskManager ?: return ApiHandlerUtils.serviceUnavailable("Kiosk service")
        val config = kiosk.getConfig()
        val json = JSONObject().apply {
            put("enabled", config.enabled)
            put("autoStart", JSONObject().apply {
                put("enabled", config.autoStart.enabled)
                put("delaySeconds", config.autoStart.delaySeconds)
                put("startStreaming", config.autoStart.startStreaming)
                put("startRtsp", config.autoStart.startRtsp)
                put("startRecording", config.autoStart.startRecording)
            })
            put("screen", JSONObject().apply {
                put("mode", config.screen.mode.name)
                put("dimBrightness", config.screen.dimBrightness)
                put("autoOffTimeoutSec", config.screen.autoOffTimeoutSec)
                put("wakeOnMotion", config.screen.wakeOnMotion)
                put("keepScreenOn", config.screen.keepScreenOn)
            })
            put("security", JSONObject().apply {
                put("exitPinSet", config.security.exitPin.isNotEmpty())
                put("allowedExitGesture", config.security.allowedExitGesture)
                put("exitGestureTimeoutMs", config.security.exitGestureTimeoutMs)
                put("lockStatusBar", config.security.lockStatusBar)
                put("lockNavigationBar", config.security.lockNavigationBar)
                put("lockNotifications", config.security.lockNotifications)
                put("blockScreenshots", config.security.blockScreenshots)
            })
            put("networkRecovery", JSONObject().apply {
                put("autoReconnect", config.networkRecovery.autoReconnect)
                put("reconnectIntervalSec", config.networkRecovery.reconnectIntervalSec)
                put("maxReconnectAttempts", config.networkRecovery.maxReconnectAttempts)
                put("restartStreamOnReconnect", config.networkRecovery.restartStreamOnReconnect)
            })
            put("crashRecovery", JSONObject().apply {
                put("autoRestart", config.crashRecovery.autoRestart)
                put("restartDelayMs", config.crashRecovery.restartDelayMs)
                put("maxRestartAttempts", config.crashRecovery.maxRestartAttempts)
                put("restartWindowMinutes", config.crashRecovery.restartWindowMinutes)
            })
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    private fun updateKioskConfig(body: JSONObject?): NanoHTTPD.Response {
        val kiosk = kioskManager ?: return ApiHandlerUtils.serviceUnavailable("Kiosk service")
        body ?: return ApiHandlerUtils.bodyRequired()
        val currentConfig = kiosk.getConfig()

        val autoStartJson = body.optJSONObject("autoStart")
        val autoStart = if (autoStartJson != null) {
            AutoStartConfig(
                enabled = autoStartJson.optBoolean("enabled", currentConfig.autoStart.enabled),
                delaySeconds = autoStartJson.optInt("delaySeconds", currentConfig.autoStart.delaySeconds),
                startStreaming = autoStartJson.optBoolean("startStreaming", currentConfig.autoStart.startStreaming),
                startRtsp = autoStartJson.optBoolean("startRtsp", currentConfig.autoStart.startRtsp),
                startRecording = autoStartJson.optBoolean("startRecording", currentConfig.autoStart.startRecording)
            )
        } else currentConfig.autoStart

        val screenJson = body.optJSONObject("screen")
        val screen = if (screenJson != null) {
            val modeStr = screenJson.optString("mode", currentConfig.screen.mode.name)
            val mode = try { ScreenMode.valueOf(modeStr) } catch (e: Exception) { currentConfig.screen.mode }
            ScreenConfig(
                mode = mode,
                dimBrightness = screenJson.optInt("dimBrightness", currentConfig.screen.dimBrightness),
                autoOffTimeoutSec = screenJson.optInt("autoOffTimeoutSec", currentConfig.screen.autoOffTimeoutSec),
                wakeOnMotion = screenJson.optBoolean("wakeOnMotion", currentConfig.screen.wakeOnMotion),
                keepScreenOn = screenJson.optBoolean("keepScreenOn", currentConfig.screen.keepScreenOn)
            )
        } else currentConfig.screen

        val securityJson = body.optJSONObject("security")
        val security = if (securityJson != null) {
            SecurityConfig(
                exitPin = securityJson.optString("exitPin", currentConfig.security.exitPin),
                allowedExitGesture = securityJson.optBoolean("allowedExitGesture", currentConfig.security.allowedExitGesture),
                exitGestureTimeoutMs = securityJson.optLong("exitGestureTimeoutMs", currentConfig.security.exitGestureTimeoutMs),
                lockStatusBar = securityJson.optBoolean("lockStatusBar", currentConfig.security.lockStatusBar),
                lockNavigationBar = securityJson.optBoolean("lockNavigationBar", currentConfig.security.lockNavigationBar),
                lockNotifications = securityJson.optBoolean("lockNotifications", currentConfig.security.lockNotifications),
                blockScreenshots = securityJson.optBoolean("blockScreenshots", currentConfig.security.blockScreenshots)
            )
        } else currentConfig.security

        val networkJson = body.optJSONObject("networkRecovery")
        val networkRecovery = if (networkJson != null) {
            NetworkRecoveryConfig(
                autoReconnect = networkJson.optBoolean("autoReconnect", currentConfig.networkRecovery.autoReconnect),
                reconnectIntervalSec = networkJson.optInt("reconnectIntervalSec", currentConfig.networkRecovery.reconnectIntervalSec),
                maxReconnectAttempts = networkJson.optInt("maxReconnectAttempts", currentConfig.networkRecovery.maxReconnectAttempts),
                restartStreamOnReconnect = networkJson.optBoolean("restartStreamOnReconnect", currentConfig.networkRecovery.restartStreamOnReconnect)
            )
        } else currentConfig.networkRecovery

        val crashJson = body.optJSONObject("crashRecovery")
        val crashRecovery = if (crashJson != null) {
            CrashRecoveryConfig(
                autoRestart = crashJson.optBoolean("autoRestart", currentConfig.crashRecovery.autoRestart),
                restartDelayMs = crashJson.optLong("restartDelayMs", currentConfig.crashRecovery.restartDelayMs),
                maxRestartAttempts = crashJson.optInt("maxRestartAttempts", currentConfig.crashRecovery.maxRestartAttempts),
                restartWindowMinutes = crashJson.optInt("restartWindowMinutes", currentConfig.crashRecovery.restartWindowMinutes)
            )
        } else currentConfig.crashRecovery

        val newConfig = KioskConfig(
            enabled = body.optBoolean("enabled", currentConfig.enabled),
            autoStart = autoStart,
            screen = screen,
            security = security,
            networkRecovery = networkRecovery,
            crashRecovery = crashRecovery
        )
        kiosk.updateConfig(newConfig)
        return NanoHTTPD.newFixedLengthResponse(
            Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "Kiosk configuration updated"}"""
        )
    }

    private fun applyAppliancePreset(): NanoHTTPD.Response {
        val kiosk = kioskManager ?: return ApiHandlerUtils.serviceUnavailable("Kiosk service")
        kiosk.applyAppliancePreset()
        return NanoHTTPD.newFixedLengthResponse(
            Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "Appliance preset applied (24/7 unattended operation)"}"""
        )
    }

    private fun applyInteractivePreset(): NanoHTTPD.Response {
        val kiosk = kioskManager ?: return ApiHandlerUtils.serviceUnavailable("Kiosk service")
        kiosk.applyInteractivePreset()
        return NanoHTTPD.newFixedLengthResponse(
            Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "Interactive preset applied (kiosk with preview)"}"""
        )
    }

    private fun getKioskEvents(): NanoHTTPD.Response {
        val kiosk = kioskManager ?: return ApiHandlerUtils.serviceUnavailable("Kiosk service")
        val events = kiosk.getEventLog()
        val json = JSONObject().apply {
            put("count", events.size)
            put("events", JSONArray().apply {
                events.forEach { event ->
                    put(JSONObject().apply {
                        put("timestamp", event.timestamp)
                        put("type", event.type.name)
                        put("message", event.message)
                        put("details", JSONObject(event.details))
                    })
                }
            })
        }
        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }
}
