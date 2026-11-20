package com.kirtanya.reel.core.httpdl

/** What a failed attempt means (docs/HOME_DISK.md §25.3). Pure. */
enum class FailKind { HTTP_BUSY, NETWORK, FATAL }

object RetryPolicy {
    const val BASE_MS = 2_000L
    const val CAP_MS = 5 * 60_000L
    const val RETRY_AFTER_CAP_MS = 10 * 60_000L

    /** An HTTP answer that says "later" is tried this many times; a lost network (no answer at all) much longer. */
    const val MAX_HTTP = 6
    const val MAX_NETWORK = 40

    /** 429 / 408 / 425 / any 5xx: the server may be fine in a moment. Everything else is final. */
    fun classify(code: Int): FailKind = when {
        code == 408 || code == 425 || code == 429 || code in 500..599 -> FailKind.HTTP_BUSY
        else -> FailKind.FATAL
    }

    /** The plain line for a final answer. */
    fun fatalMessage(code: Int): String = when (code) {
        401, 403 -> "The link was refused or has expired. Pick the source again"
        404, 410 -> "The link is gone. Pick another source"
        else -> "The server answered $code"
    }

    /** Wait before try number [attempt] (1 = the first retry): doubling, capped, and the server's `Retry-After` when it asks for longer. */
    fun delayMs(attempt: Int, retryAfterSec: Long? = null): Long {
        val shift = (attempt - 1).coerceIn(0, 20)
        val backoff = minOf(CAP_MS, BASE_MS shl shift)
        val asked = retryAfterSec?.takeIf { it > 0 }?.let { minOf(RETRY_AFTER_CAP_MS, it * 1000) } ?: 0L
        return maxOf(backoff, asked)
    }

    fun giveUp(kind: FailKind, attempt: Int): Boolean = when (kind) {
        FailKind.HTTP_BUSY -> attempt > MAX_HTTP
        FailKind.NETWORK -> attempt > MAX_NETWORK
        FailKind.FATAL -> true
    }

    /** The try counter after an attempt: bytes that arrived prove the link works, so it starts over. */
    fun nextAttempt(previous: Int, madeProgress: Boolean) = if (madeProgress) 1 else previous + 1

    /** `Retry-After` is seconds (an HTTP date is ignored: debrid services send seconds). */
    fun parseRetryAfter(header: String?): Long? = header?.trim()?.toLongOrNull()?.takeIf { it in 0..86_400 }
}
