package com.lensdaemon

import android.content.Context
import android.content.Intent
import android.media.MediaCodecList
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import com.lensdaemon.encoder.EncoderService
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for EncoderService.
 * Verifies encoder service binding and hardware codec detection.
 */
@RunWith(AndroidJUnit4::class)
class EncoderServiceSmokeTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    @Test
    fun serviceBindsSuccessfully() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, EncoderService::class.java)

        val binder: IBinder = serviceRule.bindService(intent)
        assertNotNull("Binder should not be null", binder)

        val service = (binder as EncoderService.EncoderBinder).getService()
        assertNotNull("Service should not be null", service)
    }

    @Test
    fun serviceReportsNotEncodingInitially() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(context, EncoderService::class.java)

        val binder: IBinder = serviceRule.bindService(intent)
        val service = (binder as EncoderService.EncoderBinder).getService()

        assertFalse("Should not be encoding initially", service.isEncoding())
    }

    @Test
    fun deviceHasH264HardwareEncoder() {
        // Verify the device has at least one H.264 hardware encoder
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val h264Encoders = codecList.codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }
        }
        assertTrue(
            "Device should have at least one H.264 encoder",
            h264Encoders.isNotEmpty()
        )
    }

    @Test
    fun deviceSupportsMinimumResolution() {
        // Verify H.264 encoder supports at least 720p
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val h264Encoder = codecList.codecInfos.firstOrNull { info ->
            info.isEncoder && info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }
        }
        assertNotNull("Should have H.264 encoder", h264Encoder)

        val capabilities = h264Encoder!!.getCapabilitiesForType("video/avc")
        val videoCapabilities = capabilities.videoCapabilities
        assertTrue(
            "Encoder should support 720p width",
            videoCapabilities.supportedWidths.contains(1280)
        )
        assertTrue(
            "Encoder should support 720p height",
            videoCapabilities.supportedHeights.contains(720)
        )
    }
}
