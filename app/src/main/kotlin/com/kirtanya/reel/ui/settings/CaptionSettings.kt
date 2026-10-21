package com.kirtanya.reel.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView
import com.kirtanya.reel.captions.CaptionLook
import com.kirtanya.reel.core.playback.CaptionStyle
import com.kirtanya.reel.ui.common.ButtonKind
import com.kirtanya.reel.ui.common.ReelButton
import com.kirtanya.reel.ui.theme.Tokens

/**
 * Settings → Subtitles → Style: a live preview (the player's own subtitle renderer), one-tap looks,
 * then each part. Android's caption style replaces the look but keeps our size and position.
 */
internal fun LazyListScope.captionStyleItems(style: CaptionStyle, isTv: Boolean, onChange: (CaptionStyle) -> Unit) {
    item(key = "caption-preview") {
        Section("Style") {
            CaptionPreview(style, isTv)
            Choices(
                CaptionStyle.Preset.entries, CaptionStyle.Preset.of(style), { it.label },
                extra = "Android's style" to style.system,
                onExtra = { onChange(style.copy(system = true)) },
            ) { onChange(it.style.copy(size = style.size, position = style.position)) }
        }
    }
    item(key = "caption-size") {
        Section("Size") { Choices(CaptionStyle.Size.entries, style.size, { it.label }) { onChange(style.copy(size = it)) } }
    }
    if (!style.system) {
        item(key = "caption-text") {
            Section("Text colour") { Choices(CaptionStyle.TextColour.entries, style.text, { it.label }) { onChange(style.copy(text = it)) } }
        }
        item(key = "caption-bg") {
            Section("Background") { Choices(CaptionStyle.Background.entries, style.background, { it.label }) { onChange(style.copy(background = it)) } }
        }
        item(key = "caption-edge") {
            Section("Outline", note = "With no background, letters always keep at least a shadow so they stay readable on bright scenes.") {
                Choices(CaptionStyle.Edge.entries, style.edge, { it.label }) { onChange(style.copy(edge = it)) }
            }
        }
        item(key = "caption-font") {
            Section("Font") { Choices(CaptionStyle.Font.entries, style.font, { it.label }) { onChange(style.copy(font = it)) } }
        }
    }
    item(key = "caption-pos") {
        Section("Position", note = "Higher keeps subtitles clear of the player's controls.") {
            Choices(CaptionStyle.Position.entries, style.position, { it.label }) { onChange(style.copy(position = it)) }
        }
    }
    if (style.system) item(key = "caption-system") {
        Section(note = "Colours, outline and font come from Android: Settings → Accessibility → Caption preferences.") {
            ReelButton("Choose my own style", { onChange(style.copy(system = false)) }, kind = ButtonKind.Secondary)
        }
    }
}

/** Buttons for one choice; the picked one is filled. [extra] adds one more button with its own action. */
@Composable
private fun <T> Choices(
    values: List<T>,
    selected: T?,
    label: (T) -> String,
    extra: Pair<String, Boolean>? = null,
    onExtra: () -> Unit = {},
    onPick: (T) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Tokens.Space.s), verticalArrangement = Arrangement.spacedBy(Tokens.Space.s)) {
        val extraOn = extra?.second == true
        values.forEach { v ->
            ReelButton(label(v), { onPick(v) }, kind = if (!extraOn && v == selected) ButtonKind.Primary else ButtonKind.Ghost)
        }
        if (extra != null) ReelButton(extra.first, onExtra, kind = if (extraOn) ButtonKind.Primary else ButtonKind.Ghost)
    }
}

/**
 * A 16:9 "screen", dark on the left and bright on the right (a bright scene is where weak styles fail),
 * with two lines drawn by the same SubtitleView the player uses.
 */
@Composable
private fun CaptionPreview(style: CaptionStyle, isTv: Boolean) {
    val shape = RoundedCornerShape(Tokens.Radius.m)
    Box(
        Modifier
            .widthIn(max = if (isTv) 520.dp else 640.dp)
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(Tokens.Night.canvas, Tokens.Night.accentDeep, Tokens.Night.text))),
    ) {
        AndroidView(
            factory = { SubtitleView(it) },
            update = { view ->
                CaptionLook.apply(view, style)
                view.setCues(listOf(Cue.Builder().setText(SAMPLE).build()))
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
    Column { Text(style.summary, style = Tokens.Type.caption, color = Tokens.Colors.textSecondary) }
}

private const val SAMPLE = "I've seen things you people wouldn't believe.\nAll those moments will be lost in time."
