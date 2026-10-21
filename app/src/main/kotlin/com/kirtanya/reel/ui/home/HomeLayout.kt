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
    var tvStart by mutableStateOf(TvStart.Home)
        private set
    /** TV: after this many minutes idle on a browse screen, a slideshow of My list backdrops (0 = off). */
    var screensaverMin by mutableStateOf(5)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        p.getString("order", null)?.split('\n')?.filter { it.isNotBlank() }?.let { order = it }
        hidden = p.getStringSet("hidden", emptySet()).orEmpty().toSet()
        hero = p.getString("hero", null)?.let { n -> HeroSource.entries.firstOrNull { it.name == n } } ?: HeroSource.Trending
        heroAutoAdvance = p.getBoolean("heroAuto", true)
        posterStyle = p.getString("posterStyle", null)?.let { n -> PosterStyle.entries.firstOrNull { it.name == n } } ?: PosterStyle.Posters
        density = p.getString("density", null)?.let { n -> Density.entries.firstOrNull { it.name == n } } ?: Density.Roomy
        tvStart = p.getString("tvStart", null)?.let { n -> TvStart.entries.firstOrNull { it.name == n } } ?: TvStart.Home
        screensaverMin = p.getInt("screensaver", 5)
    }

    /** [available] row ids in the user's order (new ones where they'd naturally be), hidden ones left out. */
    fun arrange(available: List<String>): List<String> {
        val known = order.filter { it in available }
        val fresh = available.filter { it !in order }
        val merged = known.toMutableList()
        fresh.forEach { id -> merged.add(available.indexOf(id).coerceAtMost(merged.size), id) }
        return merged.filter { it !in hidden }
    }

    /** Every row the user can arrange, in their order (including hidden ones). */
    fun all(available: List<String> = RowIds.DEFAULT): List<String> = order.filter { it in available } + available.filter { it !in order }

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
    fun chooseTvStart(v: TvStart) { tvStart = v; prefs?.edit()?.putString("tvStart", v.name)?.apply() }
    fun chooseScreensaver(min: Int) { screensaverMin = min; prefs?.edit()?.putInt("screensaver", min)?.apply() }

    fun reset() {
        order = RowIds.DEFAULT; hidden = emptySet(); hero = HeroSource.Trending; heroAutoAdvance = true
        posterStyle = PosterStyle.Posters; density = Density.Roomy
        prefs?.edit()?.remove("order")?.remove("hidden")?.remove("hero")?.remove("heroAuto")?.remove("posterStyle")?.remove("density")?.apply()
    }
}

/** Row ids on Home. TMDB rows use "tmdb:<their title>". */
object RowIds {
    const val CONTINUE = "continue"
    const val MY_LIST = "mylist"
    const val BECAUSE = "because"
    const val NEW_EPISODES = "new"
    const val SERVICES = "services"
    fun tmdb(title: String) = "tmdb:$title"

    val DEFAULT = listOf(
        CONTINUE, NEW_EPISODES, MY_LIST, BECAUSE, tmdb("Trending this week"), SERVICES,
        tmdb("Popular movies"), tmdb("Trending shows"), tmdb("Popular in India"), tmdb("Top rated shows"),
    )

    fun label(id: String): String = when (id) {
        CONTINUE -> "Continue watching"
        MY_LIST -> "My list"
        BECAUSE -> "Because you watched…"
        NEW_EPISODES -> "New episodes"
        SERVICES -> "On your services"
        else -> id.removePrefix("tmdb:")
    }
}
