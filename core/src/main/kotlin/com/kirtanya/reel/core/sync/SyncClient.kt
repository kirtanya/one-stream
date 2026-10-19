package com.kirtanya.reel.core.sync

import com.kirtanya.reel.core.sources.FetchRequest
import com.kirtanya.reel.core.sources.HttpFetcher
import kotlinx.serialization.json.Json

class SyncException(message: String) : Exception(message)

/** Phone side: talks to a TV's [SyncServer] at `http://host:port`. */
class SyncClient(private val http: HttpFetcher) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun hello(base: String): HelloResponse =
        json.decodeFromString(HelloResponse.serializer(), call(base, "/sync/v1/hello", "GET", null, null))

    /** Pairs with the 4-digit [code] shown on the TV; returns the token to keep. */
    suspend fun pair(base: String, code: String, device: String): PairResponse =
        json.decodeFromString(PairResponse.serializer(), call(base, "/sync/v1/pair", "POST", json.encodeToString(PairRequest.serializer(), PairRequest(code, device)), null))

    /** Sends this phone's sources; the TV makes itself match and says what changed. */
    suspend fun push(base: String, token: String, bundle: SourcesBundle): String =
        json.decodeFromString(PushResponse.serializer(), call(base, "/sync/v1/sources", "POST", json.encodeToString(SourcesBundle.serializer(), bundle), token)).summary

    private suspend fun call(base: String, path: String, method: String, body: String?, token: String?): String {
        val headers = buildMap {
            put("Content-Type", "application/json")
            if (token != null) put("Authorization", "Bearer $token")
        }
        val r = try {
            http.fetch(FetchRequest(base.trimEnd('/') + path, method, headers, body?.toByteArray()))
        } catch (e: Exception) {
            throw SyncException("Couldn't reach the TV. Is Reel open on it?")
        }
        return when (r.status) {
            200 -> r.text
            401 -> throw SyncException("This TV doesn't know this phone any more. Pair again.")
            403 -> throw SyncException(r.text.ifBlank { "Wrong code" })
            else -> throw SyncException("The TV answered ${r.status}")
        }
    }
}
