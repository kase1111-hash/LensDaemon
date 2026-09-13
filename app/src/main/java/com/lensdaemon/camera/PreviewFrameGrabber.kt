package com.lensdaemon.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Turns captured YUV_420_888 images into JPEG previews on demand: the
 * dashboard's MJPEG stream and one-off snapshots.
 *
 * Conversion runs on the camera thread, so it only happens when a consumer
 * actually wants a frame, and stream frames are rate limited to [maxFps].
 */
class PreviewFrameGrabber(
    private val quality: Int = DEFAULT_QUALITY,
    private val maxFps: Int = DEFAULT_MAX_FPS
) {
    companion object {
        private const val TAG = "PreviewFrameGrabber"
        private const val DEFAULT_QUALITY = 80
        private const val DEFAULT_MAX_FPS = 5
        private const val MS_PER_SECOND = 1000L
    }

    private class SnapshotWaiter {
        private val latch = CountDownLatch(1)

        @Volatile
        private var jpeg: ByteArray? = null

        fun deliver(data: ByteArray) {
            jpeg = data
            latch.countDown()
        }

        fun await(timeoutMs: Long): ByteArray? =
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) jpeg else null
    }

    /** Whether the stream sink currently wants frames (e.g. the MJPEG stream has viewers). */
    @Volatile
    var streamDemand: () -> Boolean = { false }

    /** Receives stream frames as JPEG, at most [maxFps] per second. */
    @Volatile
    var streamSink: ((ByteArray) -> Unit)? = null

    private val snapshotWaiters = CopyOnWriteArrayList<SnapshotWaiter>()

    @Volatile
    private var lastStreamFrameMs = 0L

    /**
     * Called with each captured image, still open, on the camera thread.
     * Returns quickly unless someone is waiting for a frame.
     */
    fun onImage(image: Image) {
        val wantsSnapshot = snapshotWaiters.isNotEmpty()
        val now = System.currentTimeMillis()
        val wantsStream = streamSink != null && streamDemand() &&
            now - lastStreamFrameMs >= MS_PER_SECOND / maxFps
        if (!wantsSnapshot && !wantsStream) return

        val jpeg = toJpeg(image) ?: return
        if (wantsStream) {
            lastStreamFrameMs = now
            streamSink?.invoke(jpeg)
        }
        if (wantsSnapshot) {
            snapshotWaiters.forEach { it.deliver(jpeg) }
        }
    }

    /**
     * Block for up to [timeoutMs] for the next captured frame as a JPEG.
     * Returns null when no frame arrived in time (camera not running).
     */
    fun captureSnapshot(timeoutMs: Long): ByteArray? {
        val waiter = SnapshotWaiter()
        snapshotWaiters.add(waiter)
        return try {
            waiter.await(timeoutMs)
        } finally {
            snapshotWaiters.remove(waiter)
        }
    }

    private fun toJpeg(image: Image): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) {
            Timber.tag(TAG).w("Unsupported image format ${image.format}")
            return null
        }
        return try {
            val nv21 = yuv420ToNv21(image)
            val out = ByteArrayOutputStream()
            YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
                .compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
            out.toByteArray()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "JPEG conversion failed")
            null
        }
    }
}

/**
 * Repack a YUV_420_888 image (any row or pixel stride) into NV21 for YuvImage.
 */
internal fun yuv420ToNv21(image: Image): ByteArray {
    val width = image.width
    val height = image.height
    val out = ByteArray(width * height * 3 / 2)

    val yPlane = image.planes[0]
    val yBuffer = yPlane.buffer
    var offset = 0
    if (yPlane.pixelStride == 1 && yPlane.rowStride == width) {
        yBuffer.get(out, 0, width * height)
        offset = width * height
    } else {
        for (row in 0 until height) {
            val rowStart = row * yPlane.rowStride
            for (col in 0 until width) {
                out[offset++] = yBuffer.get(rowStart + col * yPlane.pixelStride)
            }
        }
    }

    val uPlane = image.planes[1]
    val vPlane = image.planes[2]
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    for (row in 0 until height / 2) {
        val uRow = row * uPlane.rowStride
        val vRow = row * vPlane.rowStride
        for (col in 0 until width / 2) {
            out[offset++] = vBuffer.get(vRow + col * vPlane.pixelStride)
            out[offset++] = uBuffer.get(uRow + col * uPlane.pixelStride)
        }
    }
    return out
}
