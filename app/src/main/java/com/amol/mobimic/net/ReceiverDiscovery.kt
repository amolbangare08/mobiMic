package com.amol.mobimic.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Finds receivers advertising themselves on the LAN over mDNS.
 *
 * Typing an IP address is the single most annoying step in setting this up, and it
 * is also the step most likely to be wrong after a DHCP lease changes. The
 * receiver registers `_mobimic._udp`; this browses for it.
 */
class ReceiverDiscovery(context: Context) {

    data class Found(val name: String, val host: String, val port: Int)

    private val nsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _found = MutableStateFlow<List<Found>>(emptyList())
    val found: StateFlow<List<Found>> = _found.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Resolution is asynchronous and can fail; only resolved services
                // ever reach the UI.
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "resolve failed for ${info.serviceName}: $errorCode")
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val address = info.host?.hostAddress ?: return
                        val entry = Found(info.serviceName, address, info.port)
                        _found.value = (_found.value.filterNot { it.host == entry.host } + entry)
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _found.value = _found.value.filterNot { it.name == serviceInfo.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "discovery start failed: $errorCode")
                listener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                listener = null
            }
        }
        listener = discoveryListener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stop() {
        listener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        listener = null
        _found.value = emptyList()
    }

    private companion object {
        const val TAG = "mobiMic"
        const val SERVICE_TYPE = "_mobimic._udp."
    }
}
