package com.lensdaemon.kiosk

import android.text.InputType
import android.view.KeyEvent
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Applies kiosk mode to the activity on screen.
 *
 * Kiosk mode is a saved setting, turned on and off from the dashboard or the
 * setup wizard through the web API. While the activity is in the foreground it
 * follows that setting: pinned with lock task mode when kiosk mode is on and
 * LensDaemon is the device owner, unpinned when it is turned off. Someone at the
 * phone can leave kiosk mode by holding Volume Up + Volume Down (5 seconds by
 * default), then entering the exit PIN if one is set.
 */
class KioskActivityController(
    private val activity: AppCompatActivity,
    private val kioskManager: KioskManager
) {
    private var security: SecurityConfig = kioskManager.getConfig().security
    private var exitGesture: ButtonOverrideHandler = newExitGesture(security)
    private val pinCheck = PinEntryHandler()

    /** Follow the kiosk setting whenever the activity is in the foreground. */
    fun attach() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                kioskManager.config.collect { follow(it) }
            }
        }
    }

    /** While pinned, the volume keys feed the exit gesture. Returns whether the key was used. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!kioskManager.isLockTaskActive()) return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> exitGesture.onKeyDown(event.keyCode, event)
            KeyEvent.ACTION_UP -> exitGesture.onKeyUp(event.keyCode, event)
            else -> false
        }
    }

    fun release() {
        exitGesture.release()
    }

    private fun follow(config: KioskConfig) {
        if (config.security != security) {
            security = config.security
            exitGesture.release()
            exitGesture = newExitGesture(security)
        }
        val pinned = kioskManager.isLockTaskActive()
        if (config.enabled && !pinned && kioskManager.isDeviceOwner()) {
            Timber.i("Kiosk mode is on: pinning LensDaemon to the screen")
            kioskManager.enableKiosk(activity)
        } else if (!config.enabled && pinned) {
            Timber.i("Kiosk mode is off: unpinning LensDaemon")
            kioskManager.disableKiosk(activity)
        }
    }

    private fun newExitGesture(security: SecurityConfig) = ButtonOverrideHandler(security).apply {
        setExitGestureListener(object : ButtonOverrideHandler.ExitGestureListener {
            override fun onExitGestureDetected() = onExitRequested()
            override fun onExitGestureProgress(progress: Float) = Unit
            override fun onExitGestureCancelled() = Unit
        })
    }

    private fun onExitRequested() {
        val pin = kioskManager.getConfig().security.exitPin
        if (pin.isEmpty()) {
            leaveKiosk()
        } else {
            askForPin(pin)
        }
    }

    private fun askForPin(pin: String) {
        if (pinCheck.isLockedOut()) {
            val seconds = pinCheck.getRemainingLockout() / MILLIS_PER_SECOND + 1
            Toast.makeText(activity, "Too many wrong PINs. Try again in $seconds s", Toast.LENGTH_LONG).show()
            return
        }
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(activity)
            .setTitle("Leave kiosk mode")
            .setMessage("Enter the exit PIN")
            .setView(input)
            .setPositiveButton("Leave") { _, _ ->
                pinCheck.setPin(pin)
                pinCheck.clear()
                input.text.forEach { pinCheck.addDigit(it) }
                if (pinCheck.submit()) {
                    leaveKiosk()
                } else {
                    Toast.makeText(activity, "Wrong PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun leaveKiosk() {
        Timber.i("Leaving kiosk mode from the phone")
        kioskManager.disableKiosk(activity)
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000
    }
}
