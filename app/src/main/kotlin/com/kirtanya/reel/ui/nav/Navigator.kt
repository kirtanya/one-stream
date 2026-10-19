package com.kirtanya.reel.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kirtanya.reel.core.tmdb.TitleRef

enum class Tab(val label: String) { Home("Home"), Search("Search"), Downloads("Downloads") }

sealed interface Dest {
    data class Title(val ref: TitleRef) : Dest
}

/** Tabs at the root; title pages stack on top. Back pops the stack, then returns to Home. */
class Navigator {
    var tab by mutableStateOf(Tab.Home)
    val stack = mutableStateListOf<Dest>()

    val top: Dest? get() = stack.lastOrNull()

    fun open(dest: Dest) = stack.add(dest)

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
