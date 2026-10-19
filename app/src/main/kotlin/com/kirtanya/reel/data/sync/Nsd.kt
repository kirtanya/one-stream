package com.kirtanya.reel.data.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.kirtanya.reel.core.sync.SyncProtocol
import java.net.Inet4Address
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** A Reel TV found on the home network. */
data class FoundTv(val serviceName: String, val name: String, val host: String, val port: Int) {
    val base: String get() = "http://$host:$port"
}

/** Network Service Discovery (mDNS) for `_reel._tcp`: the TV announces, the phone finds. */
class Nsd(private val context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()

    /** TV: announce "Reel · <name>" on [port]. Returns a function that stops announcing. */
    fun announce(name: String, port: Int): () -> Unit {
        val info = NsdServiceInfo().apply {
            serviceName = "Reel · $name".take(60)
            serviceType = SyncProtocol.SERVICE_TYPE
            setPort(port)
            setAttribute("name", name.take(60))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(s: NsdServiceInfo) { Log.i(TAG, "announced ${s.serviceName} on $port") }
            override fun onRegistrationFailed(s: NsdServiceInfo, code: Int) { Log.w(TAG, "announce failed $code") }
            override fun onServiceUnregistered(s: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(s: NsdServiceInfo, code: Int) = Unit
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        return { runCatching { nsd.unregisterService(listener) } }
    }

    /** Phone: looks for Reel TVs for [timeoutMs] and returns what answered (resolved to an IPv4 address). */
    suspend fun discover(timeoutMs: Long = 6_000): List<FoundTv> {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val multicast = wifi?.createMulticastLock("reel-sync")?.apply { setReferenceCounted(false); acquire() }
        val found = mutableListOf<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onServiceFound(s: NsdServiceInfo) { synchronized(found) { found += s } }
            override fun onServiceLost(s: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { Log.w(TAG, "discovery failed $code") }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
        }
        try {
            nsd.discoverServices(SyncProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            delay(timeoutMs)
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
            multicast?.release()
        }
        return synchronized(found) { found.distinctBy { it.serviceName } }.mapNotNull { resolve(it) }
    }

    private suspend fun resolve(service: NsdServiceInfo): FoundTv? {
        val done = CompletableDeferred<NsdServiceInfo?>()
        if (Build.VERSION.SDK_INT >= 34) {
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(code: Int) { done.complete(null) }
                override fun onServiceUpdated(s: NsdServiceInfo) { done.complete(s) }
                override fun onServiceLost() = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            nsd.registerServiceInfoCallback(service, executor, cb)
            val s = withTimeoutOrNull(4_000) { done.await() }
            runCatching { nsd.unregisterServiceInfoCallback(cb) }
            return s?.toFoundTv()
        }
        @Suppress("DEPRECATION")
        nsd.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(s: NsdServiceInfo, code: Int) { done.complete(null) }
            override fun onServiceResolved(s: NsdServiceInfo) { done.complete(s) }
        })
        return withTimeoutOrNull(4_000) { done.await() }?.toFoundTv()
    }

    private fun NsdServiceInfo.toFoundTv(): FoundTv? {
        val addresses = if (Build.VERSION.SDK_INT >= 34) hostAddresses else listOfNotNull(@Suppress("DEPRECATION") host)
        val address = addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull() ?: return null
        val name = attributes["name"]?.toString(Charsets.UTF_8) ?: serviceName.removePrefix("Reel · ")
        return FoundTv(serviceName, name, address.hostAddress ?: return null, port)
    }

    companion object {
        private const val TAG = "ReelNsd"
    }
}
