package com.lensdaemon

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.lensdaemon.kiosk.KioskManager
import timber.log.Timber

/**
 * LensDaemon Application class.
 * Initializes logging, notification channels, and global app state.
 */
class LensDaemonApp : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "lensdaemon_service"
        const val NOTIFICATION_ID = 1001

        private lateinit var instance: LensDaemonApp

        fun getInstance(): LensDaemonApp = instance
    }

    /**
     * Kiosk mode for the whole app: the web API turns it on and off, and the
     * activity on screen pins or unpins itself to match.
     */
    val kioskManager: KioskManager by lazy { KioskManager(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        initLogging()
        createNotificationChannel()

        Timber.i("LensDaemon application started")
    }

    private fun initLogging() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            // In production, could plant a crash reporting tree
            Timber.plant(ReleaseTree())
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Release build logging tree - only logs warnings and errors
     */
    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority >= android.util.Log.WARN) {
                // Could send to crash reporting service here
                android.util.Log.println(priority, tag ?: "LensDaemon", message)
            }
        }
    }
}
