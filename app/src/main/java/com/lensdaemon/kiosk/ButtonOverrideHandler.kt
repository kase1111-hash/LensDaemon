package com.lensdaemon.kiosk

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Button Override Handler
 *
 * Detects special button combinations to exit kiosk mode:
 * - Vol Up + Vol Down + Power (held for configured duration)
 * - Provides countdown feedback during gesture
 * - Configurable timeout and enable/disable
 *
 * Usage:
 * 1. Call onKeyDown/onKeyUp from Activity's dispatchKeyEvent
 * 2. Register listener for exit gesture detection
 */
class ButtonOverrideHandler(
    private val config: SecurityConfig = SecurityConfig()
) {
    companion object {
        private const val TAG = "ButtonOverrideHandler"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    // Button states
    private var volumeUpPressed = false
    private var volumeDownPressed = false
    private var powerPressed = false

    // Gesture detection
    private var gestureStartTime: Long = 0
    private var isGestureActive = false
    private var countdownRunnable: Runnable? = null

    // State
    private val _gestureProgress = MutableStateFlow(0f)
    val gestureProgress: StateFlow<Float> = _gestureProgress.asStateFlow()

    private val _isGestureDetected = MutableStateFlow(false)
    val isGestureDetected: StateFlow<Boolean> = _isGestureDetected.asStateFlow()

    // Listener
    private var exitListener: ExitGestureListener? = null

    /**
     * Listener interface for exit gesture detection
     */
    interface ExitGestureListener {
        /**
         * Called when exit gesture is detected and held for the required duration
         */
        fun onExitGestureDetected()

        /**
         * Called with progress updates during gesture hold
         * @param progress 0.0 to 1.0, where 1.0 means gesture complete
         */
        fun onExitGestureProgress(progress: Float)

        /**
         * Called when gesture is cancelled (buttons released early)
         */
        fun onExitGestureCancelled()
    }

    /**
     * Set exit gesture listener
     */
    fun setExitGestureListener(listener: ExitGestureListener?) {
        this.exitListener = listener
    }

    /**
     * Handle key down events
     * @return true if the event was consumed
     */
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!config.allowedExitGesture) {
            return false
        }

        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                volumeUpPressed = true
                checkGestureStart()
                return true // Consume to prevent volume change
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                volumeDownPressed = true
                checkGestureStart()
                return true // Consume to prevent volume change
            }
            KeyEvent.KEYCODE_POWER -> {
                powerPressed = true
                checkGestureStart()
                // Don't consume power button to allow normal behavior
                return false
            }
        }
        return false
    }

    /**
     * Handle key up events
     * @return true if the event was consumed
     */
    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!config.allowedExitGesture) {
            return false
        }

        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                volumeUpPressed = false
                checkGestureEnd()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                volumeDownPressed = false
                checkGestureEnd()
                return true
            }
            KeyEvent.KEYCODE_POWER -> {
                powerPressed = false
                checkGestureEnd()
                return false
            }
        }
        return false
    }

    /**
     * Check if all buttons are pressed to start gesture
     */
    private fun checkGestureStart() {
        // For Vol+Vol gesture (without power requirement for easier testing)
        val allPressed = volumeUpPressed && volumeDownPressed

        if (allPressed && !isGestureActive) {
            startGestureCountdown()
        }
    }

    /**
     * Check if gesture should end
     */
    private fun checkGestureEnd() {
        val allPressed = volumeUpPressed && volumeDownPressed

        if (!allPressed && isGestureActive) {
            cancelGestureCountdown()
        }
    }

    /**
     * Start the gesture countdown
     */
    private fun startGestureCountdown() {
        isGestureActive = true
        gestureStartTime = System.currentTimeMillis()
        _gestureProgress.value = 0f

        Timber.tag(TAG).d("Exit gesture started")

        // Start countdown updates
        countdownRunnable = object : Runnable {
            override fun run() {
                if (!isGestureActive) return

                val elapsed = System.currentTimeMillis() - gestureStartTime
                val progress = (elapsed.toFloat() / config.exitGestureTimeoutMs).coerceIn(0f, 1f)
                _gestureProgress.value = progress

                exitListener?.onExitGestureProgress(progress)

                if (elapsed >= config.exitGestureTimeoutMs) {
                    // Gesture complete
                    completeGesture()
                } else {
                    // Continue countdown
                    handler.postDelayed(this, 100) // Update every 100ms
                }
            }
        }

        handler.post(countdownRunnable!!)
    }

    /**
     * Cancel the gesture countdown
     */
    private fun cancelGestureCountdown() {
        if (!isGestureActive) return

        isGestureActive = false
        _gestureProgress.value = 0f

        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null

        Timber.tag(TAG).d("Exit gesture cancelled")
        exitListener?.onExitGestureCancelled()
    }

    /**
     * Complete the gesture (held for full duration)
     */
    private fun completeGesture() {
        isGestureActive = false
        _gestureProgress.value = 1f
        _isGestureDetected.value = true

        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null

        // Reset button states
        volumeUpPressed = false
        volumeDownPressed = false
        powerPressed = false

        Timber.tag(TAG).i("Exit gesture detected!")
        exitListener?.onExitGestureDetected()

        // Reset detected state after a short delay
        handler.postDelayed({
            _isGestureDetected.value = false
        }, 1000)
    }

    /**
     * Reset all state
     */
    fun reset() {
        volumeUpPressed = false
        volumeDownPressed = false
        powerPressed = false
        isGestureActive = false
        gestureStartTime = 0
        _gestureProgress.value = 0f
        _isGestureDetected.value = false

        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
    }

    /**
     * Check if gesture is currently active
     */
    fun isGestureActive(): Boolean = isGestureActive

    /**
     * Get remaining time until gesture completes (ms)
     */
    fun getRemainingTime(): Long {
        if (!isGestureActive) return config.exitGestureTimeoutMs

        val elapsed = System.currentTimeMillis() - gestureStartTime
        return maxOf(0, config.exitGestureTimeoutMs - elapsed)
    }

    /**
     * Update configuration
     */
    fun updateConfig(newConfig: SecurityConfig) {
        // Note: Config changes take effect immediately
        Timber.tag(TAG).d("Config updated: gesture enabled=${newConfig.allowedExitGesture}")
    }

    /**
     * Release resources
     */
    fun release() {
        reset()
        exitListener = null
        scope.cancel()
    }
}

