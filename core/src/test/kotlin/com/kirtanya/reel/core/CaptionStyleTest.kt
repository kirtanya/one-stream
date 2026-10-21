package com.kirtanya.reel.core

import com.kirtanya.reel.core.playback.CaptionStyle
import com.kirtanya.reel.core.playback.CaptionStyle.Background
import com.kirtanya.reel.core.playback.CaptionStyle.Edge
import com.kirtanya.reel.core.playback.CaptionStyle.Preset
import com.kirtanya.reel.core.playback.CaptionStyle.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptionStyleTest {
    @Test fun savesAndReadsBack(): Unit {
        val s = CaptionStyle(Size.HUGE, CaptionStyle.TextColour.YELLOW, Background.NONE, Edge.OUTLINE, CaptionStyle.Font.MONO, CaptionStyle.Position.HIGH, system = true)
        assertEquals(s, CaptionStyle.decode(s.encode()))
    }

    @Test fun oldSizeSettingCarriesOver(): Unit {
        // Before styles only "subtitleSize" was saved.
        assertEquals(Size.LARGE, CaptionStyle.decode(null, legacySize = "LARGE").size)
        assertEquals(CaptionStyle(), CaptionStyle.decode(null))
        // Unknown values (a newer version's) fall back to defaults, the rest is kept.
        assertEquals(CaptionStyle(edge = Edge.OUTLINE), CaptionStyle.decode("edge=OUTLINE;text=PURPLE;future=1"))
    }

    @Test fun presetsIgnoreSizeAndPosition(): Unit {
        val yellowBig = Preset.YELLOW.style.copy(size = Size.HUGE, position = CaptionStyle.Position.HIGH)
        assertEquals(Preset.YELLOW, Preset.of(yellowBig))
        assertNull(Preset.of(yellowBig.copy(font = CaptionStyle.Font.SERIF)))
        assertNull(Preset.of(CaptionStyle(system = true)))
    }

    @Test fun noBoxAndNoEdgeStillGetsAShadow(): Unit {
        assertEquals(Edge.SHADOW, CaptionStyle(background = Background.NONE, edge = Edge.NONE).readable.edge)
        assertEquals(Edge.NONE, CaptionStyle(background = Background.SOLID, edge = Edge.NONE).readable.edge)
    }
}
