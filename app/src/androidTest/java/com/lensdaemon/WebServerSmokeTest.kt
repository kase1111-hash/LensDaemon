package com.lensdaemon

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lensdaemon.web.ApiRoutes
import com.lensdaemon.web.MjpegStreamer
import com.lensdaemon.web.WebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

/**
 * Smoke test for the embedded web server.
 * Verifies the server starts, serves the dashboard, and responds to API calls.
 */
@RunWith(AndroidJUnit4::class)
class WebServerSmokeTest {

    private lateinit var webServer: WebServer
    private val testPort = 18080

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        webServer = WebServer(context, testPort)
        webServer.setApiRoutes(ApiRoutes(context))
        webServer.setMjpegStreamer(MjpegStreamer())
    }

    @After
    fun tearDown() {
        webServer.stopServer()
    }

    @Test
    fun serverStartsAndStops() {
        val started = webServer.startServer()
        assertTrue("Web server should start successfully", started)
        assertTrue("Web server should report running", webServer.isRunning())

        webServer.stopServer()
        assertFalse("Web server should report stopped", webServer.isRunning())
    }

    @Test
    fun dashboardServesHtml() {
        webServer.startServer()

        val connection = URL("http://localhost:$testPort/").openConnection() as HttpURLConnection
        try {
            assertEquals(200, connection.responseCode)
            val contentType = connection.contentType
            assertTrue("Should serve HTML", contentType.contains("text/html"))
            val body = connection.inputStream.bufferedReader().readText()
            assertTrue("Dashboard should contain LensDaemon", body.contains("LensDaemon"))
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun statusApiReturnsJson() {
        webServer.startServer()

        val connection = URL("http://localhost:$testPort/api/status").openConnection() as HttpURLConnection
        try {
            assertEquals(200, connection.responseCode)
            val body = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            assertEquals("ok", json.getString("status"))
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun serverReportsStats() {
        webServer.startServer()

        // Make a request to generate stats
        val connection = URL("http://localhost:$testPort/api/status").openConnection() as HttpURLConnection
        connection.inputStream.bufferedReader().readText()
        connection.disconnect()

        val stats = webServer.getStats()
        assertNotNull("Stats should not be null", stats)
    }

    @Test
    fun unknownApiReturns404() {
        webServer.startServer()

        val connection = URL("http://localhost:$testPort/api/nonexistent").openConnection() as HttpURLConnection
        try {
            assertEquals(404, connection.responseCode)
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun crossSitePostIsRefused() {
        webServer.startServer()

        // A page of another site: refused before the handler runs
        val refused = post("/api/stream/stop", origin = "http://evil.example")
        assertEquals(403, refused.first)
        assertEquals("Cross-site request refused", JSONObject(refused.second).getString("error"))

        // No Origin (curl, scripts) or the phone's own page: on to the handler, which has no camera here
        assertEquals(503, post("/api/stream/stop", origin = null).first)
        assertEquals(503, post("/api/stream/stop", origin = "http://localhost:$testPort").first)
    }

    /**
     * Status code and body of a bodyless POST, sent as raw HTTP so that the
     * Origin header goes out exactly as given.
     */
    private fun post(path: String, origin: String?): Pair<Int, String> {
        Socket("localhost", testPort).use { socket ->
            socket.soTimeout = 5000
            val request = buildString {
                append("POST $path HTTP/1.1\r\n")
                append("Host: localhost:$testPort\r\n")
                if (origin != null) append("Origin: $origin\r\n")
                append("Content-Length: 0\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val status = response.substringBefore("\r\n").split(" ")[1].toInt()
            return status to response.substringAfter("\r\n\r\n")
        }
    }
}
