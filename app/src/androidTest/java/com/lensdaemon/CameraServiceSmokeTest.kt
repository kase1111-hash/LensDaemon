package com.lensdaemon

import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import com.lensdaemon.camera.CameraService
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for CameraService.
 * Verifies the service can bind, report state, and expose its API.
 *
 * Note: Camera preview/streaming tests require a device with a camera
 * and camera permissions granted. These tests verify the service lifecycle
 * without requiring actual camera hardware.
 */
@RunWith(AndroidJUnit4::class)
class CameraServiceSmokeTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    @Test
    fun serviceBindsSuccessfully() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, CameraService::class.java)

        val binder: IBinder = serviceRule.bindService(intent)
        assertNotNull("Binder should not be null", binder)

        val service = (binder as CameraService.LocalBinder).getService()
        assertNotNull("Service should not be null", service)
    }

    @Test
    fun serviceReportsInitialState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, CameraService::class.java)

        val binder: IBinder = serviceRule.bindService(intent)
        val service = (binder as CameraService.LocalBinder).getService()

        // Should not be streaming initially
        assertFalse("Should not be streaming initially", service.isStreaming())
    }

    @Test
    fun serviceReportsAvailableLenses() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, CameraService::class.java)

        val binder: IBinder = serviceRule.bindService(intent)
        val service = (binder as CameraService.LocalBinder).getService()

        // Camera enumeration should return at least an empty list (no crash)
        val lenses = service.getAvailableLenses()
        assertNotNull("Available lenses should not be null", lenses)
        // On a real device this will be non-empty; on emulator it may vary
    }

    @Test
    fun encoderStatsReturnSafely() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, CameraService::class.java)

        val binder: IBinder = serviceRule.bindService(intent)
        val service = (binder as CameraService.LocalBinder).getService()

        // Getting encoder stats before encoding should not crash
        val stats = service.getEncoderStats()
        // Stats may be null if no encoder is active — that's OK
    }
}
