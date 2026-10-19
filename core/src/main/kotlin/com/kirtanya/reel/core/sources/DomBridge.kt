package com.kirtanya.reel.core.sources

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** jsoup elements behind integer handles, for the cheerio-style API plugins use. */
internal class DomBridge {
    private val nodes = ArrayList<Element>()
    private val ids = HashMap<Element, Int>()

    private fun idOf(e: Element): Int = ids.getOrPut(e) { nodes.add(e); nodes.lastIndex }
    private fun node(id: Int): Element? = nodes.getOrNull(id)
    private fun idsJson(list: List<Element>) = list.joinToString(",", "[", "]") { idOf(it).toString() }

    fun load(html: String): Int = idOf(Jsoup.parse(html))
    fun fragment(html: String): Int = idOf(Jsoup.parseBodyFragment(html).body())

    fun select(id: Int, css: String): String = idsJson(node(id)?.let { runCatching { it.select(css).toList() }.getOrDefault(emptyList()) }.orEmpty())
    fun children(id: Int): String = idsJson(node(id)?.children()?.toList().orEmpty())
    fun parent(id: Int): Int = node(id)?.parent()?.let(::idOf) ?: -1
    fun next(id: Int): Int = node(id)?.nextElementSibling()?.let(::idOf) ?: -1
    fun prev(id: Int): Int = node(id)?.previousElementSibling()?.let(::idOf) ?: -1
    fun matches(id: Int, css: String): Boolean = node(id)?.let { runCatching { it.`is`(css) }.getOrDefault(false) } ?: false
    fun attr(id: Int, name: String): String? = node(id)?.takeIf { it.hasAttr(name) }?.attr(name)
    fun attrs(id: Int): String = node(id)?.attributes()?.joinToString(",", "{", "}") { "${quote(it.key)}:${quote(it.value)}" } ?: "{}"
    fun text(id: Int): String = node(id)?.text().orEmpty()
    fun html(id: Int): String = node(id)?.html().orEmpty()
    fun outer(id: Int): String = node(id)?.outerHtml().orEmpty()
    fun tag(id: Int): String = node(id)?.tagName().orEmpty()

    private fun quote(s: String) = buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
