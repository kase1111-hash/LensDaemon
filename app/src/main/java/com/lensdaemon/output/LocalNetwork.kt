package com.lensdaemon.output

import timber.log.Timber
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Finds the address other machines on the local network reach this device at,
 * for the stream and dashboard URLs shown to the user.
 *
 * Taking the first non-loopback IPv4 address is not enough on a phone: with
 * mobile data on, the cellular interface (rmnet*, ccmni*) often enumerates
 * before WiFi and carries a carrier-NAT address nobody on the LAN can reach.
 */
object LocalNetwork {

    /** Interfaces that face the local network: WiFi, Ethernet, hotspot and USB tethering. */
    private val LAN_INTERFACE_PREFIXES = listOf("wlan", "eth", "ap", "swlan", "rndis", "usb", "bt-pan")

    /** Interfaces that face the carrier, never the LAN. */
    private val CELLULAR_INTERFACE_PREFIXES = listOf("rmnet", "ccmni", "pdp", "seth", "v4-rmnet", "clat")

    /**
     * The best LAN-reachable IPv4 address of this device, or null if it has none.
     */
    fun lanIpv4Address(): String? {
        return try {
            val candidates = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { iface -> iface.inetAddresses.toList().map { iface.name to it } }
            pickLanAddress(candidates)?.hostAddress
        } catch (e: java.net.SocketException) {
            Timber.w(e, "Could not enumerate network interfaces")
            null
        }
    }

    /**
     * Pick the address to advertise from (interface name, address) pairs:
     * a private address on a LAN-facing interface first, then any private
     * address off the cellular interfaces, then any usable IPv4 address.
     */
    fun pickLanAddress(candidates: List<Pair<String, InetAddress>>): Inet4Address? {
        val ipv4 = candidates
            .filter { (_, addr) -> addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress }
            .map { (name, addr) -> name.lowercase() to addr as Inet4Address }

        fun isLan(name: String) = LAN_INTERFACE_PREFIXES.any { name.startsWith(it) }
        fun isCellular(name: String) = CELLULAR_INTERFACE_PREFIXES.any { name.startsWith(it) }

        return ipv4.firstOrNull { (name, addr) -> isLan(name) && addr.isSiteLocalAddress }?.second
            ?: ipv4.firstOrNull { (name, addr) -> !isCellular(name) && addr.isSiteLocalAddress }?.second
            ?: ipv4.firstOrNull { (name, _) -> !isCellular(name) }?.second
            ?: ipv4.firstOrNull()?.second
    }
}
