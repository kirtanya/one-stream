package com.kirtanya.reel.tvremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.core.content.edit
import com.kirtanya.reel.core.tvremote.Pb
import com.kirtanya.reel.core.tvremote.RemoteKeys
import com.kirtanya.reel.core.tvremote.RemoteLink
import com.kirtanya.reel.core.tvremote.RemotePairing
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import javax.security.auth.x500.X500Principal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A TV found on the network, or the paired one. */
data class RemoteTv(val name: String, val host: String)

/**
 * The phone as the TV's remote, over the TV's own Android TV Remote protocol v2 (what Google TV /
 * Google Home use): pair once (the TV shows a 6-character code, port 6467), then key presses and app
 * links (port 6466). Works while the TV is in standby, which is how "Send to TV" wakes it. Ported
 * from One Launcher's remote (same protocol, proven on the user's TV); the TV's certificate is
 * pinned after pairing, so another device at that address can't pose as the TV.
 */
class TvRemote(private val context: Context) : RemoteLink {
    private val prefs = context.getSharedPreferences("tv_remote", Context.MODE_PRIVATE)
    private val lock = Mutex()
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var lastServerCert: X509Certificate? = null
    @Volatile private var active: CompletableDeferred<Unit>? = null
    private var pairSocket: SSLSocket? = null
    private var pairHost: String? = null

    private val _power = MutableStateFlow<Boolean?>(null)
    override val power: StateFlow<Boolean?> = _power.asStateFlow()

    /** The paired TV, or null (Settings → Phone & TV → TV remote). */
    val paired: RemoteTv?
        get() = prefs.getString("host", null)?.takeIf { prefs.contains("pin:$it") }?.let { RemoteTv(prefs.getString("name", null) ?: "TV", it) }

    private val _pairedFlow = MutableStateFlow(paired)
    val pairedFlow: StateFlow<RemoteTv?> = _pairedFlow.asStateFlow()

    // ---- discovery

