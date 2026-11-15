package com.lensdaemon.web

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * MJPEG (Motion JPEG) live preview: a multipart/x-mixed-replace stream of JPEG
 * frames for the dashboard's preview.
 *
 * [pushFrame] runs on the camera thread and never touches a socket: it leaves
 * the newest frame in each viewer's single slot and returns. Each viewer's own
 * connection thread (NanoHTTPD gives every connection one) waits for its slot
 * and writes the frame, so a slow viewer skips frames rather than holding up
 * the camera or the other viewers. A viewer whose write has been stuck for
 * [stallTimeoutMs] (a phone locked with the preview open, a laptop that left
 * the Wi-Fi) is closed, which ends the write and frees its place.
 *
 * At most [maxViewers] watch at once; more get 503.
 */
class MjpegStreamer(
    private val quality: Int = 80,
    private val maxFps: Int = 15,
    private val maxViewers: Int = MAX_VIEWERS,
    private val stallTimeoutMs: Long = STALL_TIMEOUT_MS
) {
    companion object {
        private const val TAG = "MjpegStreamer"
        private const val BOUNDARY = "frame"
        private const val CONTENT_TYPE = "multipart/x-mixed-replace; boundary=$BOUNDARY"

        /** Viewers at once; more are answered 503. */
        const val MAX_VIEWERS = 8

        /** A viewer whose write has taken longer than this is closed. */
        const val STALL_TIMEOUT_MS = 5_000L

        /** How long a viewer waits for a frame before it looks again whether to stop. */
        private const val FRAME_WAIT_MS = 1_000L

        /** No write in progress (see MjpegClient.isStalled). */
        private const val NOT_WRITING = Long.MIN_VALUE

        private val HTTP_HEADER = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $CONTENT_TYPE\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Expires: 0\r\n" +
                "Connection: close\r\n" +
                "\r\n"
            ).toByteArray(Charsets.US_ASCII)

        private val FRAME_FOOTER = "\r\n".toByteArray(Charsets.US_ASCII)

        private fun frameHeader(size: Int): ByteArray =
            "--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: $size\r\n\r\n".toByteArray(Charsets.US_ASCII)
    }

    // Active viewers
    private val clients = ConcurrentHashMap<Int, MjpegClient>()
    private val clientIdCounter = AtomicInteger(0)

    // Frame statistics
    private val frameCount = AtomicLong(0)
    private val bytesSent = AtomicLong(0)
    private var lastFrameTime = 0L  // camera thread only
    private val minFrameIntervalMs = 1000L / maxFps
    private val stallTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(stallTimeoutMs)

    // Running state
    private val isRunning = AtomicBoolean(true)

    /**
     * Hand a new JPEG frame to every viewer (at most [maxFps] a second). Called
     * on the camera thread: it only fills the viewers' slots, and closes a
     * viewer whose write has been stuck for longer than [stallTimeoutMs].
     */
    fun pushFrame(jpegData: ByteArray) {
        if (!isRunning.get()) return

        // Rate limit frames
        val now = System.currentTimeMillis()
        if (now - lastFrameTime < minFrameIntervalMs) {
            return
        }
        lastFrameTime = now
        frameCount.incrementAndGet()

        val nowNanos = System.nanoTime()
        clients.values.forEach { client ->
            if (client.isStalled(nowNanos)) {
                Timber.w("$TAG: Viewer ${client.id} has not taken a frame for over $stallTimeoutMs ms: closing it")
                client.close()
            } else {
                client.offer(jpegData)
            }
        }
    }

    /**
     * The HTTP response for a new viewer: the stream itself, written on the
     * connection's own thread, or 503 when [maxViewers] are already watching.
     */
    fun createStreamResponse(): NanoHTTPD.Response {
        val client = openClient() ?: return unavailable(if (isRunning.get()) "Too many preview viewers" else "Preview stopped")
        Timber.i("$TAG: Viewer ${client.id} connected, ${clients.size} watching")

        val response = object : NanoHTTPD.Response(Status.OK, CONTENT_TYPE, ByteArrayInputStream(ByteArray(0)), -1) {
            override fun send(outputStream: OutputStream) {
                client.serve(outputStream)
            }

            // NanoHTTPD closes every response once it is done with it, sent or not,
            // so a viewer it never got to serve doesn't keep a place
            override fun close() {
                client.close()
                super.close()
            }
        }
        // Close the connection once the stream ends instead of waiting for another request on it
        response.closeConnection(true)
        return response
    }

    /** Register a new viewer; null when [maxViewers] are watching or the streamer has stopped. */
    @Synchronized
    internal fun openClient(): MjpegClient? {
        if (!isRunning.get() || clients.size >= maxViewers) return null
        val client = MjpegClient(clientIdCounter.incrementAndGet())
        clients[client.id] = client
        return client
    }

    private fun unavailable(message: String): NanoHTTPD.Response = NanoHTTPD.newFixedLengthResponse(
        Status.SERVICE_UNAVAILABLE,
        WebServer.MIME_JSON,
        JSONObject().put("error", message).toString()
    )

    /**
     * Get number of connected clients
     */
    fun getClientCount(): Int = clients.size

    /**
     * Get streaming statistics
     */
    fun getStats(): MjpegStats {
        return MjpegStats(
            clientCount = clients.size,
            frameCount = frameCount.get(),
            bytesSent = bytesSent.get(),
            quality = quality,
            maxFps = maxFps
        )
    }

    /**
     * Stop all streams and release resources: every viewer's stream ends
     * and new viewers are refused.
     */
    fun stop() {
        isRunning.set(false)
        clients.values.forEach { it.close() }
        clients.clear()
        Timber.i("$TAG: MJPEG streamer stopped")
    }

    /**
     * One preview viewer. Its connection thread runs [serve]; the camera thread
     * only calls [offer], which replaces the frame waiting in the single slot
     * and never waits on the connection.
     */
    internal inner class MjpegClient(val id: Int) {
        private val lock = ReentrantLock()
        private val frameReady = lock.newCondition()

        // The newest frame not yet taken by the viewer's thread; guarded by lock
        private var pending: ByteArray? = null

        @Volatile
        private var closed = false

        @Volatile
        private var output: OutputStream? = null

        // When the write in progress started (System.nanoTime), or NOT_WRITING
        @Volatile
        private var writeStartedAt = NOT_WRITING

        /** True once the viewer has been closed (it left, stalled, or the streamer stopped). */
        val isClosed: Boolean get() = closed

        /** Leave [jpeg] for the viewer, in place of a frame it has not taken yet. */
        fun offer(jpeg: ByteArray) {
            lock.withLock {
                pending = jpeg
                frameReady.signal()
            }
        }

        /** True when a write to this viewer has been going on for longer than the stall timeout. */
        fun isStalled(nowNanos: Long): Boolean {
            val started = writeStartedAt
            return started != NOT_WRITING && nowNanos - started > stallTimeoutNanos
        }

        /**
         * Stream to this viewer over [out] until it goes away, it is closed or
         * the streamer stops: the HTTP header, then each newest frame. Runs on
         * the viewer's own connection thread; the viewer is gone afterwards.
         */
        fun serve(out: OutputStream) {
            output = out
            try {
                if (!closed) {
                    write(out, HTTP_HEADER)
                    streamFrames(out)
                }
            } catch (e: IOException) {
                Timber.d(e, "$TAG: Viewer $id disconnected")
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Timber.d(e, "$TAG: Viewer $id interrupted")
            } finally {
                close()
                Timber.i("$TAG: Viewer $id left, ${clients.size} watching")
            }
        }

        private fun streamFrames(out: OutputStream) {
            while (!closed && isRunning.get()) {
                val frame = nextFrame() ?: continue
                write(out, frameHeader(frame.size), frame, FRAME_FOOTER)
                bytesSent.addAndGet(frame.size.toLong())
            }
        }

        /** The newest frame, waiting up to [FRAME_WAIT_MS] for one; null when none came or the viewer is closing. */
        private fun nextFrame(): ByteArray? = lock.withLock {
            if (pending == null && !closed) frameReady.await(FRAME_WAIT_MS, TimeUnit.MILLISECONDS)
            val frame = pending
            pending = null
            frame
        }

        /** Write [parts] and flush, noting when the write started so a stuck one is noticed. */
        private fun write(out: OutputStream, vararg parts: ByteArray) {
            writeStartedAt = System.nanoTime()
            try {
                parts.forEach { out.write(it) }
                out.flush()
            } finally {
                writeStartedAt = NOT_WRITING
            }
        }

        /**
         * Stop streaming to this viewer and give up its place: wakes its thread
         * and closes its connection, which also ends a write that is stuck.
         * Safe to call more than once and from any thread.
         */
        fun close() {
            closed = true
            clients.remove(id, this)
            lock.withLock { frameReady.signalAll() }
            val out = output ?: return
            try {
                out.close()
            } catch (e: IOException) {
                Timber.d(e, "$TAG: Closing viewer $id")
            }
        }
    }
}

