package com.amol.mobimic.net

import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * What the phone can currently send over.
 *
 * USB tethering is worth singling out because it changes the numbers that matter:
 * essentially no jitter and no contention, so the receiver's jitter buffer - which
 * dominates end-to-end latency over Wi-Fi - can shrink dramatically.
 */
object NetworkLinks {

    private const val TAG = "mobiMic"

    /** Android names the USB tether interface one of these, depending on the vendor. */
    private val USB_PREFIXES = listOf("rndis", "usb", "ncm")

    enum class Kind { USB, WIFI, OTHER }

    data class Link(
        val interfaceName: String,
        val address: Inet4Address,
        val broadcast: InetAddress?,
        val kind: Kind,
    ) {
        val isUsb: Boolean get() = kind == Kind.USB
        val hostAddress: String get() = address.hostAddress ?: ""
    }

    /**
     * Every usable IPv4 link, USB first.
     *
     * The ordering is the policy: when both a cable and Wi-Fi are up, the cable is
     * the better path and should be tried first.
     */
    fun list(): List<Link> {
        val links = mutableListOf<Link>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return emptyList()

        for (nif in interfaces) {
            if (!runCatching { nif.isUp }.getOrDefault(false)) continue
            if (runCatching { nif.isLoopback }.getOrDefault(true)) continue

            val name = nif.name.lowercase()
            val kind = when {
                USB_PREFIXES.any { name.startsWith(it) } -> Kind.USB
                name.startsWith("wlan") -> Kind.WIFI
                else -> Kind.OTHER
            }

            for (ia in nif.interfaceAddresses) {
                val address = ia.address as? Inet4Address ?: continue
                if (address.isLoopbackAddress) continue
                links += Link(nif.name, address, ia.broadcast, kind)
            }
        }

        links.sortBy { if (it.isUsb) 0 else if (it.kind == Kind.WIFI) 1 else 2 }
        if (links.isNotEmpty()) {
            Log.i(TAG, "links: " + links.joinToString { "${it.interfaceName}=${it.hostAddress}(${it.kind})" })
        }
        return links
    }

    fun usbLink(): Link? = list().firstOrNull { it.isUsb }
}
