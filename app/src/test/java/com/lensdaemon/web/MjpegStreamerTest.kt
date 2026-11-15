package com.lensdaemon.web

import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The preview must never make the camera thread wait for a viewer: pushFrame
 * only fills each viewer's slot, and each viewer's own thread does the writing.
 */
class MjpegStreamerTest {

    private val streamers = mutableListOf<MjpegStreamer>()
    private val threads = mutableListOf<Thread>()

    @After
    fun tearDown() {
        streamers.forEach { it.stop() }
        threads.forEach { it.join(JOIN_MS) }
    }

    private fun streamer(
        stallTimeoutMs: Long = MjpegStreamer.STALL_TIMEOUT_MS,
        idleEvictMs: Long = MjpegStreamer.IDLE_EVICT_MS
    ): MjpegStreamer =
        MjpegStreamer(maxFps = 1000, stallTimeoutMs = stallTimeoutMs, idleEvictMs = idleEvictMs).also { streamers.add(it) }

    /** Serve a new viewer of [streamer] over [out] on its own thread, as NanoHTTPD would. */
    private fun watch(streamer: MjpegStreamer, out: OutputStream): Pair<MjpegStreamer.MjpegClient, Thread> {
        val client = streamer.openClient()
        assertNotNull("a viewer should be accepted", client)
        val viewer = thread(name = "viewer") { client!!.serve(out) }
        threads.add(viewer)
        return client!! to viewer
    }

    private fun frame(n: Int) = "JPEG-$n".toByteArray(Charsets.US_ASCII)

