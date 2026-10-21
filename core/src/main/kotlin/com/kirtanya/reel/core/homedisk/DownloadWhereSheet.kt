package com.kirtanya.reel.core.homedisk

/** One row of the Download-where sheet. */
data class WhereRow(val where: DownloadWhere, val title: String, val body: String, val status: String?, val enabled: Boolean, val warn: Boolean = false)

/**
 * The phone's "Download where?" sheet (docs/HOME_DISK.md §17.3): what each row says and whether it can be
 * picked, from the facts the app knows.
 */
object DownloadWhereSheet {
    fun rows(homeFilledIn: Boolean, tvPaired: Boolean, tvReachable: Boolean, copyPhoneDownloads: Boolean, phoneVerdict: P2pVerdict): List<WhereRow> {
        val atHome = when {
            !homeFilledIn -> WhereRow(DownloadWhere.AT_HOME, "At home", BODY_HOME, "Set up home storage first", enabled = false, warn = true)
            !tvPaired -> WhereRow(DownloadWhere.AT_HOME, "At home", BODY_HOME, "Pair your TV first (Settings → Phone & TV)", enabled = false, warn = true)
            tvReachable -> WhereRow(DownloadWhere.AT_HOME, "At home", BODY_HOME, "TV ready", enabled = true)
            else -> WhereRow(DownloadWhere.AT_HOME, "At home", BODY_HOME, "Starts when your TV is reachable", enabled = true)
        }
        val body = if (homeFilledIn && copyPhoneDownloads) "Copied to the home disk when you're home." else "Saved on this phone."
        val status = when (val v = phoneVerdict) {
            P2pVerdict.Allow -> null
            is P2pVerdict.Wait -> when (v.reason) {
                WaitReason.MOBILE_DATA -> "Waits for Wi-Fi (you're on mobile data)"
                WaitReason.NO_NETWORK -> "Waits for a connection"
                WaitReason.WORK_NETWORK -> "Not on this network (work Wi-Fi)"
                WaitReason.UNKNOWN_WIFI -> "Waits for home Wi-Fi (this is not an allowed network)"
            }
            is P2pVerdict.Ask -> "Asks first: this Wi-Fi is new"
            is P2pVerdict.Block -> "Not on this network"
        }
        val onPhone = WhereRow(DownloadWhere.ON_PHONE, "On this phone", body, status, enabled = true, warn = status != null)
        return listOf(atHome, onPhone)
    }

    const val BODY_HOME = "Your TV downloads it to the home disk. Works from anywhere."

    /** The small line under Download once a choice is remembered ("At home · change"). */
    fun rememberedLine(where: DownloadWhere): String? = if (where == DownloadWhere.ASK) null else "${where.label} · change"
}
