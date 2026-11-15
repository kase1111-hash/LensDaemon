package com.lensdaemon.web

import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * NanoHTTPD gzips text and JSON whenever the client accepts gzip, and holds a
 * gzipped event stream's frames in the compressor, so the director's live
 * events never reached the dashboard. WebServer keeps event streams out.
 */
class GzipRuleTest {

    private var server: NanoHTTPD? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    @Test
    fun `event streams are never gzipped`() {
        assertFalse(WebServer.allowsGzip("text/event-stream"))
        assertFalse(WebServer.allowsGzip("text/event-stream; charset=UTF-8"))
        assertFalse(WebServer.allowsGzip("Text/Event-Stream"))
    }

    @Test
    fun `other responses may be`() {
        assertTrue(WebServer.allowsGzip("application/json"))
        assertTrue(WebServer.allowsGzip("text/html"))
        assertTrue(WebServer.allowsGzip("text/css"))
        assertTrue(WebServer.allowsGzip(null))
    }

    @Test
    fun `NanoHTTPD with the rule sends an event stream as it is, and still gzips JSON`() {
        val events = "data: {\"type\":\"state\"}\n\n"
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = if (session.uri == "/events") {
                newChunkedResponse(Response.Status.OK, "text/event-stream", ByteArrayInputStream(events.toByteArray()))
            } else {
                newFixedLengthResponse(Response.Status.OK, WebServer.MIME_JSON, "{\"ok\": true}")
            }

            // As WebServer does
            override fun useGzipWhenAccepted(r: Response): Boolean = WebServer.allowsGzip(r.mimeType) && super.useGzipWhenAccepted(r)
        }.apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        val port = server!!.listeningPort

        val sse = get("http://127.0.0.1:$port/events")
        assertNull("no Content-Encoding on the event stream", sse.getHeaderField("Content-Encoding"))
        assertEquals("data: {\"type\":\"state\"}", sse.inputStream.bufferedReader().readLine())

        val json = get("http://127.0.0.1:$port/status")
        assertEquals("gzip", json.getHeaderField("Content-Encoding"))
        json.inputStream.use { it.readBytes() }
    }

    private fun get(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        setRequestProperty("Accept-Encoding", "gzip")
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
    }

    private companion object {
        const val TIMEOUT_MS = 5_000
    }
}
