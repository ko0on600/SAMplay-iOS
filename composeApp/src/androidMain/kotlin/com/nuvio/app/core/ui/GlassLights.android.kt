package com.nuvio.app.core.ui

import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

internal actual fun glassLightsPainter(lights: List<GlassLight>): DrawScope.() -> Unit {
    val paints = lights.map { light ->
        Paint().apply {
            // Dark gradients band without this.
            isDither = true
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            shader = RadialGradient(
                light.center.x,
                light.center.y,
                light.radius,
                light.color.toArgb(),
                light.color.copy(alpha = 0f).toArgb(),
                Shader.TileMode.CLAMP,
            )
        }
    }
    return {
        drawIntoCanvas { canvas ->
            paints.forEach { canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, it) }
        }
    }
}
