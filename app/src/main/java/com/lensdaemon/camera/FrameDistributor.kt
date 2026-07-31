package com.lensdaemon.camera

import com.lensdaemon.encoder.EncodedFrame
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe distributor for encoded video frames.
 *
 * Dispatches each frame to all registered listeners with per-listener
 * error isolation — one listener throwing does not skip the rest.
 *
 * Listeners are held in a copy-on-write list so that registration and removal
 * never wait on an in-flight dispatch. That matters when an output blocks:
 * a viewer disconnecting, or recording stopping, must not queue up behind a
 * stalled socket write in some other listener.
 *
 * Note that listeners still run inline on the caller's (encoder) thread, so a
 * listener that blocks delays every later listener and the encoder callback
 * itself. Outputs that can block are expected to hand off internally rather
 * than block here.
 */
class FrameDistributor {

    private val listeners = CopyOnWriteArrayList<(EncodedFrame) -> Unit>()

    fun addListener(listener: (EncodedFrame) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (EncodedFrame) -> Unit) {
        listeners.remove(listener)
    }

    fun removeAll() {
        listeners.clear()
    }

    fun dispatch(frame: EncodedFrame) {
        for (listener in listeners) {
            try {
                listener(frame)
            } catch (e: Exception) {
                Timber.e(e, "Error in encoded frame listener")
            }
        }
    }

    fun listenerCount(): Int = listeners.size
}
