package com.kirtanya.reel.core.sync

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A phone the TV has paired with: its id, name and the secret they share. */
@Serializable
data class PairedPhone(val id: String, val name: String, val secret: String)

/** What the receiving device (the TV) plugs into the sync server. */
interface SyncHost {
    val deviceName: String
    /** Stable random id for this TV, so a phone can tell it apart from impostors. */
    val tvId: String

    /** Makes this device match [bundle]; never runs twice at once. */
    suspend fun apply(bundle: SourcesBundle): ApplyResult

    fun pairedPhones(): List<PairedPhone>
    fun savePairedPhone(phone: PairedPhone)

    /** v3: the TV's own addresses (LAN, Tailscale 100.x) for the hello (docs/HOME_DISK.md §9.1). */
    fun addresses(): List<String> = emptyList()

    /** v3: the home disk at a glance, for the hello. */
    fun homeSummary(): HomeSummary? = null

    /** v3: one home request from a paired phone (already decrypted); see [HomeAgent]. */
    suspend fun rpc(phone: PairedPhone, request: RpcRequest): RpcReply = HomeRpc.error(request.id, "Unknown operation ${request.op.take(40)}")
}

@Serializable data class ApplyResult(val summary: String, val failed: Int = 0)

@Serializable data class HelloResponse(
    val name: String,
    val tvId: String,
    /** Single-use nonce the phone must encrypt its next push against. */
    val nonce: String,
    /** HMAC(secret, "tv:" + phone's challenge) when the phone is paired, proving this is the paired TV. */
    val proof: String? = null,
    val protocol: Int = SyncProtocol.VERSION,
    /** v3: the TV's LAN and Tailscale addresses. */
    val addrs: List<String> = emptyList(),
    /** v3: disk state, free bytes, active jobs. */
    val home: HomeSummary? = null,
)
@Serializable data class PairRequest(val code: String, val device: String)
@Serializable data class PairResponse(val tvId: String, val name: String, val phoneId: String, val secret: String)

object SyncProtocol {
    /** 3 = home RPC (docs/HOME_DISK.md §9); v2 phones keep working (same hello, pair and sources routes). */
    const val VERSION = 3
    const val SERVICE_TYPE = "_reel._tcp."
    const val DEFAULT_PORT = 47821
    const val MAX_BODY = 256 * 1024
    const val CODE_LENGTH = 6
    const val PAIRING_WINDOW_MS = 3 * 60_000L
    const val WRONG_CODES_ALLOWED = 3
    const val NONCE_TTL_MS = 2 * 60_000L

    /** Peers the TV's servers answer: private LAN, link-local, loopback, Tailscale 100.64/10 (docs/HOME_DISK.md §9.1). */
    fun allowedPeer(address: String?): Boolean = com.kirtanya.reel.core.homedisk.NetworkFingerprint.isPrivateOrTailnet(address)
}

/**
 * Runs on the TV. Pairing only works while the TV shows its code ([openPairing], up to 3 minutes):
 * a 6-digit code, and 3 wrong codes close the window. A paired phone gets a secret once; after that
 * it checks the TV's identity (HMAC proof) and sends sources encrypted to a fresh single-use nonce.
 * Connections are handled by a small fixed pool; bodies are capped.
 */
