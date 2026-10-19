package com.kirtanya.reel.core.sync

import java.net.URLDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

enum class LinkKind { ADDON, PLUGIN_REPO, UNKNOWN }

/**
 * Turns whatever the user has — a `stremio://` Install link, a web.stremio.com install URL, a
 * pasted manifest URL, or text containing one — into the https manifest URL to add.
 */
object SourceLinks {
    private const val MAX_LENGTH = 4096
    private val urlInText = Regex("""(?i)(stremio|https?)://[^\s"'<>“”‘’]+""")
    private val trailing = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '*', '”', '’', '"', '\'')

    /** The manifest URL in [text], or null if there isn't one. */
    fun extract(text: String): String? {
        if (text.length > MAX_LENGTH * 4) return null
        for (m in urlInText.findAll(text.trim())) {
            normalize(m.value.trimEnd(*trailing))?.let { return it }
        }
        return null
    }

    /**
     * Like [extract], but a bare http(s) address with no manifest.json (an addon's base URL)
     * becomes `<url>/manifest.json`, as the Settings field always allowed.
     */
    fun fromUserInput(text: String): String? {
        extract(text)?.let { return it }
        val s = text.trim().trimEnd(*trailing)
        if (s.length > MAX_LENGTH || !Regex("""(?i)^https?://[^\s]+$""").matches(s)) return null
        return s.substringBefore('#').substringBefore('?').trimEnd('/') + "/manifest.json"
    }

    /** One link → https manifest URL. Accepts stremio:// and web.stremio.com `…addons?addon=<url>` links. */
    fun normalize(link: String): String? = normalize(link, depth = 0)

    private fun normalize(link: String, depth: Int): String? {
        val s = link.trim()
        if (s.isEmpty() || s.length > MAX_LENGTH || depth > 2) return null
        // web.stremio.com/#/addons?addon=https%3A%2F%2F…%2Fmanifest.json (Install buttons on some sites)
        Regex("""[?&#]addon=([^&#\s]+)""").find(s)?.let { m ->
            val inner = runCatching { URLDecoder.decode(m.groupValues[1], Charsets.UTF_8) }.getOrNull() ?: return null
            return normalize(inner, depth + 1)
        }
        val https = when {
            s.startsWith("stremio://", ignoreCase = true) -> "https://" + s.substring("stremio://".length)
            s.startsWith("https://", ignoreCase = true) || s.startsWith("http://", ignoreCase = true) -> s
            else -> return null
        }
        val path = https.substringBefore('#')
        return if (path.substringBefore('?').endsWith("manifest.json", ignoreCase = true)) path else null
    }

    /** Stremio addon manifest, Nuvio plugin repository, or neither. */
    fun detectKind(manifestJson: String): LinkKind {
        val o = runCatching { Json.parseToJsonElement(manifestJson) as? JsonObject }.getOrNull() ?: return LinkKind.UNKNOWN
        return when {
            o["scrapers"] is JsonArray -> LinkKind.PLUGIN_REPO
            o.containsKey("id") && o["resources"] is JsonArray -> LinkKind.ADDON
            else -> LinkKind.UNKNOWN
        }
    }
}