    /** TVs announcing the remote service on this Wi-Fi, collected for [ms]. */
    suspend fun discover(ms: Long = 3_500): List<RemoteTv> {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val found = linkedMapOf<String, RemoteTv>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        val ip = (if (Build.VERSION.SDK_INT >= 34) r.hostAddresses.firstOrNull() else @Suppress("DEPRECATION") r.host)?.hostAddress ?: return
                        synchronized(found) { found[ip] = RemoteTv(r.serviceName, ip) }
                    }
                    override fun onResolveFailed(r: NsdServiceInfo, code: Int) {}
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { return emptyList() }
        delay(ms)
        runCatching { nsd.stopServiceDiscovery(listener) }
        return synchronized(found) { found.values.toList() }
    }

    // ---- pairing

    /** Asks [tv] to show a pairing code. */
    suspend fun pairStart(tv: RemoteTv): Unit = withContext(Dispatchers.IO) {
        pairSocket?.runCatching { close() }
        val s = open(tv.host, PAIR_PORT)
        pairSocket = s
        pairHost = tv.host
        prefs.edit { putString("pendingName", tv.name) }
        val o = s.outputStream
        val i = s.inputStream
        fun outer(build: Pb.() -> Unit) = Pb().apply { int(1, 2); int(2, 200); build() }
        Pb.send(o, outer { msg(10, Pb().str(1, "atvremote").str(2, "One Stream (${Build.MODEL})")) })
        expectOk(Pb.read(i))
        Pb.send(o, outer { msg(20, Pb().msg(1, Pb().int(1, 3).int(2, 6)).int(3, 1)) })
        expectOk(Pb.read(i))
        Pb.send(o, outer { msg(30, Pb().msg(1, Pb().int(1, 3).int(2, 6)).int(2, 1)) })
        expectOk(Pb.read(i))
    }

    /** Finishes pairing with the code on the TV screen; remembers the TV and pins its certificate. */
    suspend fun pairFinish(code: String): Unit = withContext(Dispatchers.IO) {
        val s = pairSocket ?: throw IllegalStateException("Start pairing first")
        val server = s.session.peerCertificates[0] as X509Certificate
        val secret = RemotePairing.secret(code, clientCert().publicKey as RSAPublicKey, server.publicKey as RSAPublicKey)
            ?: throw IllegalArgumentException("That code doesn't match the TV")
        Pb.send(s.outputStream, Pb().apply { int(1, 2); int(2, 200); msg(40, Pb().bytes(1, secret)) })
        expectOk(Pb.read(s.inputStream))
        val host = pairHost!!
        prefs.edit {
            putString("pin:$host", fingerprint(server))
            putString("host", host)
            putString("name", prefs.getString("pendingName", null) ?: "TV")
        }
        _pairedFlow.value = paired
        runCatching { s.close() }
        pairSocket = null
    }

    fun forget() {
        disconnect()
        val host = prefs.getString("host", null)
        prefs.edit { remove("host"); remove("name"); if (host != null) remove("pin:$host") }
        _pairedFlow.value = null
    }

    // ---- remote session

    override suspend fun connect(): Boolean = lock.withLock {
        if (socket?.isClosed == false && active?.isCompleted == true) return@withLock true
        val tv = paired ?: return@withLock false
        withContext(Dispatchers.IO) {
            runCatching {
                disconnect()
                val s = open(tv.host, REMOTE_PORT)
                val cert = lastServerCert
                if (cert == null || fingerprint(cert) != prefs.getString("pin:${tv.host}", null)) {
                    s.close()
                    throw IllegalStateException("This isn't the TV you paired with")
                }
                val ready = CompletableDeferred<Unit>()
                active = ready
                socket = s
                out = s.outputStream
                Thread({ readLoop(s) }, "tv-remote").start()
                // The TV makes us its remote (configure → set active) before it takes keys or links.
                withTimeoutOrNull(4_000) { ready.await() } != null
            }.onFailure { Log.w(TAG, "connect failed: ${it.message}") }.getOrDefault(false)
        }
    }

    override suspend fun key(code: Int): Boolean = write(Pb().msg(10, Pb().int(1, code.toLong()).int(2, RemoteKeys.SHORT.toLong())))

    override suspend fun appLink(link: String): Boolean = write(Pb().msg(90, Pb().str(1, link)))

    fun disconnect() {
        runCatching { socket?.close() }
        socket = null
        out = null
        _power.value = null
    }

    private suspend fun write(m: Pb): Boolean = withContext(Dispatchers.IO) {
        runCatching { synchronized(this@TvRemote) { Pb.send(out ?: error("not connected"), m) } }.isSuccess
    }

    private fun readLoop(s: SSLSocket) {
        val input = s.inputStream
        try {
            while (!s.isClosed) {
                val m = Pb.read(input)
                when {
                    m.containsKey(1) -> { // configure → who we are and what we can do
                        val info = Pb().str(1, Build.MODEL).str(2, Build.MANUFACTURER).int(3, 1).str(4, "1").str(5, "atvremote").str(6, "1.0.0")
                        synchronized(this) { Pb.send(s.outputStream, Pb().msg(1, Pb().int(1, FEATURES.toLong()).msg(2, info))) }
                    }
                    m.containsKey(2) -> { // set active
                        synchronized(this) { Pb.send(s.outputStream, Pb().msg(2, Pb().int(1, FEATURES.toLong()))) }
                        active?.complete(Unit)
                    }
                    m.containsKey(8) -> { // ping → pong
                        val ping = Pb.parse(m[8]!!.first() as ByteArray)
                        synchronized(this) { Pb.send(s.outputStream, Pb().msg(9, Pb().int(1, (ping[1]?.firstOrNull() as? Long) ?: 0))) }
                    }
                    m.containsKey(40) -> { // screen on / off
                        val st = Pb.parse(m[40]!!.first() as ByteArray)
                        _power.value = ((st[1]?.firstOrNull() as? Long) ?: 0) != 0L
                    }
                }
            }
        } catch (_: Exception) {
        }
        if (socket === s) {
            socket = null
            out = null
            _power.value = null
        }
    }

    // ---- TLS with our keystore certificate

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKey() {
        if (keyStore().containsAlias(ALIAS)) return
        val now = System.currentTimeMillis()
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        kpg.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=atvremote, O=One Stream"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(Date(now - 86_400_000L))
                .setCertificateNotAfter(Date(now + 20L * 365 * 86_400_000L))
                .build(),
        )
        kpg.generateKeyPair()
    }

    private fun clientCert(): X509Certificate = keyStore().getCertificate(ALIAS) as X509Certificate

    private fun open(host: String, port: Int): SSLSocket {
        ensureKey()
        val ks = keyStore()
        val keys = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?) = arrayOf(ks.getCertificate(ALIAS) as X509Certificate)
            override fun getPrivateKey(alias: String?) = ks.getKey(ALIAS, null) as PrivateKey
        }
        // The TV's certificate is self-signed: accepted here, then checked against the pin.
        val trust = object : X509ExtendedTrustManager() {
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?, socket: Socket?) { lastServerCert = chain[0] }
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?, engine: SSLEngine?) { lastServerCert = chain[0] }
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) { lastServerCert = chain[0] }
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(arrayOf(keys), arrayOf(trust), null) }
        val raw = Socket().apply { connect(InetSocketAddress(host, port), 4000); soTimeout = 0 }
        val s = ssl.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        s.startHandshake()
        return s
    }

    private fun fingerprint(c: X509Certificate) = MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString("") { "%02x".format(it) }

    private fun expectOk(m: Map<Int, List<Any>>) {
        val status = (m[2]?.firstOrNull() as? Long)?.toInt() ?: 200
        if (status != 200) throw IllegalStateException(if (status == 402) "That code doesn't match the TV" else "The TV said no ($status)")
    }

    private companion object {
        const val TAG = "ReelRemote"
        const val ALIAS = "one_stream_tv_remote"
        const val PAIR_PORT = 6467
        const val REMOTE_PORT = 6466
        const val SERVICE = "_androidtvremote2._tcp."
        const val FEATURES = 622 // ping, key, IME, power, volume, app links
    }
}
