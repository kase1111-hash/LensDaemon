package com.lensdaemon

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import timber.log.Timber

/**
 * Device Admin Receiver for kiosk mode functionality.
 *
 * Setup command (via ADB):
 * adb shell dpm set-device-owner com.lensdaemon/.AdminReceiver
 *
 * Removing it again: `adb shell dpm remove-active-admin com.lensdaemon/.AdminReceiver`
 * works only if the app was a test-only build when set-device-owner ran (Android
 * records the flag then): Android Studio's Run, or a build with
 * -Pandroid.injected.testOnly=true installed with `adb install -t`. A normal install
 * can't be removed over ADB, and the app never gives device owner up, so only a
 * factory reset frees the phone (turn kiosk mode off first: it blocks factory reset).
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Timber.i("Device admin enabled")
        Toast.makeText(context, "LensDaemon device admin enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Timber.i("Device admin disabled")
        Toast.makeText(context, "LensDaemon device admin disabled", Toast.LENGTH_SHORT).show()
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Timber.i("Profile provisioning complete")
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        Timber.i("Entering lock task mode for package: $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        Timber.i("Exiting lock task mode")
    }

    companion object {
        /**
         * Check if this app is set as device owner
         */
        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as android.app.admin.DevicePolicyManager
            return dpm.isDeviceOwnerApp(context.packageName)
        }
    }
}
