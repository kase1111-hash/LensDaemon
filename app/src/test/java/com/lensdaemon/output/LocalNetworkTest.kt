package com.lensdaemon.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

/**
 * Unit tests for [LocalNetwork.pickLanAddress], the address shown in stream URLs.
 */
class LocalNetworkTest {

    private fun ip(text: String): InetAddress = InetAddress.getByName(text)

    @Test
    fun `WiFi wins over a cellular interface listed first`() {
        val picked = LocalNetwork.pickLanAddress(
            listOf(
                "rmnet_data0" to ip("10.142.7.19"),
                "wlan0" to ip("192.168.1.50")
            )
        )
        assertEquals("192.168.1.50", picked?.hostAddress)
    }

    @Test
    fun `hotspot and USB tethering addresses count as LAN`() {
        assertEquals("192.168.43.1", LocalNetwork.pickLanAddress(listOf("ap0" to ip("192.168.43.1")))?.hostAddress)
        assertEquals("192.168.42.129", LocalNetwork.pickLanAddress(listOf("rndis0" to ip("192.168.42.129")))?.hostAddress)
    }

    @Test
    fun `IPv6, loopback and link-local addresses are skipped`() {
        val picked = LocalNetwork.pickLanAddress(
            listOf(
                "wlan0" to ip("fe80::1"),
                "lo" to ip("127.0.0.1"),
                "wlan0" to ip("169.254.10.10"),
                "wlan0" to ip("10.0.0.23")
            )
        )
        assertEquals("10.0.0.23", picked?.hostAddress)
    }

    @Test
    fun `an unknown private interface beats cellular`() {
        val picked = LocalNetwork.pickLanAddress(
            listOf(
                "ccmni1" to ip("10.20.30.40"),
                "tun0" to ip("172.16.5.2")
            )
        )
        assertEquals("172.16.5.2", picked?.hostAddress)
    }

    @Test
    fun `cellular is used only when nothing else exists`() {
        assertEquals("10.142.7.19", LocalNetwork.pickLanAddress(listOf("rmnet_data0" to ip("10.142.7.19")))?.hostAddress)
        assertNull(LocalNetwork.pickLanAddress(emptyList()))
    }
}
