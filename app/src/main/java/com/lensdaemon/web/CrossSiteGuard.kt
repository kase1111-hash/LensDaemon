package com.lensdaemon.web

import fi.iki.elonen.NanoHTTPD
import java.util.Locale

/**
 * Refuses cross-site writes to the API (CSRF).
 *
 * A web page on another site, opened by anyone on the phone's network, can send
 * the API "simple" requests: a text/plain POST needs no CORS preflight, and
 * request bodies are read as JSON whatever their Content-Type. Without this
 * check such a page could turn kiosk mode off, change the exit PIN, push the
 * camera to an internet host or replace the storage settings.
 *
 * Browsers name the page's origin in the Origin header of every POST, PUT and
 * DELETE. A write whose Origin is not this server, as the Host header names it,
 * is refused; the bundled dashboard and setup wizard are served by the phone,
 * so their requests match. Clients that send no Origin (curl, scripts, apps)
 * are not a browser acting for another site, and pass.
 *
 * This does not stop DNS rebinding, where another site's name resolves to the
 * phone and Origin and Host then agree; nor does it replace authentication.
 */
internal object CrossSiteGuard {

    /** Why a request is refused; the API answers 403 {"error": REFUSAL}. */
    const val REFUSAL = "Cross-site request refused"

    private val WRITE_METHODS = setOf(NanoHTTPD.Method.POST, NanoHTTPD.Method.PUT, NanoHTTPD.Method.DELETE)

    /** Default ports of the schemes a page of this server can have. */
    private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443)

    private const val MAX_PORT = 65_535

    /** A host and the port it was reached on; null when the text named no port. */
    private data class HostPort(val host: String, val port: Int?)

    /**
     * Why the request must be refused, or null when it may go ahead: always
     * null for reads and for writes that carry no Origin header.
     *
     * @param headers the request headers, with lower-case names as NanoHTTPD gives them
     */
    fun refusal(method: NanoHTTPD.Method, headers: Map<String, String>): String? {
        if (method !in WRITE_METHODS) return null
        val origin = headers["origin"] ?: return null
        return if (sameOrigin(origin, headers["host"])) null else REFUSAL
    }

    /**
     * True when [origin] ("scheme://host[:port]") names the server that [host]
     * (the Host header, "host[:port]") does: the same host, ignoring case, IPv6
     * literals in brackets, and the same port. A port left out is the default
     * port of the origin's scheme on both sides (RFC 6454, RFC 7230), so
     * "http://phone" matches "phone" and "phone:80". The opaque origin "null",
     * schemes other than http and https, and anything unreadable never match.
     */
    fun sameOrigin(origin: String, host: String?): Boolean {
        val text = origin.trim().lowercase(Locale.US)
        val separator = text.indexOf("://")
        if (separator <= 0 || host == null) return false
        val defaultPort = DEFAULT_PORTS[text.substring(0, separator)] ?: return false
        val from = parseHostPort(text.substring(separator + "://".length).substringBefore('/')) ?: return false
        val to = parseHostPort(host.trim().lowercase(Locale.US)) ?: return false
        return from.host == to.host && (from.port ?: defaultPort) == (to.port ?: defaultPort)
    }

    /**
     * "host", "host:port", "[v6]" or "[v6]:port", already lower-case; null when
     * it is none of these (a bare IPv6 address, user info, a bad port).
     */
    private fun parseHostPort(text: String): HostPort? {
        if (text.contains('@')) return null
        // An IPv6 literal keeps its brackets; any other host ends at the first ':'
        val hostEnd = if (text.startsWith("[")) {
            text.indexOf(']') + 1
        } else {
            text.indexOf(':').let { if (it < 0) text.length else it }
        }
        val host = text.substring(0, hostEnd)
        val rest = text.substring(hostEnd)
        val port = when {
            rest.isEmpty() -> null
            rest.startsWith(":") -> rest.substring(1).toIntOrNull()?.takeIf { it in 1..MAX_PORT } ?: return null
            else -> return null
        }
        return if (host.isEmpty()) null else HostPort(host, port)
    }
}
