package com.lensdaemon.web.handlers

import com.lensdaemon.kiosk.AutoStartConfig
import com.lensdaemon.kiosk.CrashRecoveryConfig
import com.lensdaemon.kiosk.KioskConfig
import com.lensdaemon.kiosk.KioskManager
import com.lensdaemon.kiosk.NetworkRecoveryConfig
import com.lensdaemon.kiosk.ScreenConfig
import com.lensdaemon.kiosk.SecurityConfig
import com.lensdaemon.kiosk.exitPinError
import com.lensdaemon.kiosk.parseScreenMode
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

    private companion object {
        const val MAX_BRIGHTNESS = 255
        const val DEVICE_OWNER_MESSAGE = "Kiosk mode on: LensDaemon is pinned to the screen and starts on boot. " +
            "Android starts it only after the phone's first unlock, so restarting unattended needs a phone " +
            "without a PIN, pattern or password lock"
        const val NO_DEVICE_OWNER_MESSAGE = "Kiosk mode is on, but without Device Owner LensDaemon can't lock the " +
            "screen, and Android won't open its screen or (Android 11+) its camera by itself after a reboot: " +
            "adb shell dpm set-device-owner com.lensdaemon/.AdminReceiver"
    }

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

    /**
     * State and a summary of the settings. restartCount and lastRestartTime
     * count the automatic restarts after a crash, kept across restarts;
     * isScreenLocked is always false (kiosk mode never locks the screen).
     */
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
        return ok(json)
    }

    /**
     * Turn kiosk mode on: LensDaemon starts on boot and, when it is the device
     * owner, pins itself to the screen at once. Without Device Owner the screen
     * cannot be locked, and Android won't let LensDaemon open its screen (or on
     * Android 11+ its camera) by itself at boot, which the answer says
     * (lockTask: false). Either way the boot start comes after the phone's
     * first unlock, so unattended restarts need no secure screen lock.
     */
    private fun enableKiosk(): NanoHTTPD.Response {
        val kiosk = kioskManager ?: return ApiHandlerUtils.serviceUnavailable("Kiosk service")
        kiosk.requestKioskMode(true)
        val deviceOwner = kiosk.isDeviceOwner()
        val json = JSONObject().apply {
            put("success", true)
            put("lockTask", deviceOwner)
            put("message", if (deviceOwner) DEVICE_OWNER_MESSAGE else NO_DEVICE_OWNER_MESSAGE)
        }
        return ok(json)
    }

    /** Turn kiosk mode off: LensDaemon unpins itself and no longer starts on boot. */
    private fun disableKiosk(): NanoHTTPD.Response {
        val kiosk = kioskManager ?: return ApiHandlerUtils.serviceUnavailable("Kiosk service")
        kiosk.requestKioskMode(false)
        return success("Kiosk mode off: LensDaemon is unpinned and no longer starts on boot")
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
        return ok(json)
    }

    /**
     * Change some settings: every section and field is optional, and what is
     * left out stays as it is. Answers 400 for an exit PIN that is not 4-12
     * digits (null or "" removes it), an unknown screen mode or a negative time.
     */
    private fun updateKioskConfig(body: JSONObject?): NanoHTTPD.Response {
        val kiosk = kioskManager ?: return ApiHandlerUtils.serviceUnavailable("Kiosk service")
        body ?: return ApiHandlerUtils.bodyRequired()
        val current = kiosk.getConfig()
        val newConfig = try {
            KioskConfig(
                enabled = body.optBoolean("enabled", current.enabled),
                autoStart = body.optJSONObject("autoStart")?.let { parseAutoStart(it, current.autoStart) }
                    ?: current.autoStart,
                screen = body.optJSONObject("screen")?.let { parseScreen(it, current.screen) } ?: current.screen,
                security = body.optJSONObject("security")?.let { parseSecurity(it, current.security) }
                    ?: current.security,
                networkRecovery = body.optJSONObject("networkRecovery")?.let { parseNetwork(it, current.networkRecovery) }
                    ?: current.networkRecovery,
                crashRecovery = body.optJSONObject("crashRecovery")?.let { parseCrash(it, current.crashRecovery) }
                    ?: current.crashRecovery
            )
        } catch (e: IllegalArgumentException) {
            return badRequest(e.message ?: "Invalid kiosk configuration")
        }
        kiosk.updateConfig(newConfig)
        return success("Kiosk configuration updated")
    }

    private fun parseAutoStart(json: JSONObject, current: AutoStartConfig): AutoStartConfig =
        AutoStartConfig(
            enabled = json.optBoolean("enabled", current.enabled),
            delaySeconds = notNegative(json, "delaySeconds", current.delaySeconds, "autoStart"),
            startStreaming = json.optBoolean("startStreaming", current.startStreaming),
            startRtsp = json.optBoolean("startRtsp", current.startRtsp),
            startRecording = json.optBoolean("startRecording", current.startRecording)
        )

    private fun parseScreen(json: JSONObject, current: ScreenConfig): ScreenConfig {
        val mode = if (json.has("mode")) {
            requireNotNull(parseScreenMode(json.optString("mode"))) { "screen.mode must be PREVIEW, OFF, DIM or BLACK" }
        } else {
            current.mode
        }
        return ScreenConfig(
            mode = mode,
            dimBrightness = json.optInt("dimBrightness", current.dimBrightness).coerceIn(0, MAX_BRIGHTNESS),
            autoOffTimeoutSec = notNegative(json, "autoOffTimeoutSec", current.autoOffTimeoutSec, "screen"),
            wakeOnMotion = json.optBoolean("wakeOnMotion", current.wakeOnMotion),
            keepScreenOn = json.optBoolean("keepScreenOn", current.keepScreenOn)
        )
    }

    /** The exit PIN: absent keeps it, null or "" removes it, anything else must be 4-12 digits. */
    private fun parseSecurity(json: JSONObject, current: SecurityConfig): SecurityConfig {
        val pin = when {
            !json.has("exitPin") -> current.exitPin
            json.isNull("exitPin") -> ""
            else -> json.opt("exitPin").toString().trim()
        }
        val pinError = exitPinError(pin)
        require(pinError == null) { pinError.orEmpty() }
        val gestureMs = json.optLong("exitGestureTimeoutMs", current.exitGestureTimeoutMs)
        require(gestureMs >= 0) { "security.exitGestureTimeoutMs must not be negative" }
        return SecurityConfig(
            exitPin = pin,
            allowedExitGesture = json.optBoolean("allowedExitGesture", current.allowedExitGesture),
            exitGestureTimeoutMs = gestureMs,
            lockStatusBar = json.optBoolean("lockStatusBar", current.lockStatusBar),
            lockNavigationBar = json.optBoolean("lockNavigationBar", current.lockNavigationBar),
            lockNotifications = json.optBoolean("lockNotifications", current.lockNotifications),
            blockScreenshots = json.optBoolean("blockScreenshots", current.blockScreenshots)
        )
    }

    private fun parseNetwork(json: JSONObject, current: NetworkRecoveryConfig): NetworkRecoveryConfig =
        NetworkRecoveryConfig(
            autoReconnect = json.optBoolean("autoReconnect", current.autoReconnect),
            reconnectIntervalSec = notNegative(json, "reconnectIntervalSec", current.reconnectIntervalSec, "networkRecovery"),
            maxReconnectAttempts = json.optInt("maxReconnectAttempts", current.maxReconnectAttempts),
            restartStreamOnReconnect = json.optBoolean("restartStreamOnReconnect", current.restartStreamOnReconnect)
        )

    private fun parseCrash(json: JSONObject, current: CrashRecoveryConfig): CrashRecoveryConfig {
        val delayMs = json.optLong("restartDelayMs", current.restartDelayMs)
        require(delayMs >= 0) { "crashRecovery.restartDelayMs must not be negative" }
        return CrashRecoveryConfig(
            autoRestart = json.optBoolean("autoRestart", current.autoRestart),
            restartDelayMs = delayMs,
            maxRestartAttempts = json.optInt("maxRestartAttempts", current.maxRestartAttempts),
            restartWindowMinutes = notNegative(json, "restartWindowMinutes", current.restartWindowMinutes, "crashRecovery")
        )
    }

    /** The whole number [key] of [json] ([current] when absent); a negative one is refused, naming [section].[key]. */
    private fun notNegative(json: JSONObject, key: String, current: Int, section: String): Int {
        val value = json.optInt(key, current)
        require(value >= 0) { "$section.$key must not be negative" }
        return value
    }

    /** The appliance preset; the exit PIN and kiosk on/off are kept, and the event log notes it. */
    private fun applyAppliancePreset(): NanoHTTPD.Response {
        val kiosk = kioskManager ?: return ApiHandlerUtils.serviceUnavailable("Kiosk service")
        kiosk.applyAppliancePreset()
        return success("Appliance preset applied (24/7 unattended operation)")
    }

    /** The interactive preset; the exit PIN and kiosk on/off are kept, and the event log notes it. */
    private fun applyInteractivePreset(): NanoHTTPD.Response {
        val kiosk = kioskManager ?: return ApiHandlerUtils.serviceUnavailable("Kiosk service")
        kiosk.applyInteractivePreset()
        return success("Interactive preset applied (kiosk with preview)")
    }

    /** The in-memory event log, emptied when the app restarts. */
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
        return ok(json)
    }

    private fun ok(json: JSONObject): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())

    private fun success(message: String): NanoHTTPD.Response =
        ok(JSONObject().put("success", true).put("message", message))

    private fun badRequest(error: String): NanoHTTPD.Response = NanoHTTPD.newFixedLengthResponse(
        Status.BAD_REQUEST, WebServer.MIME_JSON, JSONObject().put("success", false).put("error", error).toString()
    )
}
