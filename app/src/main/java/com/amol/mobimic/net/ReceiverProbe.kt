package com.amol.mobimic.net

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Finds the PC by asking, on every interface at once.
 *
 * mDNS is the conventional answer, but it is unreliable over a USB tether - the
 * link comes up and down with the cable, and responders are slow to notice. A
 * single broadcast probe per interface is smaller, faster, and works identically
 * on Wi-Fi and USB, which is the whole point here.
 *
 * Protocol, deliberately tiny:
 *   probe  "MMICPROB" + version(1)
 *   reply  "MMICHERE" + version(1) + audio port (u16 LE)
 */
object ReceiverProbe {

    private const val TAG = "mobiMic"
    private const val PROBE_MAGIC = "MMICPROB"
    private const val REPLY_MAGIC = "MMICHERE"
    private const val VERSION: Byte = 1

    /** The receiver listens for probes here; audio still goes to the port in the reply. */
    const val DISCOVERY_PORT = 47002

    data class Found(
        val host: String,
        val port: Int,
        val viaInterface: String,
        val overUsb: Boolean,
    )

    /**
     * Broadcasts on every link and collects replies until [timeoutMs] elapses.
     * Results are ordered with USB links first, matching [NetworkLinks.list].
     */
    fun discover(timeoutMs: Int = 1200): List<Found> {
        val links = NetworkLinks.list()
        if (links.isEmpty()) return emptyList()

        val found = linkedMapOf<String, Found>()

        for (link in links) {
            val broadcast = link.broadcast ?: continue
            try {
                DatagramSocket(null).use { socket ->
                    socket.reuseAddress = true
                    socket.broadcast = true
                    // Bind to this link's own address so the probe leaves by the
                    // interface we mean, not by whatever the routing table prefers.
                    socket.bind(InetSocketAddress(link.address, 0))
                    socket.soTimeout = timeoutMs

                    val probe = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
                    probe.put(PROBE_MAGIC.toByteArray(Charsets.US_ASCII))
                    probe.put(VERSION)
                    socket.send(DatagramPacket(probe.array(), probe.capacity(), broadcast, DISCOVERY_PORT))

                    val deadline = System.currentTimeMillis() + timeoutMs
                    val buffer = ByteArray(64)
                    while (System.currentTimeMillis() < deadline) {
                        val reply = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(reply)
                        } catch (_: IOException) {
                            break // timed out; nothing more is coming on this link
                        }
                        parseReply(reply, link)?.let { found.putIfAbsent(it.host, it) }
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "probe failed on ${link.interfaceName}: ${e.message}")
            }
        }

        Log.i(TAG, "discovery found ${found.size}: " + found.values.joinToString {
            "${it.host}:${it.port} via ${it.viaInterface}${if (it.overUsb) " (USB)" else ""}"
        })
        return found.values.toList()
    }

    private fun parseReply(packet: DatagramPacket, link: NetworkLinks.Link): Found? {
        if (packet.length < 11) return null
        val magic = String(packet.data, packet.offset, 8, Charsets.US_ASCII)
        if (magic != REPLY_MAGIC) return null

        val buffer = ByteBuffer.wrap(packet.data, packet.offset + 8, packet.length - 8)
            .order(ByteOrder.LITTLE_ENDIAN)
        val version = buffer.get()
        if (version != VERSION) return null
        val port = buffer.short.toInt() and 0xFFFF

        val host = (packet.address as? InetAddress)?.hostAddress ?: return null
        return Found(host = host, port = port, viaInterface = link.interfaceName, overUsb = link.isUsb)
    }
}
