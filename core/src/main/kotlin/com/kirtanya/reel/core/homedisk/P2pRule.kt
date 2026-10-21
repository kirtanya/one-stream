package com.kirtanya.reel.core.homedisk

/** What the BitTorrent traffic is for. */
enum class P2pUse {
    /** "On this phone", Keep, Save as download, and finished downloads sharing. */
    DOWNLOAD,
    /** Play from a torrent source. */
    STREAM,
}

/** Why a phone download waits or a play is refused. */
enum class WaitReason {
    NO_NETWORK,
    /** Mobile data with "Download on this phone over mobile data" off. */
    MOBILE_DATA,
    /** A Wi-Fi the user marked "Never here (work)". */
    WORK_NETWORK,
    /** A Wi-Fi nobody has answered for yet. */
    UNKNOWN_WIFI,
}

sealed interface P2pVerdict {
    data object Allow : P2pVerdict
    /** Downloads wait (and start by themselves when an allowed network appears). */
    data class Wait(val reason: WaitReason, val canStartAnyway: Boolean = false) : P2pVerdict
    /** Unknown Wi-Fi: ask once for this network ([fingerprint]); until answered, downloads wait and plays don't start. */
    data class Ask(val fingerprint: String) : P2pVerdict
    /** Play refused here (the office): offer "Download at home instead". */
    data class Block(val reason: WaitReason) : P2pVerdict
}

/** The user's answers for Wi-Fi networks, by fingerprint. */
data class KnownNetworks(val allowed: Set<String> = emptySet(), val work: Set<String> = emptySet())

/**
 * The phone's P2P network rule (docs/HOME_DISK.md §5.3, product decisions 2026-10-21):
 *
 * | Network | Download on this phone | Play from a torrent |
 * |---|---|---|
 * | Home | allowed | allowed |
 * | Mobile data | waits for Wi-Fi unless "over mobile data" is on, or Start now anyway | allowed |
 * | Wi-Fi the user allowed | allowed | allowed |
 * | Wi-Fi marked work | never (Download at home instead) | never (Download at home instead) |
 * | Any other Wi-Fi | ask once for this network | ask once (same answer) |
 *
 * "Start now anyway" never works on a work network: no BitTorrent on the office network is a hard rule.
 * The TV only ever downloads at home and isn't governed by this rule.
 */
object P2pRule {
    fun decide(
        use: P2pUse,
        net: NetworkNow,
        isHome: Boolean,
        known: KnownNetworks,
        mobileDataDownloads: Boolean,
        startAnyway: Boolean = false,
        isTv: Boolean = false,
    ): P2pVerdict {
        if (isTv) return P2pVerdict.Allow
        val fp = net.fingerprint
        return when {
            net.transport == Transport.NONE ->
                if (use == P2pUse.STREAM) P2pVerdict.Allow else P2pVerdict.Wait(WaitReason.NO_NETWORK)
            // A network the user called work stays forbidden even if it later looks like home.
            fp != null && fp in known.work ->
                if (use == P2pUse.STREAM) P2pVerdict.Block(WaitReason.WORK_NETWORK) else P2pVerdict.Wait(WaitReason.WORK_NETWORK)
            isHome -> P2pVerdict.Allow
            net.transport == Transport.CELLULAR -> when {
                use == P2pUse.STREAM || mobileDataDownloads || startAnyway -> P2pVerdict.Allow
                else -> P2pVerdict.Wait(WaitReason.MOBILE_DATA, canStartAnyway = true)
            }
            fp != null && fp in known.allowed -> P2pVerdict.Allow
            fp != null -> P2pVerdict.Ask(fp)
            // Wi-Fi we can't fingerprint can't be remembered: treat it as unknown and keep waiting.
            else -> if (use == P2pUse.STREAM) P2pVerdict.Block(WaitReason.UNKNOWN_WIFI) else P2pVerdict.Wait(WaitReason.UNKNOWN_WIFI)
        }
    }

    /** True when downloads may move traffic now. */
    fun downloadsAllowed(v: P2pVerdict) = v == P2pVerdict.Allow

    /** The reason an Ask verdict keeps downloads waiting. */
    fun waitReason(v: P2pVerdict): WaitReason? = when (v) {
        P2pVerdict.Allow -> null
        is P2pVerdict.Wait -> v.reason
        is P2pVerdict.Ask -> WaitReason.UNKNOWN_WIFI
        is P2pVerdict.Block -> v.reason
    }
}
