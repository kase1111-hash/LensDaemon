package com.lensdaemon

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lensdaemon.kiosk.KioskConfig
import com.lensdaemon.kiosk.KioskManager
import com.lensdaemon.kiosk.KioskState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for kiosk mode functionality.
 * Tests Device Owner API interactions and kiosk configuration.
 *
 * Note: Full kiosk enable/disable requires Device Owner status, which
 * needs ADB setup (dpm set-device-owner). These tests verify the API
 * layer without requiring Device Owner.
 */
@RunWith(AndroidJUnit4::class)
class KioskModeSmokeTest {

    private lateinit var context: Context
    private lateinit var kioskManager: KioskManager

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        kioskManager = KioskManager(context)
    }

    @Test
    fun kioskManagerCreatesWithoutCrash() {
        assertNotNull("KioskManager should be created", kioskManager)
    }

    @Test
    fun kioskStateReportsCorrectly() {
        val state = kioskManager.getKioskState()
        assertNotNull("Kiosk state should not be null", state)
        // Without Device Owner, state should be NOT_DEVICE_OWNER or DISABLED
        assertTrue(
            "State should be NOT_DEVICE_OWNER or DISABLED",
            state == KioskState.NOT_DEVICE_OWNER || state == KioskState.DISABLED
        )
    }

    @Test
    fun deviceOwnerDetection() {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, AdminReceiver::class.java)

        // Check if we're device owner — result depends on test device setup
        val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)
        // Just verify the API doesn't crash
        assertNotNull("Device owner check should return a value", isDeviceOwner)
    }

    @Test
    fun kioskConfigDefaultsAreValid() {
        val config = KioskConfig()
        assertNotNull("Default config should be valid", config)
        assertNotNull("Auto-start config should exist", config.autoStart)
        assertNotNull("Screen config should exist", config.screen)
        assertNotNull("Security config should exist", config.security)
    }

    @Test
    fun kioskConfigPresetsLoad() {
        val applianceConfig = KioskConfig.APPLIANCE
        assertNotNull("Appliance preset should load", applianceConfig)

        val interactiveConfig = KioskConfig.INTERACTIVE
        assertNotNull("Interactive preset should load", interactiveConfig)
    }
}