    /** Push a frame, failing if the camera thread would have been held up. */
    private fun pushQuickly(streamer: MjpegStreamer, jpeg: ByteArray) {
        val start = System.nanoTime()
        streamer.pushFrame(jpeg)
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("pushFrame took $ms ms", ms < PUSH_LIMIT_MS)
        // Past the frame rate limit (1000 fps here) before the next push
        Thread.sleep(2)
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    @Test
    fun `a viewer that stops reading never holds up pushFrame or the other viewers`() {
        val streamer = streamer()
        val stuck = StuckOutputStream(passWrites = 1)
        watch(streamer, stuck)
        val sink = Sink()
        watch(streamer, sink)

        // The stuck viewer takes the first frame and blocks writing it
        pushQuickly(streamer, frame(0))
        assertTrue("the stuck viewer should be writing", stuck.blocked.await(2, TimeUnit.SECONDS))

        for (n in 1..20) {
            pushQuickly(streamer, frame(n))
            Thread.sleep(8)
        }
        assertTrue("the other viewer should get several frames", waitFor(2000) { sink.frameCount() >= 5 })
        assertEquals(2, streamer.getClientCount())
    }

    @Test
    fun `a viewer stuck for longer than the stall timeout is closed and removed`() {
        val streamer = streamer(stallTimeoutMs = 300)
        val stuck = StuckOutputStream(passWrites = 1)
        val (stuckClient, stuckThread) = watch(streamer, stuck)
        val sink = Sink()
        watch(streamer, sink)

        pushQuickly(streamer, frame(0))
        assertTrue(stuck.blocked.await(2, TimeUnit.SECONDS))

        // Frames keep coming; once the write has been stuck for 300 ms the next one closes the viewer
        var n = 1
        val closed = waitFor(3000) {
            pushQuickly(streamer, frame(n++))
            stuckClient.isClosed
        }
        assertTrue("the stuck viewer should be closed", closed)
        stuckThread.join(JOIN_MS)
        assertFalse("its thread should be free again", stuckThread.isAlive)
        assertTrue("its connection should be closed", stuck.isClosed)
        assertEquals(1, streamer.getClientCount())

        // The other viewer keeps receiving
        val before = sink.frameCount()
        repeat(5) { pushQuickly(streamer, frame(n++)); Thread.sleep(8) }
        assertTrue(waitFor(2000) { sink.frameCount() > before })
    }

    @Test
    fun `the stall timeout is five seconds`() {
        assertEquals(5_000L, MjpegStreamer.STALL_TIMEOUT_MS)
    }

    @Test
    fun `a slow viewer gets the newest frame, skipping the ones in between`() {
        val streamer = streamer()
        val gate = StuckOutputStream(passWrites = 1)
        val (_, viewer) = watch(streamer, gate)

        pushQuickly(streamer, frame(1))
        assertTrue(gate.blocked.await(2, TimeUnit.SECONDS))
        for (n in 2..5) pushQuickly(streamer, frame(n))
        gate.release()

        assertTrue("the newest frame should follow", waitFor(2000) { gate.text().contains("JPEG-5") })
        streamer.stop()
        viewer.join(JOIN_MS)
        val text = gate.text()
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Type: multipart/x-mixed-replace; boundary=frame\r\n"))
        assertTrue(text.contains("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: 6\r\n\r\nJPEG-1\r\n"))
        for (skipped in 2..4) assertFalse("frame $skipped should be skipped", text.contains("JPEG-$skipped"))
    }

    @Test
    fun `the ninth viewer is turned away until one leaves`() {
        val streamer = streamer()
        val responses = List(MjpegStreamer.MAX_VIEWERS) { streamer.createStreamResponse() }
        responses.forEach { assertEquals(Status.OK, it.status) }
        assertEquals(8, streamer.getClientCount())

        val ninth = streamer.createStreamResponse()
        assertEquals(Status.SERVICE_UNAVAILABLE, ninth.status)
        val body = ninth.data.readBytes().toString(Charsets.UTF_8)
        assertEquals("Too many preview viewers", JSONObject(body).getString("error"))

        // NanoHTTPD closes each response when it is done with it: that viewer's place is free
        responses.first().close()
        assertEquals(7, streamer.getClientCount())
        assertEquals(Status.OK, streamer.createStreamResponse().status)
    }

    @Test
    fun `viewers that left while the camera sent nothing make room for new ones`() {
        val streamer = streamer(idleEvictMs = 500)
        // Eight viewers came and left with no frame written: nothing has told the streamer they are gone
        val gone = List(MjpegStreamer.MAX_VIEWERS) { checkNotNull(streamer.openClient()) { "a viewer should be accepted" } }
        assertNull("none has been idle long enough to give up its place", streamer.openClient())

        Thread.sleep(700)
        assertNotNull("the viewer idle longest should make room", streamer.openClient())
        assertEquals("only one should go", 1, gone.count { it.isClosed })
        assertTrue("the one there longest goes", gone.first().isClosed)
        assertEquals(MjpegStreamer.MAX_VIEWERS, streamer.getClientCount())
        assertEquals(Status.OK, streamer.createStreamResponse().status)
    }

    @Test
    fun `a viewer that keeps getting frames keeps its place at the cap`() {
        val streamer = streamer(idleEvictMs = 300)
        // The one watching came first, so it has been there longest
        val sink = Sink()
        val (watching, _) = watch(streamer, sink)
        val gone = List(MjpegStreamer.MAX_VIEWERS - 1) { checkNotNull(streamer.openClient()) { "a viewer should be accepted" } }

        // Frames flow for longer than the idle time: the viewers that left take none, the one watching takes them
        var n = 0
        val until = System.currentTimeMillis() + 500
        while (System.currentTimeMillis() < until) {
            pushQuickly(streamer, frame(n++))
            Thread.sleep(20)
        }
        assertTrue("the viewer watching should get frames", waitFor(2000) { sink.frameCount() > 0 })

        assertNotNull("a viewer that left should make room", streamer.openClient())
        assertFalse("the viewer watching keeps its place", watching.isClosed)
        assertEquals(1, gone.count { it.isClosed })
    }

    @Test
    fun `the idle time that gives up a place at the cap is three seconds`() {
        assertEquals(3_000L, MjpegStreamer.IDLE_EVICT_MS)
    }

    @Test
    fun `stop ends every viewer`() {
        val streamer = streamer()
        val sinks = List(3) { Sink() }
        val viewers = sinks.map { watch(streamer, it).second }
        pushQuickly(streamer, frame(1))
        assertTrue(waitFor(2000) { sinks.all { it.frameCount() == 1 } })

        streamer.stop()
        viewers.forEach { it.join(JOIN_MS) }
        viewers.forEach { assertFalse("a viewer's thread should end", it.isAlive) }
        assertEquals(0, streamer.getClientCount())
        assertEquals(Status.SERVICE_UNAVAILABLE, streamer.createStreamResponse().status)
    }

    /**
     * A connection whose viewer stops reading: the first [passWrites] writes go
     * through, the next one blocks until [release] or [close] (close fails it,
     * as closing a socket does).
     */
    private class StuckOutputStream(private val passWrites: Int) : OutputStream() {
        val blocked = CountDownLatch(1)
        private val opened = CountDownLatch(1)
        private val received = ByteArrayOutputStream()
        private var writes = 0

        @Volatile
        var isClosed = false
            private set

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (writes++ >= passWrites && opened.count > 0) {
                blocked.countDown()
                // Bounded, so a broken streamer fails the test instead of hanging it
                opened.await(STUCK_MS, TimeUnit.MILLISECONDS)
            }
            if (isClosed) throw IOException("Connection closed")
            synchronized(received) { received.write(b, off, len) }
        }

        fun release() = opened.countDown()

        override fun close() {
            isClosed = true
            opened.countDown()
        }

        fun text(): String = synchronized(received) { received.toString(Charsets.ISO_8859_1.name()) }
    }

    /** A viewer that reads everything. */
    private class Sink : OutputStream() {
        private val received = ByteArrayOutputStream()

        override fun write(b: Int) = synchronized(received) { received.write(b) }

        override fun write(b: ByteArray, off: Int, len: Int) = synchronized(received) { received.write(b, off, len) }

        fun frameCount(): Int = synchronized(received) {
            Regex("Content-Type: image/jpeg").findAll(received.toString(Charsets.ISO_8859_1.name())).count()
        }
    }

    private companion object {
        const val PUSH_LIMIT_MS = 50L
        const val JOIN_MS = 3_000L
        const val STUCK_MS = 10_000L
    }
}
