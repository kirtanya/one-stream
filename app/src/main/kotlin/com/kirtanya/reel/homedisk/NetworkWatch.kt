package com.kirtanya.reel.homedisk

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.kirtanya.reel.core.homedisk.LinkFacts
import com.kirtanya.reel.core.homedisk.NetworkFingerprint
import com.kirtanya.reel.core.homedisk.NetworkNow
import com.kirtanya.reel.core.homedisk.Transport
import java.net.Inet4Address
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The network that really carries the traffic (docs/HOME_DISK.md §5.2): with Tailscale on, the default
 * network is a VPN, so the rules look at the network underneath it. Needs only ACCESS_NETWORK_STATE.
 */
class NetworkWatch(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _now = MutableStateFlow(NetworkNow.NONE)
    val now: StateFlow<NetworkNow> = _now.asStateFlow()
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        refresh()
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { refresh() }
                override fun onLost(network: Network) { refresh() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { refresh() }
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { refresh() }
            })
        }
    }

    fun refresh(): NetworkNow = read().also { _now.value = it }

    private fun read(): NetworkNow {
        val active = cm?.activeNetwork ?: return NetworkNow.NONE
        val caps = cm.getNetworkCapabilities(active) ?: return NetworkNow.NONE
        val (net, netCaps) = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) underlying() ?: return NetworkNow.NONE else active to caps
        val transport = when {
            netCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            netCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            netCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            else -> Transport.OTHER
        }
        val lp = cm.getLinkProperties(net)
        return NetworkNow(
            transport,
            metered = !netCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            fingerprint = lp?.let { NetworkFingerprint.of(facts(it)) },
        )
    }

    /** The non-VPN network with internet under the VPN, Wi-Fi / Ethernet first. */
    @Suppress("DEPRECATION")
    private fun underlying(): Pair<Network, NetworkCapabilities>? =
        cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
            .filter { (_, c) -> !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
            .sortedBy { (_, c) -> if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) 0 else 1 }
            .firstOrNull()

    /** Our own IPv4 address on the current network (the TV puts it in its hello). */
    fun lanAddress(): String? {
        val net = cm?.activeNetwork ?: return null
        return cm.getLinkProperties(net)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && NetworkFingerprint.isPrivateLan(it.hostAddress) }?.hostAddress
    }

    companion object {
        fun facts(lp: LinkProperties): LinkFacts {
            val v4 = lp.linkAddresses.firstOrNull { it.address is Inet4Address }
            return LinkFacts(
                gateways = lp.routes.filter { it.isDefaultRoute && it.gateway is Inet4Address }.mapNotNull { it.gateway?.hostAddress },
                dnsServers = lp.dnsServers.mapNotNull { it.hostAddress },
                ipv4Prefix = v4?.let { NetworkFingerprint.ipv4Prefix(it.address.hostAddress ?: "", it.prefixLength) },
                privateDnsServerName = lp.privateDnsServerName,
                domains = lp.domains,
            )
        }

        /** The device's Tailscale address (100.64/10), when the VPN is up: listed in the TV's hello (§9.1). */
        fun tailnetAddress(): String? = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && NetworkFingerprint.isTailnet(it.hostAddress) }?.hostAddress
        }.getOrNull()
    }
}
