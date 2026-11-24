package com.nuvio.app.features.player.volumeboost

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.accentBrush
import com.nuvio.app.core.ui.themePalette

private val BoostRed = Color(0xFFFF453A)
private val BoostTextRed = Color(0xFFFF7A70)

/**
 * Fill of the vertical level bar. With boost the bar spans 0 to 300%, 0 to 100% on the bottom
 * half and 100 to 300% on the top half (it moves with the swipe, [levelToSwipe]): the top half
 * carries a faint red wash so the loud end reads before you reach it, and the fill warms from the
 * accent to red as it climbs past 100%. Without boost it draws the accent fill exactly as before.
 */
@Composable
internal fun LevelBarFill(level: Float, maxLevel: Float, modifier: Modifier = Modifier) {
    val accentBrush = MaterialTheme.themePalette.accentBrush()
    val accent = MaterialTheme.themePalette.secondary
    Box(
        modifier.drawBehind {
            val height = size.height
            val normalTop = height * (1f - levelBarShare(1f, maxLevel))
            if (maxLevel > 1f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(BoostRed.copy(alpha = 0.38f), BoostRed.copy(alpha = 0.06f)),
                        startY = 0f,
                        endY = normalTop,
                    ),
                    size = Size(size.width, normalTop),
                )
            }
            val baseHeight = levelBarShare(level.coerceIn(0f, 1f), maxLevel) * height
            if (baseHeight > 0f) {
                drawRect(
                    brush = accentBrush,
                    topLeft = Offset(0f, height - baseHeight),
                    size = Size(size.width, baseHeight),
                )
            }
            if (level > 1f && maxLevel > 1f) {
                val top = height * (1f - levelBarShare(level, maxLevel))
                drawRect(
                    brush = Brush.verticalGradient(listOf(BoostRed, accent), startY = 0f, endY = normalTop),
                    topLeft = Offset(0f, top),
                    size = Size(size.width, normalTop - top),
                )
            }
            if (maxLevel > 1f) {
                // Hairline notch at 100% so the boost zone reads as its own stretch.
                val notch = 1.dp.toPx()
                drawRect(
                    color = Color.Black.copy(alpha = 0.45f),
                    topLeft = Offset(0f, normalTop - notch / 2f),
                    size = Size(size.width, notch),
                )
            }
        },
    )
}

/** How far up the bar [level] sits, 0 to 1. */
internal fun levelBarShare(level: Float, maxLevel: Float): Float =
    if (maxLevel <= 1f) {
        level.coerceIn(0f, 1f)
    } else {
        levelToSwipe(level.coerceIn(0f, maxLevel)) / levelToSwipe(maxLevel)
    }

/** Percent label colour: white up to 100%, warming to a soft red at the top (300%). */
internal fun levelLabelColor(level: Float): Color =
    if (level <= 1f) {
        Color.White
    } else {
        lerp(Color.White, BoostTextRed, ((level - 1f) / (VolumeBoost.MAX_LEVEL - 1f)).coerceIn(0f, 1f))
    }
