package com.kirtanya.reel.core.homedisk

import java.security.MessageDigest

/** How the device is connected right now (the non-VPN network that carries the traffic). */
enum class Transport { WIFI, ETHERNET, CELLULAR, OTHER, NONE }

/**
 * What [NetworkFingerprint] hashes: only things ACCESS_NETWORK_STATE gives (docs/HOME_DISK.md §5.2).
 * The SSID needs a location permission, so it is never used.
 */
data class LinkFacts(
    val gateways: List<String> = emptyList(),
    val dnsServers: List<String> = emptyList(),
    /** IPv4 address & mask, e.g. `192.168.1.0/24`. */
    val ipv4Prefix: String? = null,
    val privateDnsServerName: String? = null,
    val domains: String? = null,
)

/** One network as the home-disk rules see it. */
data class NetworkNow(
    val transport: Transport,
    val metered: Boolean = false,
    /** [NetworkFingerprint.of] the link; null without a network. */
    val fingerprint: String? = null,
) {
    val isWifiLike: Boolean get() = transport == Transport.WIFI || transport == Transport.ETHERNET

    companion object {
        val NONE = NetworkNow(Transport.NONE)
    }
}

/**
 * Identifies a network without a location permission: SHA-256 of its gateways, DNS servers, IPv4 prefix,
 * private DNS name and search domains (docs/HOME_DISK.md §5.2; the same function STREAMING_V2's NetworkGate
 * uses). Order of addresses doesn't matter; a router whose settings change gets a new fingerprint.
 */
object NetworkFingerprint {
    fun of(f: LinkFacts): String {
        val text = listOf(
            "gw=" + f.gateways.map(String::trim).filter(String::isNotEmpty).sorted().joinToString(","),
            "dns=" + f.dnsServers.map(String::trim).filter(String::isNotEmpty).sorted().joinToString(","),
            "net=" + f.ipv4Prefix.orEmpty().trim(),
            "pdns=" + f.privateDnsServerName.orEmpty().trim().lowercase(),
            "dom=" + f.domains.orEmpty().trim().lowercase(),
        ).joinToString("\n")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** `192.168.1.37` / 24 → `192.168.1.0/24`; null for anything that isn't IPv4. */
    fun ipv4Prefix(address: String, prefixLength: Int): String? {
        val parts = address.split('.').mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
        if (parts.size != 4 || prefixLength !in 0..32) return null
        val ip = parts.fold(0L) { acc, b -> (acc shl 8) or b.toLong() }
        val mask = if (prefixLength == 0) 0L else (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
        val net = ip and mask
        return (3 downTo 0).joinToString(".") { ((net shr (8 * it)) and 0xFF).toString() } + "/$prefixLength"
    }

    /** Private LAN, link-local, loopback or Tailscale (100.64/10) IPv4: the only peers our servers answer (§9.1). */
    fun isPrivateOrTailnet(address: String?): Boolean {
        val p = address?.removePrefix("/")?.removePrefix("::ffff:")?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return false
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) ||
            (p[0] == 169 && p[1] == 254) || p[0] == 127 || (p[0] == 100 && p[1] in 64..127)
    }

    /** The home LAN only (no Tailscale): where the TV's proof counts and an SMB server may be. */
    fun isPrivateLan(address: String?): Boolean =
        isPrivateOrTailnet(address) && !isTailnet(address) && address?.startsWith("127.") != true

    fun isTailnet(address: String?): Boolean {
        val p = address?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return false
        return p.size == 4 && p[0] == 100 && p[1] in 64..127
    }
}

/** Why the app thinks the device is (or isn't) at home. */
enum class HomeReason {
    /** The paired TV answered its HMAC challenge on the LAN: nobody else can. */
    TV_PROOF,
    /** This network's fingerprint is the one saved at setup. */
    FINGERPRINT,
    NO_NETWORK,
    /** Mobile data (or anything that isn't Wi-Fi / Ethernet). */
    NOT_WIFI,
    /** No home network saved yet: only a Test the user confirms may sign in. */
    NO_HOME_SAVED,
    /** Wi-Fi with another fingerprint. */
    OTHER_NETWORK,
}

data class HomeDecision(val isHome: Boolean, val reason: HomeReason, val saveFingerprint: String? = null)

/**
 * Home detection without a location permission (docs/HOME_DISK.md §5.2) and the rule that keeps the SMB
 * password away from strangers (invariant I6): a stranger's SMB server could capture the NTLMv2 response and
 * crack the password offline, so nothing signs in to a share off the home network.
 */
object HomeNetwork {
    /**
     * [tvProof]: the paired TV answered on this network at a private LAN address (always false on the TV).
     * When the proof holds but the fingerprint differs (router settings changed), [HomeDecision.saveFingerprint]
     * is the new one to save.
     */
    fun decide(net: NetworkNow, savedHome: String?, tvProof: Boolean): HomeDecision = when {
        net.transport == Transport.NONE || net.fingerprint == null -> HomeDecision(false, HomeReason.NO_NETWORK)
        !net.isWifiLike -> HomeDecision(false, HomeReason.NOT_WIFI)
        tvProof -> HomeDecision(true, HomeReason.TV_PROOF, saveFingerprint = net.fingerprint.takeIf { it != savedHome })
        savedHome == null -> HomeDecision(false, HomeReason.NO_HOME_SAVED)
        net.fingerprint == savedHome -> HomeDecision(true, HomeReason.FINGERPRINT)
        else -> HomeDecision(false, HomeReason.OTHER_NETWORK)
    }

    /**
     * May this device open an SMB connection now (I6)? At home, yes. With no home saved yet, only the Test the
     * user started after confirming "I'm at home" (that Test then saves this network as home). Never on mobile
     * data, never on another Wi-Fi, never on a network the user marked as work.
     */
    fun maySignIn(decision: HomeDecision, userConfirmedHome: Boolean, markedWork: Boolean): Boolean = when {
        markedWork -> false
        decision.isHome -> true
        decision.reason == HomeReason.NO_HOME_SAVED -> userConfirmedHome
        else -> false
    }
}
