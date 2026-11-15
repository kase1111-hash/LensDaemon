package com.lensdaemon.kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the kiosk rules that need no phone: presets keep the exit PIN
 * and the on/off state, which exit PINs can be typed at the phone, and screen
 * mode names.
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
}
