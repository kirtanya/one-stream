package com.kirtanya.reel.ui.home

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What the big hero at the top of Home cycles through. */
enum class HeroSource(val label: String) { Trending("Trending"), MyList("My list"), Continue("Continue watching") }

/** Rows of posters, or wide stills (16:9) for a calmer, more cinematic Home. */
enum class PosterStyle(val label: String) { Posters("Posters"), Wide("Wide") }

/** Phone poster rows: how many fit across the screen. */
enum class Density(val label: String, val across: Int) { Roomy("Roomy", 3), Compact("Compact", 4) }

/** TV Home cards: backdrops (landscape, 16:9) or posters (portrait, 2:3). */
enum class TvCardShape(val label: String) { Landscape("Landscape"), Portrait("Portrait") }

/** TV Home card size, whatever the shape (widths in [TvCards]). */
enum class TvCardSize(val label: String) { Small("Small"), Medium("Medium"), Large("Large") }

/** Where the TV app opens. */
enum class TvStart(val label: String) { Home("Home"), Search("Search"), Downloads("Downloads") }

/**
 * Home screen layout, chosen in Settings → Home screen (per device; the TV keeps its own).
 * Rows are identified by [RowIds]; TMDB rows by their title. Unknown rows (a new TMDB row)
 * show in their natural place until the user moves them.
 */
object HomeLayout {
    private const val PREFS = "home_layout"
    private var prefs: android.content.SharedPreferences? = null