class SyncServer(
    private val host: SyncHost,
    port: Int = SyncProtocol.DEFAULT_PORT,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) : NanoHTTPD(port) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val nonces = ConcurrentHashMap<String, Long>()

    @Volatile private var pairingUntil = 0L
    @Volatile private var wrongCodes = 0

    /** The code shown on the TV while pairing is open; empty when closed. */
    @Volatile var code: String = ""
        private set

    /** Called on pairing/push events and when the pairing window opens or closes (for the TV's Settings screen). */
    var onEvent: (String) -> Unit = {}

    init {
        // At most 4 connections handled at once; extra sockets are closed instead of spawning threads.
        setAsyncRunner(BoundedRunner(4))
    }

    val pairingOpen: Boolean get() = clock() < pairingUntil && code.isNotEmpty()

    /** The TV's Phone & TV screen is showing: accept pairing for a few minutes with a fresh code. */
    fun openPairing() = synchronized(lock) {
        code = (0 until SyncProtocol.CODE_LENGTH).joinToString("") { random.nextInt(10).toString() }
        wrongCodes = 0
        pairingUntil = clock() + SyncProtocol.PAIRING_WINDOW_MS
        onEvent("pairing:open")
    }

    fun closePairing() = synchronized(lock) {
        code = ""
        pairingUntil = 0
        onEvent("pairing:closed")
    }

    override fun serve(session: IHTTPSession): Response = try {
        when {
            // Defence in depth (§9.1): only the home LAN, loopback and the tailnet; nothing is forwarded on the router anyway.
            !SyncProtocol.allowedPeer(session.remoteIpAddress) -> text(Response.Status.FORBIDDEN, "no")
            session.method == Method.GET && session.uri == "/sync/v2/hello" -> hello(session)
            session.method == Method.POST && session.uri == "/sync/v2/pair" -> pair(body(session))
            session.method == Method.POST && session.uri == "/sync/v2/sources" -> push(session)
            session.method == Method.POST && session.uri == "/sync/v3/rpc" -> rpc(session)
            else -> text(Response.Status.NOT_FOUND, "not found")
        }
    } catch (e: TooLarge) {
        text(Response.Status.BAD_REQUEST, "too large")
    } catch (e: Exception) {
        text(Response.Status.BAD_REQUEST, "bad request")
    }

    private fun hello(session: IHTTPSession): Response {
        val nonce = SyncCrypto.randomHex(16)
        val now = clock()
        nonces.entries.removeIf { now - it.value > SyncProtocol.NONCE_TTL_MS }
        if (nonces.size < 64) nonces[nonce] = now
        val phone = session.parameters["phone"]?.firstOrNull()?.let { id -> host.pairedPhones().firstOrNull { it.id == id } }
        val challenge = session.parameters["challenge"]?.firstOrNull()?.take(64)
        val proof = if (phone != null && challenge != null) SyncCrypto.hmac(phone.secret, "tv:$challenge") else null
        val hello = HelloResponse(host.deviceName, host.tvId, nonce, proof, addrs = runCatching { host.addresses() }.getOrDefault(emptyList()), home = runCatching { host.homeSummary() }.getOrNull())
        return ok(json.encodeToString(HelloResponse.serializer(), hello))
    }

    private fun pair(body: String): Response {
        val req = json.decodeFromString(PairRequest.serializer(), body)
        synchronized(lock) {
            if (!pairingOpen) return text(Response.Status.FORBIDDEN, "Pairing is closed. Open Settings → Phone & TV on the TV.")
            if (!SyncCrypto.same(req.code.trim(), code)) {
                wrongCodes++
                if (wrongCodes >= SyncProtocol.WRONG_CODES_ALLOWED) {
                    code = ""
                    pairingUntil = 0
                    onEvent("pairing:closed")
                    return text(Response.Status.FORBIDDEN, "Too many wrong codes. Reopen Phone & TV on the TV for a new code.")
                }
                return text(Response.Status.FORBIDDEN, "Wrong code")
            }
            val phone = PairedPhone(id = SyncCrypto.randomHex(8), name = req.device.take(60), secret = SyncCrypto.randomHex(32))
            host.savePairedPhone(phone)
            code = ""
            pairingUntil = 0
            onEvent("paired:${phone.name}")
            return ok(json.encodeToString(PairResponse.serializer(), PairResponse(host.tvId, host.deviceName, phone.id, phone.secret)))
        }
    }

    /** A paired phone's encrypted body, checked against a fresh single-use nonce; or the refusal to send. */
    private fun opened(session: IHTTPSession): Triple<PairedPhone, String, ByteArray>? {
        val phone = session.headers["x-reel-phone"]?.let { id -> host.pairedPhones().firstOrNull { it.id == id } } ?: return null
        val nonce = session.headers["x-reel-nonce"].orEmpty()
        val issued = nonces.remove(nonce) ?: return null
        if (clock() - issued > SyncProtocol.NONCE_TTL_MS) return null
        val plain = try {
            SyncCrypto.decrypt(phone.secret, body(session), nonce)
        } catch (e: TooLarge) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return Triple(phone, nonce, plain)
    }

    private fun rpc(session: IHTTPSession): Response {
        val (phone, nonce, plain) = opened(session) ?: return text(Response.Status.UNAUTHORIZED, "Not paired")
        val request = json.decodeFromString(RpcRequest.serializer(), plain.decodeToString())
        val reply = try {
            runBlocking { host.rpc(phone, request) }
        } catch (e: Exception) {
            HomeRpc.error(request.id, "The TV couldn't do that")
        }
        onEvent("rpc:${phone.name}:${request.op}")
        return ok(SyncCrypto.encrypt(phone.secret, json.encodeToString(RpcReply.serializer(), reply).toByteArray(), "reply:$nonce"))
    }

    private fun push(session: IHTTPSession): Response {
        val (phone, _, plain) = opened(session) ?: return text(Response.Status.UNAUTHORIZED, "Not paired")
        val nonce = session.headers["x-reel-nonce"].orEmpty()
        val bundle = json.decodeFromString(SourcesBundle.serializer(), plain.decodeToString())
        val result = runBlocking { host.apply(bundle.copy(from = phone.name)) }
        onEvent("synced:${phone.name}:${result.summary}")
        val reply = json.encodeToString(ApplyResult.serializer(), result)
        return ok(SyncCrypto.encrypt(phone.secret, reply.toByteArray(), "reply:$nonce"))
    }

    private class TooLarge : Exception()

    /** Reads at most [SyncProtocol.MAX_BODY] bytes, as they arrive (never pre-allocates from Content-Length). */
    private fun body(session: IHTTPSession): String {
        val declared = session.headers["content-length"]?.toLongOrNull() ?: 0
        if (declared > SyncProtocol.MAX_BODY) throw TooLarge()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var left = declared
        while (left > 0) {
            val n = session.inputStream.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun ok(body: String) = newFixedLengthResponse(Response.Status.OK, "application/json", body)
    private fun text(status: Response.Status, msg: String) = newFixedLengthResponse(status, MIME_PLAINTEXT, msg)

    /** Fixed pool; when full, the new connection is closed straight away. */
    private class BoundedRunner(size: Int) : AsyncRunner {
        private val pool = Executors.newFixedThreadPool(size) { r -> Thread(r, "reel-sync").apply { isDaemon = true } }
        private val slots = Semaphore(size)
        private val running = ConcurrentHashMap.newKeySet<ClientHandler>()

        override fun exec(code: ClientHandler) {
            if (!slots.tryAcquire()) {
                code.close()
                return
            }
            running += code
            pool.execute {
                try {
                    code.run()
                } finally {
                    running -= code
                    slots.release()
                }
            }
        }

        override fun closed(code: ClientHandler) = Unit
        override fun closeAll() {
            running.forEach { it.close() }
        }
    }
}
