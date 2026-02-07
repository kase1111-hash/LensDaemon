package com.lensdaemon.camera

import com.lensdaemon.encoder.EncodedFrame
import timber.log.Timber

/**
 * Thread-safe distributor for encoded video frames.
 *
 * Dispatches each frame to all registered listeners with per-listener
 * error isolation — one listener throwing does not skip the rest.
 */
class FrameDistributor {

    private val listeners = mutableListOf<(EncodedFrame) -> Unit>()

    fun addListener(listener: (EncodedFrame) -> Unit) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: (EncodedFrame) -> Unit) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun removeAll() {
        synchronized(listeners) {
            listeners.clear()
        }
    }

    fun dispatch(frame: EncodedFrame) {
        synchronized(listeners) {
            for (listener in listeners) {
                try {
                    listener(frame)
                } catch (e: Exception) {
                    Timber.e(e, "Error in encoded frame listener")
                }
            }
        }
    }

    fun listenerCount(): Int = synchronized(listeners) { listeners.size }
}