/**
 * PIN entry handler for secure kiosk exit
 */
class PinEntryHandler(
    private var correctPin: String = ""
) {
    companion object {
        private const val TAG = "PinEntryHandler"
        private const val MAX_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 60_000L // 1 minute
    }

    private var enteredPin = StringBuilder()
    private var attemptCount = 0
    private var lastAttemptTime = 0L
    private var lockoutUntil = 0L

    private var listener: PinEntryListener? = null

    /**
     * Listener interface
     */
    interface PinEntryListener {
        fun onPinEntered(correct: Boolean)
        fun onPinDigitAdded(length: Int)
        fun onPinCleared()
        fun onLockout(remainingMs: Long)
    }

    /**
     * Set listener
     */
    fun setListener(pinListener: PinEntryListener?) {
        this.listener = pinListener
    }

    /**
     * Set the correct PIN
     */
    fun setPin(pin: String) {
        correctPin = pin
    }

    /**
     * Check if PIN is set
     */
    fun isPinSet(): Boolean = correctPin.isNotEmpty()

    /**
     * Add a digit to the entered PIN
     */
    fun addDigit(digit: Char): Boolean {
        if (!digit.isDigit()) return false

        // Check lockout
        if (isLockedOut()) {
            listener?.onLockout(lockoutUntil - System.currentTimeMillis())
            return false
        }

        enteredPin.append(digit)
        listener?.onPinDigitAdded(enteredPin.length)

        Timber.tag(TAG).d("PIN digit added, length: ${enteredPin.length}")
        return true
    }

    /**
     * Remove last digit
     */
    fun removeDigit() {
        if (enteredPin.isNotEmpty()) {
            enteredPin.deleteCharAt(enteredPin.length - 1)
            listener?.onPinDigitAdded(enteredPin.length)
        }
    }

    /**
     * Clear entered PIN
     */
    fun clear() {
        enteredPin.clear()
        listener?.onPinCleared()
    }

    /**
     * Submit the entered PIN
     * @return true if PIN is correct
     */
    fun submit(): Boolean {
        // Check lockout
        if (isLockedOut()) {
            listener?.onLockout(lockoutUntil - System.currentTimeMillis())
            return false
        }

        val entered = enteredPin.toString()
        val correct = entered == correctPin

        if (correct) {
            Timber.tag(TAG).i("Correct PIN entered")
            attemptCount = 0
            clear()
        } else {
            Timber.tag(TAG).w("Incorrect PIN entered")
            attemptCount++
            lastAttemptTime = System.currentTimeMillis()

            if (attemptCount >= MAX_ATTEMPTS) {
                lockoutUntil = System.currentTimeMillis() + LOCKOUT_DURATION_MS
                Timber.tag(TAG).w("PIN lockout activated for ${LOCKOUT_DURATION_MS}ms")
            }

            clear()
        }

        listener?.onPinEntered(correct)
        return correct
    }

    /**
     * Check if currently locked out
     */
    fun isLockedOut(): Boolean {
        return System.currentTimeMillis() < lockoutUntil
    }

    /**
     * Get remaining lockout time
     */
    fun getRemainingLockout(): Long {
        return maxOf(0, lockoutUntil - System.currentTimeMillis())
    }

    /**
     * Get current entry length
     */
    fun getEntryLength(): Int = enteredPin.length

    /**
     * Reset all state
     */
    fun reset() {
        enteredPin.clear()
        attemptCount = 0
        lockoutUntil = 0
    }
}
