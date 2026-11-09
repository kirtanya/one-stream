package com.kirtanya.reel.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kirtanya.reel.core.tmdb.TitleRef

/**
 * [Movies], [Shows] and [Categories] exist only on the TV's side nav; the phone's tab bar lists its own four tabs, so adding
 * a tab here never changes it.
 */
enum class Tab(val label: String) {
    Home("Home"), Search("Search"), Movies("Movies"), Shows("Shows"), Categories("Categories"), Downloads("Downloads"), Settings("Settings")
}

sealed interface Dest {
    /** [season] preselects that season of a show (Continue watching lands on the episode's season). */
    data class Title(val ref: TitleRef, val season: Int? = null) : Dest

    /**
     * Where to play a film, or one episode of a show. [download] opens it for downloading;
     * [replacing] (an info-hash) for a new source for that download (Downloads → Change source);
     * [resumeAtMs] the player's position when it asked for another source (the pick plays from there).
     */
    data class Sources(
        val ref: TitleRef,
        val season: Int? = null,
        val episode: Int? = null,
        val download: Boolean = false,
        val replacing: String? = null,
        val resumeAtMs: Long? = null,
    ) : Dest {
        /** The same page, whatever position it carries. */
        fun samePage(other: Dest) = other is Sources && other.copy(resumeAtMs = null) == copy(resumeAtMs = null)
    }

    /**
     * A grid: a Home row's titles ("See all"), everything on one streaming service, or the most
     * popular films / shows in a genre ([genre] with [genreType]; loads more as you scroll).
     */
    data class Collection(
        val title: String,
        val items: List<com.kirtanya.reel.core.tmdb.TitleSummary>? = null,
        val provider: com.kirtanya.reel.core.tmdb.Provider? = null,
        val genre: com.kirtanya.reel.core.tmdb.Genre? = null,
        val genreType: com.kirtanya.reel.core.tmdb.MediaType? = null,
    ) : Dest

    /** TV: the films and shows of one Categories tile (a genre of both kinds, or an original language). */
    data class Category(val category: com.kirtanya.reel.core.browse.Category) : Dest

    /** A cast member: photo, bio, what they've been in. */
    data class Person(val id: Int, val name: String) : Dest

    /** A title known only by its IMDb id (One Launcher links): looked up on TMDB, then becomes [Title]. */
    data class ImdbTitle(val imdbId: String, val type: com.kirtanya.reel.core.tmdb.MediaType) : Dest

    /** "Play on TV" from the phone (onestream://cast/play): searches, then opens the player; becomes [Title]. */
    data class Cast(val request: com.kirtanya.reel.core.launcher.CastRequest) : Dest
}

/** Tabs at the root; title pages stack on top. Back pops the stack, then returns to Home. */
class Navigator {
    var tab by mutableStateOf(Tab.Home)
    val stack = mutableStateListOf<Dest>()

    val top: Dest? get() = stack.lastOrNull()

    fun open(dest: Dest) = stack.add(dest)

    /** Swaps [old] for [new] in place (an IMDb link once TMDB has resolved it), so Back skips the lookup. */
    fun replace(old: Dest, new: Dest) {
        val i = stack.lastIndexOf(old)
        if (i >= 0) stack[i] = new
    }

    /** A query handed in from outside (One Launcher's search link); [searchRequest] gives Search fresh state. */
    var searchQuery by mutableStateOf("")
        private set
    var searchRequest by mutableStateOf(0)
        private set

    fun openSearch(query: String) {
        searchQuery = query
        searchRequest++
        select(Tab.Search)
    }

    /** Saved-state key for the Search tab: a new one per handed-in query, so it starts with that text. */
    val searchStateKey: String get() = if (searchRequest == 0) Tab.Search.name else "${Tab.Search.name}#$searchRequest"

    /** Opens [dest] unless it's already showing; if it's further back, returns to it instead of stacking a copy. */
    fun openOrReturn(dest: Dest) {
        // A Sources page carrying a position (the player's Change source) returns to that page too, taking the position along.
        val existing = stack.indexOfLast { it == dest || (dest is Dest.Sources && dest.samePage(it)) }
        when {
            existing < 0 -> stack.add(dest)
            else -> {
                while (stack.lastIndex > existing) stack.removeAt(stack.lastIndex)
                if (stack[existing] != dest) stack[existing] = dest
            }
        }
    }

    fun select(t: Tab) {
        stack.clear()
        tab = t
    }

    /** Returns false when there is nothing left to go back to (let the system close the app). */
    fun back(): Boolean = when {
        stack.isNotEmpty() -> { stack.removeAt(stack.lastIndex); true }
        tab != Tab.Home -> { tab = Tab.Home; true }
        else -> false
    }
}

@Composable
fun rememberNavigator() = remember { Navigator() }
