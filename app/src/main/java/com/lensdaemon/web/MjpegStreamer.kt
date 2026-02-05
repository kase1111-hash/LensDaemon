package com.lensdaemon.web

import fi.iki.elonen.NanoHTTPD
import timber.log.Timber
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * MJPEG (Motion JPEG) streamer for live preview
 * Provides HTTP-based video preview using multipart JPEG stream
 */
class MjpegStreamer(
    private val quality: Int = 80,
    private val maxFps: Int = 15
) {
    companion object {
        private const val TAG = "MjpegStreamer"
        private const val BOUNDARY = "frame"
        private const val CONTENT_TYPE = "multipart/x-mixed-replace; boundary=$BOUNDARY"

        // Frame header format
        private const val FRAME_HEADER = "--$BOUNDARY\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\n\r\n"
        private const val FRAME_FOOTER = "\r\n"
    }

    // Active client streams
    private val clients = ConcurrentHashMap<Int, MjpegClient>()
    private val clientIdCounter = AtomicInteger(0)

    // Frame statistics
    private val frameCount = AtomicLong(0)
    private val bytesSent = AtomicLong(0)
    private var lastFrameTime = 0L
    private val minFrameIntervalMs = 1000L / maxFps

    // Current frame (shared across all clients)
    @Volatile
    private var currentFrame: ByteArray? = null

    // Running state
    private val isRunning = AtomicBoolean(true)

    /**
     * Push a new JPEG frame to all connected clients
     */
    fun pushFrame(jpegData: ByteArray) {
        if (!isRunning.get()) return

        // Rate limit frames
        val now = System.currentTimeMillis()
        if (now - lastFrameTime < minFrameIntervalMs) {
            return
        }
        lastFrameTime = now

        currentFrame = jpegData
        frameCount.incrementAndGet()

        // Send to all connected clients
        val clientsToRemove = mutableListOf<Int>()

        clients.forEach { (id, client) ->
            try {
                client.sendFrame(jpegData)
                bytesSent.addAndGet(jpegData.size.toLong())
            } catch (e: Exception) {
                Timber.w("$TAG: Error sending frame to client $id: ${e.message}")
                clientsToRemove.add(id)
            }
        }

        // Remove disconnected clients
        clientsToRemove.forEach { id ->
            clients.remove(id)?.close()
        }
    }

    /**
     * Create HTTP response for MJPEG stream
     */
    fun createStreamResponse(): NanoHTTPD.Response {
        val clientId = clientIdCounter.incrementAndGet()
        val client = MjpegClient(clientId)
        clients[clientId] = client

        Timber.i("$TAG: Client $clientId connected, total clients: ${clients.size}")

        // Create chunked response with MJPEG content type
        return object : NanoHTTPD.Response(
            Status.OK,
            CONTENT_TYPE,
            client.inputStream,
            -1
        ) {
            override fun send(outputStream: OutputStream) {
                try {
                    // Write headers
                    val sb = StringBuilder()
                    sb.append("HTTP/1.1 ${status.description}\r\n")
                    sb.append("Content-Type: $CONTENT_TYPE\r\n")
                    sb.append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
                    sb.append("Pragma: no-cache\r\n")
                    sb.append("Expires: 0\r\n")
                    sb.append("Connection: close\r\n")
                    sb.append("\r\n")

                    outputStream.write(sb.toString().toByteArray())
                    outputStream.flush()

                    // Stream frames until client disconnects
                    client.streamTo(outputStream)

                } catch (e: IOException) {
                    Timber.d("$TAG: Client $clientId disconnected")
                } finally {
                    clients.remove(clientId)
                    client.close()
                    Timber.i("$TAG: Client $clientId removed, remaining: ${clients.size}")
                }
            }
        }
    }

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
     * Stop all streams and release resources
     */
    fun stop() {
        isRunning.set(false)
        clients.values.forEach { it.close() }
        clients.clear()
        currentFrame = null
        Timber.i("$TAG: MJPEG streamer stopped")
    }

    /**
     * Individual MJPEG client handler
     */
    private inner class MjpegClient(val id: Int) {
        val pipedOutput = PipedOutputStream()
        val inputStream = PipedInputStream(pipedOutput, 1024 * 1024) // 1MB buffer

        private val isActive = AtomicBoolean(true)
        @Volatile
        private var outputStream: OutputStream? = null

        /**
         * Send a frame to this client
         */
        fun sendFrame(jpegData: ByteArray) {
            if (!isActive.get()) return

            val output = outputStream ?: return

            try {
                val header = String.format(FRAME_HEADER, jpegData.size)
                output.write(header.toByteArray())
                output.write(jpegData)
                output.write(FRAME_FOOTER.toByteArray())
                output.flush()
            } catch (e: IOException) {
                isActive.set(false)
                throw e
            }
        }

        /**
         * Stream frames to output
         */
        fun streamTo(output: OutputStream) {
            this.outputStream = output

            // Wait for frames until client disconnects
            while (isActive.get() && isRunning.get()) {
                try {
                    Thread.sleep(10)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }

        /**
         * Close this client
         */
        fun close() {
            isActive.set(false)
            try {
                pipedOutput.close()
                inputStream.close()
            } catch (e: Exception) {
                // Ignore
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
