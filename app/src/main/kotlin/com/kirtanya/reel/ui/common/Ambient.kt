package com.kirtanya.reel.ui.common

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.kirtanya.reel.ui.theme.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Poster URL → its light colour. Shared by every screen so the glow doesn't flicker on return. */
private val ambientCache = LruCache<String, Int>(200)

/**
 * The colour a title "lights the room" with: the poster's most vivid colour, lifted so it reads
 * as light on the night canvas. The app accent until (or unless) an image gives one.
 * Animated, so the light drifts from one title to the next: pass the State to [AmbientGlow] unread,
 * so each animation frame redraws the glow only (reading it in composition recomposed the whole screen).
 * Only the glow takes this colour; the app's own colours never follow the poster.
 */
@Composable
fun rememberAmbientColor(imageUrl: String?): State<Color> {
    val context = LocalContext.current
    var media by remember { mutableStateOf(imageUrl?.let { ambientCache.get(it) }?.takeIf { it != NO_COLOUR }?.let(::Color)) }
    LaunchedEffect(imageUrl) {
        if (imageUrl == null) {
            media = null
            return@LaunchedEffect
        }
        ambientCache.get(imageUrl)?.let {
            media = if (it == NO_COLOUR) null else Color(it)
            return@LaunchedEffect
        }
        val color = withContext(Dispatchers.Default) {
            runCatching {
                val request = ImageRequest.Builder(context).data(imageUrl).size(96).allowHardware(false).build()
                val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult
                result?.image?.toBitmap()?.let(::lightFrom)
            }.getOrNull()
        }
        // A grey poster has no colour to give: remember that, and keep the user's accent.
        ambientCache.put(imageUrl, color?.toArgb() ?: NO_COLOUR)
        media = color
    }
    return animateColorAsState(media ?: Tokens.Colors.accent, tween(Tokens.Motion.ambientMs), label = "ambient")
}

private const val NO_COLOUR = 0

private fun lightFrom(bitmap: Bitmap): Color? {
    val p = Palette.from(bitmap).maximumColorCount(16).generate()
    val swatch = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.darkVibrantSwatch ?: p.dominantSwatch ?: return null
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(swatch.rgb, hsv)
    if (hsv[1] < 0.15f) return null
    // Light, not paint: keep the hue, make it saturated and bright enough to glow on near-black.
    hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
    hsv[2] = hsv[2].coerceIn(0.75f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * The Afterglow light leak: a soft radial light falling from the top of the screen (off to
 * the left), coloured by [color], fading into the canvas.
 */
@Composable
fun AmbientGlow(color: Color, modifier: Modifier = Modifier, height: Dp = 520.dp, strength: Float = 0.42f) =
    AmbientGlow({ color }, modifier, height, strength)

/** [color] is read while drawing, so an animated colour never recomposes the caller. */
@Composable
fun AmbientGlow(color: () -> Color, modifier: Modifier = Modifier, height: Dp = 520.dp, strength: Float = 0.42f) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                val c = color()
                drawRect(
                    Brush.radialGradient(
                        0f to c.copy(alpha = strength),
                        0.45f to c.copy(alpha = strength * 0.35f),
                        1f to Color.Transparent,
                        center = Offset(size.width * 0.2f, -size.height * 0.05f),
                        radius = size.maxDimension * 0.85f,
                    ),
                )
            },
    )
}

/**
 * Edge-to-edge image with a scrim into the canvas: bottom always, left too on TV (where text
 * sits on the left of the picture).
 */
@Composable
fun Backdrop(url: String?, modifier: Modifier = Modifier, fromLeft: Boolean = false, content: @Composable BoxScope.() -> Unit = {}) {
    Box(modifier) {
        RemoteImage(url, Modifier.fillMaxSize(), ContentScale.Crop)
        if (fromLeft) {
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Tokens.Colors.canvas, 0.6f to Color.Transparent)))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Tokens.Colors.canvas)))
        content()
    }
}
