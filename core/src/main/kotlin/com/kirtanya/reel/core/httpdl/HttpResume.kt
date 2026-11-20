package com.kirtanya.reel.core.httpdl

/** What an answer says, as the fetcher reads it. */
data class ResponseFacts(
    val code: Int,
    /** `Content-Length` of this body; null when absent (chunked). */
    val contentLength: Long?,
    val contentRange: String?,
    /** ETag, else Last-Modified. */
    val validator: String?,
    val retryAfterSec: Long? = null,
)

/** What to do with an answer to a request that asked for bytes from [HttpResume.plan]'s `from`. */
sealed interface ResumeStep {
    /** Write this body starting at [from] (0 = from the start: the server ignored the range or the file changed). */
    data class Begin(val from: Long, val total: Long, val validator: String?, val restarted: Boolean) : ResumeStep

    /** The answer cannot be used, ask again from 0 without a range. */
    data class Refetch(val why: String) : ResumeStep

    /** Everything is already here. */
    data class AlreadyDone(val total: Long) : ResumeStep

    data class Retry(val kind: FailKind, val retryAfterSec: Long?, val message: String) : ResumeStep

    data class Fail(val message: String) : ResumeStep
}

/** The resume state machine (docs/HOME_DISK.md §25.3). Pure. */
object HttpResume {
    const val NO_LENGTH = "The link did not say how big the file is"

    /** `bytes 100-999/1000` → (100, 999, 1000); the total may be `*`. */
    data class Range(val first: Long, val last: Long, val total: Long?)

    fun parseRange(h: String?): Range? {
        val m = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE).matchEntire(h?.trim() ?: return null) ?: return null
        return Range(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].toLongOrNull())
    }

    /** The total in a 416's Content-Range (the star form: bytes, star, slash, total). */
    private fun unsatisfiedTotal(h: String?): Long? =
        Regex("""bytes\s+\*/(\d+)""", RegexOption.IGNORE_CASE).matchEntire(h?.trim() ?: return null)?.groupValues?.get(1)?.toLongOrNull()

    /**
     * @param from where this request asked to start (0 = no range)
     * @param savedTotal the total learned earlier (0 = unknown)
     * @param savedValidator the validator saved with the progress
     */
    fun plan(from: Long, savedTotal: Long, savedValidator: String?, r: ResponseFacts): ResumeStep {
        val changed = savedValidator != null && r.validator != null && savedValidator != r.validator
        return when (r.code) {
            206 -> {
                val range = parseRange(r.contentRange)
                    ?: return ResumeStep.Refetch("206 without a usable Content-Range")
                if (range.first != from) return ResumeStep.Refetch("the server started at ${range.first}, not $from")
                if (changed) return ResumeStep.Refetch("the file changed on the server")
                val total = range.total ?: r.contentLength?.let { from + it } ?: return ResumeStep.Fail(NO_LENGTH)
                ResumeStep.Begin(from, total, r.validator ?: savedValidator, restarted = false)
            }
            200 -> {
                val total = r.contentLength ?: return ResumeStep.Fail(NO_LENGTH)
                // A 200 to a ranged request sends the whole file again: write it from 0.
                ResumeStep.Begin(0, total, r.validator, restarted = from > 0)
            }
            416 -> {
                val total = unsatisfiedTotal(r.contentRange) ?: savedTotal.takeIf { it > 0 }
                when {
                    total != null && from == total -> ResumeStep.AlreadyDone(total)
                    from > 0 -> ResumeStep.Refetch("range not satisfiable")
                    else -> ResumeStep.Fail("The server refused the download")
                }
            }
            else -> when (RetryPolicy.classify(r.code)) {
                FailKind.HTTP_BUSY -> ResumeStep.Retry(FailKind.HTTP_BUSY, r.retryAfterSec, "The server is busy (${r.code})")
                else -> ResumeStep.Fail(RetryPolicy.fatalMessage(r.code))
            }
        }
    }
}