    var order by mutableStateOf(RowIds.DEFAULT)
        private set
    var hidden by mutableStateOf(emptySet<String>())
        private set
    var hero by mutableStateOf(HeroSource.Trending)
        private set
    var heroAutoAdvance by mutableStateOf(true)
        private set
    var posterStyle by mutableStateOf(PosterStyle.Posters)
        private set
    var density by mutableStateOf(Density.Roomy)
        private set
    /** TV only; the phone keeps [posterStyle] and [density]. Default: small landscape cards, so more titles show per row. */
    var tvCardShape by mutableStateOf(TvCardShape.Landscape)
        private set
    var tvCardSize by mutableStateOf(TvCardSize.Small)
        private set
    var tvStart by mutableStateOf(TvStart.Home)
        private set
    /** TV: after this many minutes idle on a browse screen, a slideshow of My list backdrops (0 = off). */
    var screensaverMin by mutableStateOf(5)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        // "services" (the removed "On your services" row) may still be in a saved layout: drop it.
        p.getString("order", null)?.split('\n')?.let { order = dropRemoved(it) }
        hidden = dropRemoved(p.getStringSet("hidden", emptySet()).orEmpty()).toSet()
        hero = p.getString("hero", null)?.let { n -> HeroSource.entries.firstOrNull { it.name == n } } ?: HeroSource.Trending
        heroAutoAdvance = p.getBoolean("heroAuto", true)
        posterStyle = p.getString("posterStyle", null)?.let { n -> PosterStyle.entries.firstOrNull { it.name == n } } ?: PosterStyle.Posters
        density = p.getString("density", null)?.let { n -> Density.entries.firstOrNull { it.name == n } } ?: Density.Roomy
        tvCardShape = p.getString("tvCardShape", null)?.let { n -> TvCardShape.entries.firstOrNull { it.name == n } } ?: TvCardShape.Landscape
        tvCardSize = p.getString("tvCardSize", null)?.let { n -> TvCardSize.entries.firstOrNull { it.name == n } } ?: TvCardSize.Small
        tvStart = p.getString("tvStart", null)?.let { n -> TvStart.entries.firstOrNull { it.name == n } } ?: TvStart.Home
        screensaverMin = p.getInt("screensaver", 5)
    }

    /** [available] row ids in the user's order (new ones where they'd naturally be), hidden ones left out. */
    fun arrange(available: List<String>): List<String> = all(available).filter { it !in hidden }

    /**
     * Every row the user can arrange, in their order (including hidden ones). Rows added since the layout was saved
     * (the Hindi rows) go where the default puts them, so Settings lists them where the home screen shows them.
     */
    fun all(available: List<String> = RowIds.DEFAULT): List<String> {
        val merged = order.filter { it in available }.toMutableList()
        available.filter { it !in order }.forEach { id -> merged.add(available.indexOf(id).coerceAtMost(merged.size), id) }
        return merged
    }

    fun move(id: String, by: Int, available: List<String> = RowIds.DEFAULT) {
        val list = all(available).toMutableList()
        val i = list.indexOf(id)
        val j = (i + by).coerceIn(0, list.lastIndex)
        if (i < 0 || i == j) return
        list.add(j, list.removeAt(i))
        order = list
        prefs?.edit()?.putString("order", list.joinToString("\n"))?.apply()
    }

    fun setShown(id: String, shown: Boolean) {
        hidden = if (shown) hidden - id else hidden + id
        prefs?.edit()?.putStringSet("hidden", hidden)?.apply()
    }

    fun chooseHero(v: HeroSource) { hero = v; prefs?.edit()?.putString("hero", v.name)?.apply() }
    fun chooseHeroAutoAdvance(v: Boolean) { heroAutoAdvance = v; prefs?.edit()?.putBoolean("heroAuto", v)?.apply() }
    fun choosePosterStyle(v: PosterStyle) { posterStyle = v; prefs?.edit()?.putString("posterStyle", v.name)?.apply() }
    fun chooseDensity(v: Density) { density = v; prefs?.edit()?.putString("density", v.name)?.apply() }
    fun chooseTvCardShape(v: TvCardShape) { tvCardShape = v; prefs?.edit()?.putString("tvCardShape", v.name)?.apply() }
    fun chooseTvCardSize(v: TvCardSize) { tvCardSize = v; prefs?.edit()?.putString("tvCardSize", v.name)?.apply() }
    fun chooseTvStart(v: TvStart) { tvStart = v; prefs?.edit()?.putString("tvStart", v.name)?.apply() }
    fun chooseScreensaver(min: Int) { screensaverMin = min; prefs?.edit()?.putInt("screensaver", min)?.apply() }

    fun reset() {
        order = RowIds.DEFAULT; hidden = emptySet(); hero = HeroSource.Trending; heroAutoAdvance = true
        posterStyle = PosterStyle.Posters; density = Density.Roomy
        tvCardShape = TvCardShape.Landscape; tvCardSize = TvCardSize.Small
        prefs?.edit()?.remove("order")?.remove("hidden")?.remove("hero")?.remove("heroAuto")?.remove("posterStyle")?.remove("density")
            ?.remove("tvCardShape")?.remove("tvCardSize")?.apply()
    }
}

/** [ids] without rows that no longer exist (a saved layout from before they were removed) and blanks. */
internal fun dropRemoved(ids: Collection<String>): List<String> = ids.filter { it.isNotBlank() && it != RowIds.REMOVED_SERVICES }

/** Row ids on Home. TMDB rows use "tmdb:<their title>". */
object RowIds {
    const val CONTINUE = "continue"
    const val MY_LIST = "mylist"
    const val BECAUSE = "because"
    const val NEW_EPISODES = "new"
    /** The removed "On your services" row: old saved layouts may still list it. */
    internal const val REMOVED_SERVICES = "services"
    fun tmdb(title: String) = "tmdb:$title"

    val DEFAULT = listOf(
        CONTINUE, NEW_EPISODES, MY_LIST, BECAUSE, tmdb("Trending this week"),
        tmdb("New Hindi releases"), tmdb("Popular Hindi films"), tmdb("Hindi shows"),
        tmdb("Popular movies"), tmdb("Trending shows"), tmdb("Popular in India"), tmdb("Top rated shows"),
    )

    fun label(id: String): String = when (id) {
        CONTINUE -> "Continue watching"
        MY_LIST -> "My list"
        BECAUSE -> "Because you watched…"
        NEW_EPISODES -> "New episodes"
        else -> id.removePrefix("tmdb:")
    }
}
