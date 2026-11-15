package com.lensdaemon.kiosk

import com.lensdaemon.web.handlers.KioskApiHandler
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the kiosk rules that need no phone: presets keep the exit PIN
 * and the on/off state, which exit PINs can be typed at the phone, screen mode
 * names, and how PUT /api/kiosk/config reads its body.
 */
class KioskRulesTest {

    private val withPin = KioskConfig(
        enabled = true,
        screen = ScreenConfig(mode = ScreenMode.DIM, dimBrightness = 40),
        security = SecurityConfig(exitPin = "2468", lockNavigationBar = false, blockScreenshots = true)
    )

    @Test
    fun `presets keep the exit PIN and kiosk mode on`() {
        for (preset in listOf(KioskConfig.APPLIANCE, KioskConfig.INTERACTIVE)) {
            val applied = withPin.withPreset(preset)
            assertEquals("2468", applied.security.exitPin)
            assertEquals(true, applied.enabled)
            // Everything else comes from the preset
            assertEquals(preset.copy(enabled = true, security = preset.security.copy(exitPin = "2468")), applied)
        }
    }

    @Test
    fun `presets keep the exit PIN and kiosk mode off`() {
        val off = withPin.copy(enabled = false)
        for (preset in listOf(KioskConfig.APPLIANCE, KioskConfig.INTERACTIVE)) {
            val applied = off.withPreset(preset)
            assertEquals("2468", applied.security.exitPin)
            assertEquals(false, applied.enabled)
            assertEquals(preset.autoStart, applied.autoStart)
            assertEquals(preset.screen, applied.screen)
            assertEquals(preset.networkRecovery, applied.networkRecovery)
            assertEquals(preset.crashRecovery, applied.crashRecovery)
            assertEquals(preset.security.lockNavigationBar, applied.security.lockNavigationBar)
            assertEquals(preset.security.blockScreenshots, applied.security.blockScreenshots)
        }
    }

    @Test
    fun `without a PIN a preset leaves none`() {
        assertEquals("", KioskConfig.DEFAULT.withPreset(KioskConfig.APPLIANCE).security.exitPin)
    }

    @Test
    fun `exit PINs are empty or 4 to 12 digits`() {
        assertNull(exitPinError(""))
        assertNull(exitPinError("1234"))
        assertNull(exitPinError("123456789012"))
        assertNotNull(exitPinError("123"))
        assertNotNull(exitPinError("a123"))
        assertNotNull(exitPinError("1234567890123"))
        assertNotNull(exitPinError("12 34"))
        assertNotNull(exitPinError("null"))
        assertEquals("exitPin must be 4-12 digits, or empty to remove it", exitPinError("12ab"))
    }

    @Test
    fun `screen modes parse in any letter case`() {
        assertEquals(ScreenMode.DIM, parseScreenMode("dim"))
        assertEquals(ScreenMode.BLACK, parseScreenMode("Black"))
        assertEquals(ScreenMode.OFF, parseScreenMode(" OFF "))
        assertEquals(ScreenMode.PREVIEW, parseScreenMode("PREVIEW"))
        assertNull(parseScreenMode("x"))
        assertNull(parseScreenMode(""))
    }

    // ==================== PUT /api/kiosk/config ====================

    private fun put(body: String, current: KioskConfig): KioskConfig =
        KioskApiHandler().kioskConfigFrom(JSONObject(body), current)

    @Test
    fun `negative times an earlier version saved are kept as 0 when the request leaves them out`() {
        val saved = KioskConfig(
            autoStart = AutoStartConfig(delaySeconds = -5),
            security = SecurityConfig(exitGestureTimeoutMs = -1),
            crashRecovery = CrashRecoveryConfig(restartDelayMs = -3, restartWindowMinutes = -1)
        )
        val updated = put(
            """{"security": {"exitPin": "2468"}, "autoStart": {"startRecording": true},
                "crashRecovery": {"autoRestart": false}}""",
            saved
        )
        assertEquals("2468", updated.security.exitPin)
        assertEquals(0L, updated.security.exitGestureTimeoutMs)
        assertTrue(updated.autoStart.startRecording)
        assertEquals(0, updated.autoStart.delaySeconds)
        assertFalse(updated.crashRecovery.autoRestart)
        assertEquals(0L, updated.crashRecovery.restartDelayMs)
        assertEquals(0, updated.crashRecovery.restartWindowMinutes)
    }

    @Test
    fun `a negative time in the request is refused, naming it`() {
        val delay = assertThrows(IllegalArgumentException::class.java) {
            put("""{"autoStart": {"delaySeconds": -1}}""", KioskConfig.DEFAULT)
        }
        assertEquals("autoStart.delaySeconds must not be negative", delay.message)
        val gesture = assertThrows(IllegalArgumentException::class.java) {
            put("""{"security": {"exitGestureTimeoutMs": -1}}""", KioskConfig.DEFAULT)
        }
        assertEquals("security.exitGestureTimeoutMs must not be negative", gesture.message)
        assertEquals(30, put("""{"autoStart": {"delaySeconds": 30}}""", KioskConfig.DEFAULT).autoStart.delaySeconds)
    }

    @Test
    fun `only an exit PIN in the request is checked`() {
        // What an earlier version saved for {"exitPin": null}
        val saved = KioskConfig(security = SecurityConfig(exitPin = "null"))
        val kept = put("""{"security": {"allowedExitGesture": false}}""", saved)
        assertEquals("null", kept.security.exitPin)
        assertFalse(kept.security.allowedExitGesture)

        val error = assertThrows(IllegalArgumentException::class.java) {
            put("""{"security": {"exitPin": "12ab"}}""", saved)
        }
        assertEquals("exitPin must be 4-12 digits, or empty to remove it", error.message)
        assertEquals("", put("""{"security": {"exitPin": null}}""", saved).security.exitPin)
        assertEquals("", put("""{"security": {"exitPin": ""}}""", saved).security.exitPin)
        assertEquals("2468", put("""{"security": {"exitPin": 2468}}""", saved).security.exitPin)
    }

    @Test
    fun `an unknown screen mode is refused`() {
        assertEquals(ScreenMode.DIM, put("""{"screen": {"mode": "dim"}}""", KioskConfig.DEFAULT).screen.mode)
        val error = assertThrows(IllegalArgumentException::class.java) {
            put("""{"screen": {"mode": "bright"}}""", KioskConfig.DEFAULT)
        }
        assertEquals("screen.mode must be PREVIEW, OFF, DIM or BLACK", error.message)
    }
}
