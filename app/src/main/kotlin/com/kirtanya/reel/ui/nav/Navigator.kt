package com.kirtanya.reel.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kirtanya.reel.core.tmdb.TitleRef

enum class Tab(val label: String) { Home("Home"), Search("Search"), Downloads("Downloads"), Settings("Settings") }

sealed interface Dest {
    data class Title(val ref: TitleRef) : Dest

    /** Where to play a film, or one episode of a show. */
    data class Sources(val ref: TitleRef, val season: Int? = null, val episode: Int? = null) : Dest

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

    /** A cast member: photo, bio, what they've been in. */
    data class Person(val id: Int, val name: String) : Dest
}

/** Tabs at the root; title pages stack on top. Back pops the stack, then returns to Home. */
class Navigator {
    var tab by mutableStateOf(Tab.Home)
    val stack = mutableStateListOf<Dest>()

    val top: Dest? get() = stack.lastOrNull()

    fun open(dest: Dest) = stack.add(dest)

    /** Opens [dest] unless it's already showing; if it's further back, returns to it instead of stacking a copy. */
    fun openOrReturn(dest: Dest) {
        val existing = stack.lastIndexOf(dest)
        when {
            existing == stack.lastIndex && existing >= 0 -> Unit
            existing >= 0 -> while (stack.lastIndex > existing) stack.removeAt(stack.lastIndex)
            else -> stack.add(dest)
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
