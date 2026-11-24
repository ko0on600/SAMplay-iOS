package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

private val LIGHT_ANCHORS = listOf(Offset(0.1f, 0f), Offset(0.95f, 1f))
private const val LIGHT_RADIUS = 0.72f

/** One soft light screened over what is already drawn, so it shows on dark and hardly on bright. */
internal class GlassLight(val color: Color, val center: Offset, val radius: Float)

/** Prepared once per size; drawing it costs a gradient fill per light. */
internal expect fun glassLightsPainter(lights: List<GlassLight>): DrawScope.() -> Unit

/**
 * Nuvio RS: the glass themes' lights. They lie over the whole app rather than behind it, because the
 * screens stay opaque: pages cross while navigating, and every fade ends in the page's plain colour.
 */
@Composable
internal fun Modifier.glassLights(enabled: Boolean): Modifier {
    val colors = LocalGlassLights.current
    if (!enabled || colors.isEmpty()) return this
    return drawWithCache {
        val paint = glassLightsPainter(colors.zip(LIGHT_ANCHORS) { color, anchor -> light(size, color, anchor) })
        onDrawWithContent {
            drawContent()
            paint()
        }
    }
}

private fun light(size: Size, color: Color, anchor: Offset) = GlassLight(
    color = color,
    center = Offset(anchor.x * size.width, anchor.y * size.height),
    radius = LIGHT_RADIUS * size.maxDimension,
)
