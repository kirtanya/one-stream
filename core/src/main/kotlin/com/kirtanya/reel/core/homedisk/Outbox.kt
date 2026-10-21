package com.kirtanya.reel.core.homedisk

import com.kirtanya.reel.core.sync.RpcRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A request the TV hasn't acknowledged yet. Its [request] id is the idempotency key (sent unchanged on every retry). */
@Serializable
data class OutboxItem(
    val request: RpcRequest,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastAttemptAt: Long = 0,
    val lastError: String? = null,
)

/**
 * The phone's outbox (docs/HOME_DISK.md §9.3), `files/home/outbox.json`: what to send to the TV, when to try
 * again (30 s doubling to 30 min) and when to give up (7 days: "Couldn't reach your TV for a week").
 */
object Outbox {
    const val FIRST_RETRY_MS = 30_000L
    const val MAX_RETRY_MS = 30 * 60_000L
    const val GIVE_UP_MS = 7 * 86_400_000L
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val codec = ListSerializer(OutboxItem.serializer())

    /** Adds [request] unless one with the same id is already waiting. */
    fun add(items: List<OutboxItem>, request: RpcRequest, now: Long): List<OutboxItem> =
        if (items.any { it.request.id == request.id }) items else items + OutboxItem(request, now)

    fun retryAfterMs(attempts: Int): Long =
        if (attempts <= 0) 0 else minOf(MAX_RETRY_MS, FIRST_RETRY_MS shl minOf(attempts - 1, 20))

    /** Items to send now, oldest first. [force] (app start, network change, Downloads opened) skips the backoff. */
    fun due(items: List<OutboxItem>, now: Long, force: Boolean = false): List<OutboxItem> =
        items.filter { !expired(it, now) && (force || now - it.lastAttemptAt >= retryAfterMs(it.attempts)) }.sortedBy { it.createdAt }

    fun expired(item: OutboxItem, now: Long) = now - item.createdAt >= GIVE_UP_MS

    /** The TV answered [id] (with anything, even a refusal): it leaves the outbox. */
    fun acknowledged(items: List<OutboxItem>, id: String): List<OutboxItem> = items.filterNot { it.request.id == id }

    fun failed(items: List<OutboxItem>, id: String, now: Long, error: String): List<OutboxItem> =
        items.map { if (it.request.id == id) it.copy(attempts = it.attempts + 1, lastAttemptAt = now, lastError = error) else it }

    fun encode(items: List<OutboxItem>): String = json.encodeToString(codec, items)
    fun decode(text: String?): List<OutboxItem> = text?.let { runCatching { json.decodeFromString(codec, it) }.getOrNull() }.orEmpty()
}