/**
 * MJPEG streaming statistics
 */
data class MjpegStats(
    val clientCount: Int,
    val frameCount: Long,
    val bytesSent: Long,
    val quality: Int,
    val maxFps: Int
)

/**
 * JPEG frame converter for camera preview
 * Converts camera frames to JPEG for MJPEG streaming
 */
class JpegFrameConverter(
    private val quality: Int = 80
) {
    companion object {
        private const val TAG = "JpegFrameConverter"
    }

    /**
     * Convert YUV image to JPEG
     */
    fun convertToJpeg(
        yuvData: ByteArray,
        width: Int,
        height: Int,
        format: Int
    ): ByteArray? {
        return try {
            // Use Android's YuvImage for conversion
            val yuvImage = android.graphics.YuvImage(
                yuvData,
                format,
                width,
                height,
                null
            )

            val outputStream = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                android.graphics.Rect(0, 0, width, height),
                quality,
                outputStream
            )

            outputStream.toByteArray()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error converting to JPEG")
            null
        }
    }

    /**
     * Convert Bitmap to JPEG
     */
    fun convertToJpeg(bitmap: android.graphics.Bitmap): ByteArray? {
        return try {
            val outputStream = java.io.ByteArrayOutputStream()
            bitmap.compress(
                android.graphics.Bitmap.CompressFormat.JPEG,
                quality,
                outputStream
            )
            outputStream.toByteArray()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error converting bitmap to JPEG")
            null
        }
    }
}
