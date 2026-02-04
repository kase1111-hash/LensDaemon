package com.lensdaemon.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lensdaemon.MainActivity
import timber.log.Timber

/**
 * Broadcast receiver to auto-start LensDaemon on device boot.
 *
 * Implementation will be completed in Phase 10.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            Timber.i("Boot completed, starting LensDaemon")

            // TODO: Phase 10 - Check if autostart is enabled in config
            val autoStartEnabled = true // Will read from config

            if (autoStartEnabled) {
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(launchIntent)
            }
        }
    }
}
