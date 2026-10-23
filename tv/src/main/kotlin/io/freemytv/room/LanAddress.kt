package io.freemytv.room

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** The TV's own private IPv4 address, and the check that a caller is on the same kind of network. */
object LanAddress {
    /** Wi-Fi or Ethernet first; null when the TV has no private IPv4 address (then nothing is served). */
    fun find(): String? {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull() ?: return null
        val candidates = interfaces
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .sortedBy { rank(it.name) }
        for (nic in candidates) {
            for (address in nic.inetAddresses) {
                if (address is Inet4Address && isPrivate(address)) return address.hostAddress
            }
        }
        return null
    }

    private fun rank(name: String): Int = when {
        name.startsWith("wlan") -> 0
        name.startsWith("eth") -> 1
        name.startsWith("en") -> 2
        else -> 3
    }

    /** RFC 1918, link-local and loopback (adb forward reaches us from 127.0.0.1). Anything else is refused. */
    fun isPrivate(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val plain = host.removePrefix("::ffff:").substringBefore('%')
        val address = runCatching { InetAddress.getByName(plain) }.getOrNull() ?: return false
        return isPrivate(address)
    }

    fun isPrivate(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        if (address is Inet4Address) {
            val b = address.address
            val first = b[0].toInt() and 0xFF
            val second = b[1].toInt() and 0xFF
            // Carrier-grade NAT (100.64/10), which some ISP routers hand out on the LAN side.
            return first == 100 && second in 64..127
        }
        return false
    }
}
