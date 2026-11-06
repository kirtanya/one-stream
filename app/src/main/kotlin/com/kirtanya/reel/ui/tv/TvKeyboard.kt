package com.kirtanya.reel.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kirtanya.reel.core.browse.KeyPage
import com.kirtanya.reel.core.browse.SearchKey
import com.kirtanya.reel.core.browse.SearchKeys
import com.kirtanya.reel.ui.theme.Tokens

private val KeySize = 40.dp
private val KeyGap = 4.dp

/** The keyboard's width: seven keys and six gaps. */
internal val TvKeyboardWidth: Dp = KeySize * SearchKeys.COLS + KeyGap * (SearchKeys.COLS - 1)

private val OkKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.ButtonA)

/**
 * The TV's own search keyboard (Netflix / YouTube style, layout and behaviour from One Launcher's keyboard, see
 * [SearchKeys]): an alphabetical grid, tall Delete and Space on the right, 123 / abc, then Clear and Search. Typing
 * never opens the system keyboard. Every key is a plain focusable, so the arrows are Compose's own focus search:
 * ◄ off the left column reaches the rail, ► off the right column calls [onExitRight] (the results), ▲ ▼ move on to
 * what's above and below. Focus = a solid accent fill (never a scale); nothing animates, and a key costs one box
 * and one text, with no coroutine or effect of its own.
 *
 * @param hints characters likely to come next (see [com.kirtanya.reel.core.browse.SearchSuggest.nextKeys]): a faint outline.
 * @param onExitRight takes focus to the results; false when there is nowhere to go (the arrow then does its default).
 */
@Composable
internal fun TvKeyboard(
    page: KeyPage,
    hints: Set<Char>,
    onKey: (SearchKey) -> Unit,
    onExitRight: () -> Boolean,
    startFocus: FocusRequester,
    searchFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(modifier.width(TvKeyboardWidth), verticalArrangement = Arrangement.spacedBy(KeyGap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
            Column(verticalArrangement = Arrangement.spacedBy(KeyGap)) {
                for (row in 0 until SearchKeys.GRID) {
                    Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                        for (col in 0 until SearchKeys.COLS - 1) {
                            val key = SearchKeys.key(page, row, col)
                            val start = if (row == SearchKeys.START_ROW && col == SearchKeys.START_COL) Modifier.focusRequester(startFocus) else Modifier
                            KeyCap(key, page, key is SearchKey.Text && key.char in hints, onKey, start.size(KeySize))
                        }
                    }
                }
            }
            // The right column: two tall keys.
            Column(verticalArrangement = Arrangement.spacedBy(KeyGap)) {
                KeyCap(SearchKey.Delete, page, false, onKey, Modifier.width(KeySize).height(tall(SearchKeys.DELETE_ROWS.count())), onExitRight)
                KeyCap(SearchKey.Space, page, ' ' in hints, onKey, Modifier.width(KeySize).height(tall(SearchKeys.SPACE_ROWS.count())), onExitRight)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
            KeyCap(SearchKey.Clear, page, false, onKey, Modifier.width(wide(SearchKeys.CLEAR_COLS.count())).height(KeySize))
            KeyCap(SearchKey.Search, page, false, onKey, Modifier.width(wide(SearchKeys.SEARCH_COLS.count())).height(KeySize).focusRequester(searchFocus), onExitRight)
        }
    }
}

private fun tall(rows: Int) = KeySize * rows + KeyGap * (rows - 1)
private fun wide(cols: Int) = KeySize * cols + KeyGap * (cols - 1)

@Composable
private fun KeyCap(
    key: SearchKey,
    page: KeyPage,
    hint: Boolean,
    onKey: (SearchKey) -> Unit,
    modifier: Modifier,
    /** Set on the right-most keys: ► from them goes to the results. */
    onExitRight: (() -> Boolean)? = null,
) {
    // Read only here, so a focus move recomposes the two keys involved, nothing else.
    var focused by remember { mutableStateOf(false) }
    val shape = SHAPE
    val fg = if (focused) Tokens.Colors.onAccent else Tokens.Colors.text
    Box(
        modifier
            .onKeyEvent { e ->
                when {
                    e.key in OkKeys -> {
                        // One press types once; holding OK repeats only Delete (clearing a field). Key-up is swallowed too,
                        // so the press that brought focus here can't click anything.
                        if (e.type == KeyEventType.KeyDown && (e.nativeKeyEvent.repeatCount == 0 || key == SearchKey.Delete)) onKey(key)
                        true
                    }
                    e.key == Key.DirectionRight && onExitRight != null -> e.type == KeyEventType.KeyDown && onExitRight()
                    else -> false
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clip(shape)
            .background(if (focused) Tokens.Colors.accent else Tokens.Colors.surface2)
            // A key likely to come next: a faint outline (not while focused, where the fill says it all).
            .then(if (hint && !focused) Modifier.border(Tokens.Size.hairline, Tokens.Colors.hairlineStrong, shape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when (key) {
            is SearchKey.Text -> Text(key.char.toString(), style = Tokens.Type.heading, color = fg)
            SearchKey.Mode -> Text(if (page == KeyPage.LETTERS) "123" else "abc", style = Tokens.Type.caption, color = fg)
            SearchKey.Delete -> Icon(Icons.AutoMirrored.Rounded.Backspace, "Delete", tint = fg, modifier = Modifier.size(ICON))
            SearchKey.Space -> Icon(Icons.Rounded.SpaceBar, "Space", tint = fg, modifier = Modifier.size(ICON))
            SearchKey.Clear -> Label(Icons.Rounded.Close, "Clear", fg)
            SearchKey.Search -> Label(Icons.Rounded.Search, "Search", fg)
        }
    }
}

@Composable
private fun Label(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Space.xs)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(ICON))
        Text(text, style = Tokens.Type.caption, color = color, maxLines = 1)
    }
}

private val SHAPE = RoundedCornerShape(Tokens.Radius.s)
private val ICON = 20.dp
