package com.lensdaemon.web.handlers

import com.lensdaemon.director.DirectorManager
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The body of GET /api/director/events, as Server-Sent Events: each director event is one
 * unnamed `data:` frame holding a JSON object whose `type` says what happened (state,
 * state_changed, script_loaded, cue_executed, take_started, take_ended, error or
 * thermal_warning), starting with the director's current state.
 *
 * NanoHTTPD sends it as a chunked response from the connection's thread, which blocks in
 * [read] until the next frame. After [KEEPALIVE_SECONDS] without one, [read] returns a
 * `: keepalive` comment, so writing to a client that went away fails; NanoHTTPD then
 * closes this stream, and [close] stops listening to the director. Frames wait in a
 * bounded queue: when a client reads too slowly, newer frames are dropped.
 */
internal class DirectorEventStream(director: DirectorManager) : InputStream() {

    companion object {
        const val MIME_TYPE = "text/event-stream"

        private const val TAG = "DirectorEventStream"
        private const val QUEUE_CAPACITY = 256
        private const val KEEPALIVE_SECONDS = 15L
        private val KEEPALIVE = ": keepalive\n\n".toByteArray(Charsets.UTF_8)

        /** The event stream response for [director]: not cached, and with no CORS header. */
        fun response(director: DirectorManager): NanoHTTPD.Response =
            NanoHTTPD.newChunkedResponse(Status.OK, MIME_TYPE, DirectorEventStream(director)).apply {
                addHeader("Cache-Control", "no-cache")
                addHeader("Connection", "keep-alive")
            }

        /** The first frame: where the director is now. */
        fun stateJson(director: DirectorManager): JSONObject {
            val status = director.getStatus()
            return JSONObject()
                .put("type", "state")
                .put("enabled", status.enabled)
                .put("state", status.state.name)
                .put("currentScene", status.currentScene ?: "")
                .put("currentCue", status.currentCue ?: "")
                .put("currentTake", status.takeNumber)
        }

        /** One director event, as the JSON of its frame. */
        fun eventJson(event: DirectorManager.DirectorEvent): JSONObject = when (event) {
            is DirectorManager.DirectorEvent.StateChanged -> JSONObject()
                .put("type", "state_changed")
                .put("state", event.state.name)
            is DirectorManager.DirectorEvent.ScriptLoaded -> JSONObject()
                .put("type", "script_loaded")
                .put("scenes", event.script.scenes.size)
                .put("totalCues", event.script.totalCues)
            is DirectorManager.DirectorEvent.CueExecuted -> JSONObject()
                .put("type", "cue_executed")
                .put("cueType", event.cue.type.name)
                .put("cueText", event.cue.rawText)
                .put("success", event.success)
            is DirectorManager.DirectorEvent.TakeStarted -> JSONObject()
                .put("type", "take_started")
                .put("takeNumber", event.take.takeNumber)
                .put("sceneId", event.take.sceneId)
            is DirectorManager.DirectorEvent.TakeEnded -> JSONObject()
                .put("type", "take_ended")
                .put("takeNumber", event.take.takeNumber)
                .put("qualityScore", event.take.qualityScore)
                .put("durationMs", event.take.durationMs)
            is DirectorManager.DirectorEvent.Error -> JSONObject()
                .put("type", "error")
                .put("message", event.message)
            is DirectorManager.DirectorEvent.ThermalWarning -> JSONObject()
                .put("type", "thermal_warning")
                .put("temperature", event.temperature)
                .put("action", event.action)
        }
    }

    private val queue = LinkedBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var closed = false

    // The frame being read, and how much of it is read (the reading thread's own)
    private var chunk = ByteArray(0)
    private var offset = 0

    init {
        scope.launch {
            director.events
                .onSubscription { enqueue(stateJson(director)) }
                .collect { enqueue(eventJson(it)) }
        }
    }

    override fun read(): Int {
        if (!nextChunk()) return -1
        return chunk[offset++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!nextChunk()) return -1
        val count = minOf(len, chunk.size - offset)
        System.arraycopy(chunk, offset, b, off, count)
        offset += count
        return count
    }

    override fun close() {
        closed = true
        scope.cancel()
        queue.clear()
        // Wakes a read waiting for the next frame
        queue.offer(ByteArray(0))
    }

    private fun enqueue(json: JSONObject) {
        if (!closed) {
            queue.offer("data: $json\n\n".toByteArray(Charsets.UTF_8))
        }
    }

    /** Makes [chunk] hold unread bytes: the next frame, or a keepalive after a quiet spell. False once closed. */
    private fun nextChunk(): Boolean {
        while (offset >= chunk.size) {
            if (closed) return false
            val next = try {
                queue.poll(KEEPALIVE_SECONDS, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Timber.tag(TAG).d(e, "Director event stream interrupted")
                Thread.currentThread().interrupt()
                return false
            }
            if (closed) return false
            chunk = next ?: KEEPALIVE
            offset = 0
        }
        return true
    }
}
