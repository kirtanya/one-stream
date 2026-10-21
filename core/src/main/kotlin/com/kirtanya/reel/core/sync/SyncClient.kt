package com.kirtanya.reel.core.sync

import com.kirtanya.reel.core.sources.FetchRequest
import com.kirtanya.reel.core.sources.HttpFetcher
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

class SyncException(message: String, val needsPairing: Boolean = false) : Exception(message)

/** What the phone keeps about a paired TV. */
data class TvCredentials(val tvId: String, val phoneId: String, val secret: String)

/** Phone side: talks to a TV's [SyncServer] at `http://host:port`. */
class SyncClient(private val http: HttpFetcher) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun hello(base: String, phoneId: String? = null, challenge: String? = null): HelloResponse {
        val query = if (phoneId != null && challenge != null) "?phone=${enc(phoneId)}&challenge=${enc(challenge)}" else ""
        return json.decodeFromString(HelloResponse.serializer(), call(base, "/sync/v2/hello$query", "GET", null, emptyMap()))
    }

    /** Pairs with the code shown on the TV; returns the credentials to keep (the only time the secret travels). */
    suspend fun pair(base: String, code: String, device: String): PairResponse =
        json.decodeFromString(PairResponse.serializer(), call(base, "/sync/v2/pair", "POST", json.encodeToString(PairRequest.serializer(), PairRequest(code, device)), emptyMap()))

    /**
     * Checks the TV at [base] really is the paired one (it must answer our random challenge with
     * the shared secret), then sends [bundle] encrypted. Nothing is sent to an impostor.
     */
    suspend fun push(base: String, creds: TvCredentials, bundle: SourcesBundle): ApplyResult {
        val challenge = SyncCrypto.randomHex(16)
        val hello = hello(base, creds.phoneId, challenge)
        val expected = SyncCrypto.hmac(creds.secret, "tv:$challenge")
        if (hello.tvId != creds.tvId || hello.proof == null || !SyncCrypto.same(hello.proof, expected)) {
            if (hello.tvId == creds.tvId) throw SyncException("This TV doesn't know this phone any more. Pair again.", needsPairing = true)
            throw SyncException("That isn't your paired TV")
        }
        val body = SyncCrypto.encrypt(creds.secret, json.encodeToString(SourcesBundle.serializer(), bundle).toByteArray(), hello.nonce)
        val reply = call(base, "/sync/v2/sources", "POST", body, mapOf("X-Reel-Phone" to creds.phoneId, "X-Reel-Nonce" to hello.nonce))
        val plain = runCatching { SyncCrypto.decrypt(creds.secret, reply, "reply:${hello.nonce}") }.getOrElse {
            throw SyncException("That isn't your paired TV")
        }
        return json.decodeFromString(ApplyResult.serializer(), plain.decodeToString())
    }

    /**
     * Sends one home request (protocol v3, docs/HOME_DISK.md §9.1) after the same identity check as [push].
     * A TV older than v3 can't take it: [SyncException] "Update One Stream on the TV".
     */
    suspend fun rpc(base: String, creds: TvCredentials, request: RpcRequest): RpcReply {
        val hello = verifiedHello(base, creds)
        if (!HomeRpc.supported(hello)) throw SyncException(HomeRpc.UPDATE_TV)
        val body = SyncCrypto.encrypt(creds.secret, json.encodeToString(RpcRequest.serializer(), request).toByteArray(), hello.nonce)
        val reply = call(base, "/sync/v3/rpc", "POST", body, mapOf("X-Reel-Phone" to creds.phoneId, "X-Reel-Nonce" to hello.nonce))
        val plain = runCatching { SyncCrypto.decrypt(creds.secret, reply, "reply:${hello.nonce}") }.getOrElse {
            throw SyncException("That isn't your paired TV")
        }
        return json.decodeFromString(RpcReply.serializer(), plain.decodeToString())
    }

    /** The TV at [base] answers our random challenge with the shared secret (so it is the paired one): its hello. */
    suspend fun verifiedHello(base: String, creds: TvCredentials): HelloResponse {
        val challenge = SyncCrypto.randomHex(16)
        val hello = hello(base, creds.phoneId, challenge)
        val expected = SyncCrypto.hmac(creds.secret, "tv:$challenge")
        if (hello.tvId != creds.tvId || hello.proof == null || !SyncCrypto.same(hello.proof, expected)) {
            if (hello.tvId == creds.tvId) throw SyncException("This TV doesn't know this phone any more. Pair again.", needsPairing = true)
            throw SyncException("That isn't your paired TV")
        }
        return hello
    }

    private suspend fun call(base: String, path: String, method: String, body: String?, extra: Map<String, String>): String {
        val headers = mapOf("Content-Type" to "application/json") + extra
        val r = try {
            http.fetch(FetchRequest(base.trimEnd('/') + path, method, headers, body?.toByteArray()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SyncException("Couldn't reach the TV. Is Reel open on it?")
        }
        return when (r.status) {
            200 -> r.text
            401 -> throw SyncException("This TV doesn't know this phone any more. Pair again.", needsPairing = true)
            403 -> throw SyncException(r.text.ifBlank { "Wrong code" })
            else -> throw SyncException("The TV answered ${r.status}")
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}
