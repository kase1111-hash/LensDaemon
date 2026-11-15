package com.lensdaemon.kiosk

import android.text.InputType
import android.view.KeyEvent
import android.view.WindowManager
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
 * default), then entering the exit PIN if one is set; the gesture and every PIN
 * attempt go into the kiosk event log.
 *
 * It also owns the screen. FLAG_KEEP_SCREEN_ON is set, except while pinned with
 * keepScreenOn off or screen mode OFF: then the phone's own screen timeout turns
 * the display off. Nothing locks the phone, so Vol Up + Vol Down stays within
 * reach once someone wakes it. While pinned, DIM and BLACK are applied through
 * [ScreenController], also when the settings change while pinned; unpinning
 * brings the normal preview back. The auto-off timer (autoOffTimeoutSec) is not
 * used.
 */
class KioskActivityController(
    private val activity: AppCompatActivity,
    private val kioskManager: KioskManager
) {
    private var security: SecurityConfig = kioskManager.getConfig().security
    private var exitGesture: ButtonOverrideHandler = newExitGesture(security)
    private val pinCheck = PinEntryHandler()
    private val screen = ScreenController(activity.applicationContext)

    /** The screen settings applied while pinned; null while not pinned. */
    private var appliedScreen: ScreenConfig? = null

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
        screen.release()
    }

    /** Runs on every resume and every settings change while resumed. */
    private fun follow(config: KioskConfig) {
        if (config.security != security) {
            security = config.security
            exitGesture.release()
            exitGesture = newExitGesture(security)
        }
        var pinned = kioskManager.isLockTaskActive()
        if (config.enabled && !pinned && kioskManager.isDeviceOwner()) {
            Timber.i("Kiosk mode is on: pinning LensDaemon to the screen")
            pinned = kioskManager.enableKiosk(activity)
        } else if (!config.enabled && pinned) {
            Timber.i("Kiosk mode is off: unpinning LensDaemon")
            kioskManager.disableKiosk(activity)
        }
        applyScreen(config, pinned)
    }

    /**
     * The screen for [config]: its mode while kiosk mode is on and the phone
     * is [pinned] (when it changed since last applied), the normal preview
     * otherwise, and keep-screen-on. Kiosk mode off always brings the normal
     * screen back: Android reports the end of lock task mode a moment after
     * stopLockTask, so [pinned] can still read true right after unpinning.
     * OFF shows the preview and lets the screen timeout turn the display off:
     * ScreenController's OFF locks the phone, and since this runs on every
     * resume, the phone would lock again before Vol Up + Vol Down could be held.
     */
    private fun applyScreen(config: KioskConfig, pinned: Boolean) {
        val kioskScreen = pinned && config.enabled
        if (kioskScreen && config.screen != appliedScreen) {
            screen.configure(config.screen.copy(autoOffTimeoutSec = 0))
            screen.applyMode(activity, if (config.screen.mode == ScreenMode.OFF) ScreenMode.PREVIEW else config.screen.mode)
            appliedScreen = config.screen
        } else if (!kioskScreen && appliedScreen != null) {
            screen.applyMode(activity, ScreenMode.PREVIEW)
            appliedScreen = null
        }
        // Last, because applyMode may have set the flag
        val keepOn = !kioskScreen || (config.screen.keepScreenOn && config.screen.mode != ScreenMode.OFF)
        if (keepOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        kioskManager.recordEvent(
            KioskEventType.EXIT_GESTURE_DETECTED,
            "Exit gesture held at the phone",
            mapOf("pinRequired" to pin.isNotEmpty())
        )
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
                val correct = pinCheck.submit()
                kioskManager.recordEvent(
                    KioskEventType.EXIT_PIN_ENTERED,
                    if (correct) "Correct exit PIN entered at the phone" else "Wrong exit PIN entered at the phone",
                    mapOf("correct" to correct)
                )
                if (correct) {
                    leaveKiosk()
                } else {
                    Toast.makeText(activity, "Wrong PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Unpin and turn kiosk mode off; the screen comes back to normal at once. */
    private fun leaveKiosk() {
        Timber.i("Leaving kiosk mode from the phone")
        kioskManager.disableKiosk(activity)
        applyScreen(kioskManager.getConfig(), kioskManager.isLockTaskActive())
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000
    }
}
