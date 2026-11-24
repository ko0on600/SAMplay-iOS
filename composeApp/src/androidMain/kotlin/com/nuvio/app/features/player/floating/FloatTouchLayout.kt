package com.nuvio.app.features.player.floating

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.max

/**
 * Nuvio RS: the floating window's root. Every touch goes to [Callback.onWindowTouch] (drag, throw,
 * pinch, tap); one that starts on a button also reaches the button, until the window takes it over.
 */
internal class FloatTouchLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    interface Callback {
        /** Handles the touch for the window; true once the window has taken it over. */
        fun onWindowTouch(e: MotionEvent): Boolean

        /** Whether the touch starting with [down] should also reach the buttons. */
        fun routeToChildren(down: MotionEvent): Boolean
    }

    var callback: Callback? = null
    private var toChildren = false

    /**
     * SAMplay 2.0.3: how many pixels at the window's edge fade out. The card's shadow ran past the
     * window and was cut off in a straight line, a grey box around the picture that showed on
     * white apps (most while held, when the card grows and rises, and low on the screen, where
     * the light throws the shadow further). Faded, the shadow ends softly instead.
     */
    var edgeFade: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private var fadeShaders: Array<LinearGradient>? = null
    private var fadeShadersW = 0f
    private var fadeShadersH = 0f
    private var fadeShadersSize = 0f

    override fun dispatchDraw(canvas: Canvas) {
        val fade = edgeFade
        val w = width.toFloat()
        val h = height.toFloat()
        if (fade <= 0f || w <= fade * 2f || h <= fade * 2f) {
            super.dispatchDraw(canvas)
            return
        }
        val layer = canvas.saveLayer(0f, 0f, w, h, null)
        super.dispatchDraw(canvas)
        val shaders = edgeShaders(w, h, fade)
        fadePaint.shader = shaders[0]
        canvas.drawRect(0f, 0f, fade, h, fadePaint)
        fadePaint.shader = shaders[1]
        canvas.drawRect(w - fade, 0f, w, h, fadePaint)
        fadePaint.shader = shaders[2]
        canvas.drawRect(0f, 0f, w, fade, fadePaint)
        fadePaint.shader = shaders[3]
        canvas.drawRect(0f, h - fade, w, h, fadePaint)
        canvas.restoreToCount(layer)
    }

    /** Clear at the window's edge, solid [fade] pixels in: left, right, top, bottom. */
    private fun edgeShaders(w: Float, h: Float, fade: Float): Array<LinearGradient> {
        fadeShaders?.let { if (w == fadeShadersW && h == fadeShadersH && fade == fadeShadersSize) return it }
        val clear = Color.TRANSPARENT
        val solid = Color.BLACK
        val made = arrayOf(
            LinearGradient(0f, 0f, fade, 0f, clear, solid, Shader.TileMode.CLAMP),
            LinearGradient(w, 0f, w - fade, 0f, clear, solid, Shader.TileMode.CLAMP),
            LinearGradient(0f, 0f, 0f, fade, clear, solid, Shader.TileMode.CLAMP),
            LinearGradient(0f, h, 0f, h - fade, clear, solid, Shader.TileMode.CLAMP),
        )
        fadeShaders = made
        fadeShadersW = w
        fadeShadersH = h
        fadeShadersSize = fade
        return made
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val current = callback ?: return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) toChildren = current.routeToChildren(ev)
        val takenOver = current.onWindowTouch(ev)
        if (!toChildren) return true
        if (takenOver) {
            // The button lets go, the window keeps the touch.
            val cancel = MotionEvent.obtain(ev)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            toChildren = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * SAMplay 2.0.3: how much of the shadow room at the window's edge may fade, at most [maxFade]: what
 * the held card (grown to [liftScale] around its centre) leaves free, less a [gap], so the
 * picture itself never fades.
 */
internal fun shadowFadeWidth(
    pad: Float,
    cardW: Float,
    cardH: Float,
    liftScale: Float,
    gap: Float,
    maxFade: Float,
): Float = (pad - max(cardW, cardH) * (liftScale - 1f) / 2f - gap).coerceIn(0f, maxFade)
