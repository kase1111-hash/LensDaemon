package com.lensdaemon.web

import fi.iki.elonen.NanoHTTPD
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException

/**
 * Reads the JSON body of an API request.
 *
 * The body is read here rather than through NanoHTTPD's parseBody, which hands
 * a PUT body over as a temporary file instead of text (so PUT endpoints saw no
 * body at all) and decodes a POST body as US-ASCII whenever the Content-Type
 * names no charset, as browsers' "application/json" doesn't (so any non-ASCII
 * text arrived mangled). JSON is UTF-8 unless the request says otherwise.
 */
object RequestBody {

    private const val TAG = "RequestBody"

    /** Bodies above this are discarded: API requests are small JSON objects. */
    const val MAX_BODY_BYTES = 1_048_576

    private val CHARSET_PARAM = Regex("""charset\s*=\s*"?([^";\s]+)"?""", RegexOption.IGNORE_CASE)

    /** The body as a JSON object, or null when there is none or it is not a JSON object. */
    fun readJson(session: NanoHTTPD.IHTTPSession): JSONObject? {
        val text = readText(session)?.takeIf { it.isNotBlank() } ?: return null
        return try {
            JSONObject(text)
        } catch (e: JSONException) {
            Timber.tag(TAG).w(e, "Request body is not a JSON object")
            null
        }
    }

    /**
     * The body text, or null when there is none. Reads exactly Content-Length
     * bytes, so a kept-alive connection stays positioned at the next request.
     */
    fun readText(session: NanoHTTPD.IHTTPSession): String? {
        val length = session.headers["content-length"]?.trim()?.toLongOrNull() ?: return null
        if (length <= 0) return null
        return try {
            if (length > MAX_BODY_BYTES) {
                Timber.tag(TAG).w("Ignoring a $length-byte request body")
                discard(session.inputStream, length)
                return null
            }
            val bytes = readFully(session.inputStream, length.toInt())
            String(bytes, charsetOf(session.headers["content-type"]))
        } catch (e: IOException) {
            Timber.tag(TAG).w(e, "Could not read the request body")
            null
        }
    }

    /** The charset a Content-Type header names, UTF-8 when it names none or one this platform lacks. */
    fun charsetOf(contentType: String?): Charset {
        val name = contentType?.let { CHARSET_PARAM.find(it)?.groupValues?.get(1) } ?: return Charsets.UTF_8
        return try {
            Charset.forName(name)
        } catch (e: IllegalCharsetNameException) {
            Timber.tag(TAG).w(e, "Unknown charset %s, reading UTF-8", name)
            Charsets.UTF_8
        } catch (e: UnsupportedCharsetException) {
            Timber.tag(TAG).w(e, "Unsupported charset %s, reading UTF-8", name)
            Charsets.UTF_8
        }
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) break
            read += n
        }
        return if (read == length) bytes else bytes.copyOf(read)
    }

    private fun discard(input: InputStream, length: Long) {
        var remaining = length
        val buffer = ByteArray(DISCARD_BUFFER_BYTES)
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
    }

    private const val DISCARD_BUFFER_BYTES = 8192
}
