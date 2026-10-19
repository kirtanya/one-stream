package com.kirtanya.reel.core.sync

import fi.iki.elonen.NanoHTTPD
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the receiving device (the TV) plugs into the sync server. */
interface SyncHost {
    val deviceName: String

    /** Current sources, so the server can work out what changes. */
    fun currentBundle(): SourcesBundle

    /** Makes this device match [bundle]; returns a short summary ("+2 addons"). */
    suspend fun apply(bundle: SourcesBundle): String

    /** Paired senders: token → device name. Persisted by the host. */
    fun pairedTokens(): Map<String, String>
    fun savePairedToken(token: String, device: String)
}

@Serializable data class HelloResponse(val name: String, val protocol: Int = SyncProtocol.VERSION)
@Serializable data class PairRequest(val code: String, val device: String)
@Serializable data class PairResponse(val token: String, val name: String)
@Serializable data class PushResponse(val summary: String)

object SyncProtocol {
    const val VERSION = 1
    const val SERVICE_TYPE = "_reel._tcp."
    const val DEFAULT_PORT = 47821
    const val MAX_BODY = 256 * 1024
}

/**
 * Runs on the TV. A phone pairs once with the 4-digit [code] shown in the TV's Settings and
 * gets a token; afterwards it can push its sources with that token. Five wrong codes within a
 * minute lock pairing for a minute and change the code.
 */
class SyncServer(
    private val host: SyncHost,
    port: Int = SyncProtocol.DEFAULT_PORT,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) : NanoHTTPD(port) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var failures = ArrayDeque<Long>()
    private var lockedUntil = 0L

    /** Shown on the TV; changes after every successful pairing and after a lock-out. */
    @Volatile var code: String = newCode()
        private set

    /** Called when the code changes or a phone pairs / pushes (for the TV's Settings screen). */
    var onEvent: (String) -> Unit = {}

    override fun serve(session: IHTTPSession): Response = try {
        when {
            session.method == Method.GET && session.uri == "/sync/v1/hello" ->
                ok(json.encodeToString(HelloResponse.serializer(), HelloResponse(host.deviceName)))
            session.method == Method.POST && session.uri == "/sync/v1/pair" -> pair(body(session))
            session.method == Method.POST && session.uri == "/sync/v1/sources" -> push(session)
            else -> text(Response.Status.NOT_FOUND, "not found")
        }
    } catch (e: TooLarge) {
        text(Response.Status.BAD_REQUEST, "too large")
    } catch (e: Exception) {
        text(Response.Status.BAD_REQUEST, e.message ?: "bad request")
    }

    private fun pair(body: String): Response {
        val req = json.decodeFromString(PairRequest.serializer(), body)
        synchronized(lock) {
            val now = clock()
            if (now < lockedUntil) return text(Response.Status.FORBIDDEN, "Too many wrong codes. Try again in a minute.")
            if (req.code.trim() != code) {
                failures.addLast(now)
                while (failures.isNotEmpty() && now - failures.first() > 60_000) failures.removeFirst()
                if (failures.size >= 5) {
                    lockedUntil = now + 60_000
                    failures.clear()
                    code = newCode()
                    onEvent("code")
                }
                return text(Response.Status.FORBIDDEN, "Wrong code")
            }
            val token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            host.savePairedToken(token, req.device.take(60))
            code = newCode()
            onEvent("paired:${req.device}")
            return ok(json.encodeToString(PairResponse.serializer(), PairResponse(token, host.deviceName)))
        }
    }

    private fun push(session: IHTTPSession): Response {
        val token = session.headers["authorization"]?.removePrefix("Bearer ")?.trim()
        val device = token?.let { host.pairedTokens()[it] } ?: return text(Response.Status.UNAUTHORIZED, "Not paired")
        val bundle = json.decodeFromString(SourcesBundle.serializer(), body(session))
        val summary = runBlocking { host.apply(bundle.copy(from = device)) }
        onEvent("synced:$device:$summary")
        return ok(json.encodeToString(PushResponse.serializer(), PushResponse(summary)))
    }

    private class TooLarge : Exception()

    private fun body(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (length > SyncProtocol.MAX_BODY) throw TooLarge()
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = session.inputStream.read(buf, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun newCode() = (1000 + random.nextInt(9000)).toString()
    private fun ok(body: String) = newFixedLengthResponse(Response.Status.OK, "application/json", body)
    private fun text(status: Response.Status, msg: String) = newFixedLengthResponse(status, MIME_PLAINTEXT, msg)
}
