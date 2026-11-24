package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope

internal actual fun glassLightsPainter(lights: List<GlassLight>): DrawScope.() -> Unit {
    val brushes = lights.map { light ->
        Brush.radialGradient(listOf(light.color, light.color.copy(alpha = 0f)), light.center, light.radius)
    }
    return { brushes.forEach { drawRect(it, blendMode = BlendMode.Screen) } }
}
