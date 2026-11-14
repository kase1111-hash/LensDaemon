package com.lensdaemon.web

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends real requests to a real NanoHTTPD server: POST and PUT bodies must both
 * be read, as UTF-8 when the Content-Type names no charset, and each request
 * must leave a kept-alive connection ready for the next.
 */
class RequestBodyTest {

    private val received = mutableListOf<JSONObject?>()
    private lateinit var server: NanoHTTPD

    @Before
    fun setUp() {
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val body = RequestBody.readJson(session)
                synchronized(received) { received.add(body) }
                return newFixedLengthResponse(Response.Status.OK, "application/json", "{}")
            }
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun send(method: String, body: String?, contentType: String = "application/json"): Int {
        val connection = URL("http://127.0.0.1:${server.listeningPort}/api/test").openConnection() as HttpURLConnection
        connection.requestMethod = method
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        connection.inputStream.use { it.readBytes() }
        return code
    }

    private fun last(): JSONObject? = synchronized(received) { received.last() }

    @Test
    fun `reads a POST body`() {
        assertEquals(200, send("POST", """{"enabled": true}"""))
        assertEquals(true, last()?.getBoolean("enabled"))
    }

    @Test
    fun `reads a PUT body`() {
        assertEquals(200, send("PUT", """{"bitrate": 6000000, "screen": {"mode": "OFF"}}"""))
        val body = last()
        assertNotNull("a PUT body should be read", body)
        assertEquals(6_000_000, body!!.getInt("bitrate"))
        assertEquals("OFF", body.getJSONObject("screen").getString("mode"))
    }

    @Test
    fun `reads non-ASCII text as UTF-8 when no charset is given`() {
        assertEquals(200, send("POST", """{"script": "[SCENE: Café – Señor]"}"""))
        assertEquals("[SCENE: Café – Señor]", last()?.getString("script"))
        assertEquals(200, send("PUT", """{"notes": "über"}"""))
        assertEquals("über", last()?.getString("notes"))
    }

    @Test
    fun `honours a charset the request names`() {
        val latin1 = """{"name": "Café"}"""
        val connection = URL("http://127.0.0.1:${server.listeningPort}/api/test").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=ISO-8859-1")
        connection.outputStream.use { it.write(latin1.toByteArray(Charsets.ISO_8859_1)) }
        assertEquals(200, connection.responseCode)
        connection.inputStream.use { it.readBytes() }
        assertEquals("Café", last()?.getString("name"))
    }

    @Test
    fun `requests on a kept-alive connection each get their own body`() {
        for (i in 1..5) {
            assertEquals(200, send(if (i % 2 == 0) "PUT" else "POST", """{"n": $i}"""))
        }
        val numbers = synchronized(received) { received.map { it?.getInt("n") } }
        assertEquals(listOf(1, 2, 3, 4, 5), numbers)
    }

    @Test
    fun `no body is null`() {
        assertEquals(200, send("POST", null))
        assertNull(last())
    }

    @Test
    fun `a body that is not a JSON object is null`() {
        assertEquals(200, send("POST", "not json", contentType = "text/plain"))
        assertNull(last())
    }

    @Test
    fun `charset names are read from the Content-Type`() {
        assertEquals(Charsets.UTF_8, RequestBody.charsetOf(null))
        assertEquals(Charsets.UTF_8, RequestBody.charsetOf("application/json"))
        assertEquals(Charsets.ISO_8859_1, RequestBody.charsetOf("application/json; charset=\"iso-8859-1\""))
        assertEquals(Charsets.UTF_8, RequestBody.charsetOf("application/json; charset=no-such-charset"))
    }
}
