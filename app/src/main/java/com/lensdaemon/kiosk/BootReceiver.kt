package com.lensdaemon.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.lensdaemon.AdminReceiver
import com.lensdaemon.LensDaemonApp
import com.lensdaemon.MainActivity
import com.lensdaemon.camera.CameraService
import com.lensdaemon.thermal.ThermalService
import com.lensdaemon.web.WebServerService
import timber.log.Timber

/**
 * Starts LensDaemon after the phone boots, when kiosk mode and its auto-start
 * are on.
 *
 * - At once, while the boot broadcast lets the app start foreground services:
 *   the web server (dashboard and API) and thermal monitoring. Their foreground
 *   services also keep the process alive through the delay.
 * - After autoStart.delaySeconds (clamped to 0-120 s): the app's screen, which
 *   pins itself again as device owner, and the outputs autoStart asks for, RTSP
 *   and recording, each on its own, with the saved stream defaults (see
 *   [CameraService.autoStartIntent]). startStreaming alone starts nothing: an
 *   encoder with no output serves nobody. The settings are read again then,
 *   since the dashboard runs during the wait: kiosk mode or its auto-start
 *   turned off by then starts nothing more.
 *
 * What Android allows at boot:
 * - BOOT_COMPLETED arrives only after the first unlock, so restarting
 *   unattended after a power cut needs a phone without a secure screen lock
 *   (PIN, pattern or password).
 * - Without Device Owner, Android 10+ won't open the app's screen from the
 *   background, and Android 11+ gives a service started at boot no camera, so
 *   the camera is not started there.
 *
 * Boot and auto-start go into the kiosk event log (GET /api/kiosk/events).
 * After a crash, CameraService brings the outputs back itself (Android's
 * sticky restart, within the crash recovery budget).
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"

        /** Set on the boot start of [MainActivity]. */
        const val EXTRA_BOOT_START = "boot_start"
        private const val PREFS_NAME = "lensdaemon_boot"
        private const val KEY_LAST_BOOT_TIME = "last_boot_time"
        private const val KEY_BOOT_COUNT = "boot_count"
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"

        /** The longest wait, in seconds, before the app and the camera start. */
        const val MAX_DELAY_S = 120
        private const val MS_PER_SECOND = 1000L
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != ACTION_QUICKBOOT_POWERON) return

        Timber.tag(TAG).i("Boot completed received")
        val bootNumber = countBoot(context)

        val kiosk = kioskManager(context)
        val config = kiosk.getConfig()
        if (!config.enabled || !config.autoStart.enabled) {
            Timber.tag(TAG).i("Auto-start disabled, skipping boot sequence")
            return
        }
        kiosk.recordEvent(KioskEventType.BOOT_COMPLETED, "Phone started (boot $bootNumber)", mapOf("boot" to bootNumber))

        // Now, inside the boot broadcast: Android lets a receiver of it start
        // foreground services from the background, and they keep the process
        // alive during the delay
        launchService(context, WebServerService.createStartIntent(context), "web server")
        launchService(context, ThermalService.createStartIntent(context), "thermal monitoring")

        val delaySeconds = config.autoStart.delaySeconds.coerceIn(0, MAX_DELAY_S)
        Timber.tag(TAG).i("Auto-start enabled, opening LensDaemon in $delaySeconds s")
        val appContext = context.applicationContext
        handler.postDelayed({ startupSequence(appContext) }, delaySeconds * MS_PER_SECOND)
    }

    /**
     * After the delay: the app's screen, then the camera outputs autoStart
     * asks for. The settings are read again here, not taken from the boot:
     * the dashboard and the API run during the wait, so kiosk mode or its
     * auto-start turned off by then starts nothing, and RTSP and recording
     * follow what is saved now. Android 11+ without Device Owner gives a
     * service started at boot no camera, so the camera is skipped there.
     */
    private fun startupSequence(context: Context) {
        val kiosk = kioskManager(context)
        val config = kiosk.getConfig()
        if (!config.enabled || !config.autoStart.enabled) {
            Timber.tag(TAG).i("Kiosk mode or its auto-start was turned off during the wait: not starting LensDaemon")
            return
        }
        val autoStart = config.autoStart
        Timber.tag(TAG).i("Executing startup sequence")
        startMainActivity(context)

        if (!autoStart.startRtsp && !autoStart.startRecording) {
            Timber.tag(TAG).i("Auto-start: no output to start (startRtsp and startRecording are off)")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !AdminReceiver.isDeviceOwner(context)) {
            Timber.tag(TAG).w(
                "Auto-start: without Device Owner, Android 11+ gives a service started at boot no camera; " +
                    "not starting it (adb shell dpm set-device-owner com.lensdaemon/.AdminReceiver)"
            )
            return
        }
        val intent = CameraService.autoStartIntent(
            context,
            rtsp = autoStart.startRtsp,
            record = autoStart.startRecording
        )
        if (launchService(context, intent, "camera")) {
            val outputs = listOfNotNull(
                "RTSP".takeIf { autoStart.startRtsp },
                "recording".takeIf { autoStart.startRecording }
            )
            kiosk.recordEvent(
                KioskEventType.AUTO_START,
                "Auto-start: ${outputs.joinToString(" and ")}",
                mapOf("rtsp" to autoStart.startRtsp, "record" to autoStart.startRecording)
            )
        }
    }

    /**
     * Start the main activity. Android 10+ lets an app open its screen from the
     * background only as device owner; otherwise this is blocked.
     */
    private fun startMainActivity(context: Context) {
        try {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_BOOT_START, true)
            }
            context.startActivity(launchIntent)
            Timber.tag(TAG).d("Main activity started")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start main activity")
        }
    }

    /** Start the foreground service [intent] ([what], for the log); false when Android refuses it. */
    private fun launchService(context: Context, intent: Intent, what: String): Boolean = try {
        ContextCompat.startForegroundService(context, intent)
        Timber.tag(TAG).i("Started $what")
        true
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (Android 12+) is one
        Timber.tag(TAG).e(e, "Android refused to start $what")
        false
    } catch (e: SecurityException) {
        Timber.tag(TAG).e(e, "Android refused to start $what")
        false
    }

    private fun kioskManager(context: Context): KioskManager =
        (context.applicationContext as LensDaemonApp).kioskManager

    /** Count this boot in the boot preferences; returns its number. */
    private fun countBoot(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastBootTime = prefs.getLong(KEY_LAST_BOOT_TIME, 0)
        val bootNumber = prefs.getInt(KEY_BOOT_COUNT, 0) + 1

        prefs.edit()
            .putLong(KEY_LAST_BOOT_TIME, System.currentTimeMillis())
            .putInt(KEY_BOOT_COUNT, bootNumber)
            .apply()

        Timber.tag(TAG).i("Boot #$bootNumber, last boot: $lastBootTime")
        return bootNumber
    }
}
