package com.nuvio.app.features.player.floating

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Nuvio RS: the floating window's geometry, like the iPhone's picture in picture: the spots it rests
 * on, where a throw lands, and when a throw or push tucks it into a side.
 */
internal object PipPhysics {
    /** How fast a thrown window slows down, per millisecond (as iOS scrolling). */
    const val DECELERATION_RATE = 0.998f

    enum class Side { LEFT, RIGHT }

    /** Where the window may rest: the screen minus the system bars and cutout, with a margin. */
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    sealed class Target {
        /** Rest on resting spot [index]. */
        data class Anchor(val x: Float, val y: Float, val index: Int) : Target()

        /** Tuck into [side], the window's left at [x]. */
        data class Tuck(val side: Side, val x: Float, val y: Float) : Target()
    }

    data class Config(
        /** px/s */
        val tuckVelocity: Float,
        /** Share of the width a throw must carry past the edge. */
        val tuckProjectedFraction: Float = 0.5f,
        /** Share of the width pushed past the edge that tucks without a throw. */
        val tuckDraggedFraction: Float = 0.42f,
        val tabVisible: Float,
    )

    /** How far a throw at [velocity] (px/s) carries before it stops. */
    fun project(velocity: Float, rate: Float = DECELERATION_RATE): Float =
        velocity / 1000f * rate / (1f - rate)

    /** The resting spots: the four corners, and the middle of each side when the screen is tall enough. */
    fun anchors(safe: Bounds, w: Float, h: Float, withMiddle: Boolean = true): List<Pair<Float, Float>> {
        val left = safe.left
        val right = max(safe.left, safe.right - w)
        val top = safe.top
        val bottom = max(safe.top, safe.bottom - h)
        val middle = (top + bottom) / 2f
        val spots = mutableListOf(left to top, right to top, left to bottom, right to bottom)
        if (withMiddle && bottom - top > h * 1.6f) {
            spots += left to middle
            spots += right to middle
        }
        return spots
    }

    fun nearestAnchor(px: Float, py: Float, anchors: List<Pair<Float, Float>>): Int {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        anchors.forEachIndexed { index, (x, y) ->
            val distance = hypot(px - x, py - y)
            if (distance < bestDistance) {
                best = index
                bestDistance = distance
            }
        }
        return best
    }

    /** The window's left when tucked into [side], [visible] px of it left on screen. */
    fun tuckedX(side: Side, screenW: Float, w: Float, visible: Float): Float =
        if (side == Side.LEFT) -(w - visible) else screenW - visible

    /** Where the window goes when let go at ([x], [y]) moving at ([vx], [vy]) px/s. */
    fun resolveRelease(
        x: Float,
        y: Float,
        vx: Float,
        vy: Float,
        w: Float,
        h: Float,
        screenW: Float,
        safe: Bounds,
        cfg: Config,
    ): Target {
        val projectedX = x + project(vx)
        val projectedY = y + project(vy)
        val pastLeft = (0f - x) / w
        val pastRight = (x + w - screenW) / w
        val projectedPastLeft = (0f - projectedX) / w
        val projectedPastRight = (projectedX + w - screenW) / w
        val sideways = abs(vx) > abs(vy) * 0.8f
        val tuckLeft = (vx < -cfg.tuckVelocity && sideways && projectedPastLeft > cfg.tuckProjectedFraction) ||
            (pastLeft > cfg.tuckDraggedFraction && vx <= cfg.tuckVelocity * 0.25f)
        val tuckRight = (vx > cfg.tuckVelocity && sideways && projectedPastRight > cfg.tuckProjectedFraction) ||
            (pastRight > cfg.tuckDraggedFraction && vx >= -cfg.tuckVelocity * 0.25f)
        val restY = clamp(projectedY, safe.top, max(safe.top, safe.bottom - h))
        if (tuckLeft) return Target.Tuck(Side.LEFT, tuckedX(Side.LEFT, screenW, w, cfg.tabVisible), restY)
        if (tuckRight) return Target.Tuck(Side.RIGHT, tuckedX(Side.RIGHT, screenW, w, cfg.tabVisible), restY)
        val spots = anchors(safe, w, h)
        val index = nearestAnchor(
            clamp(projectedX, safe.left - w, safe.right),
            clamp(projectedY, safe.top - h, safe.bottom),
            spots,
        )
        val (anchorX, anchorY) = spots[index]
        return Target.Anchor(anchorX, anchorY, index)
    }

    /** The resting spot on [side] nearest to [y], where a tucked window comes back to. */
    fun untuckTarget(side: Side, y: Float, w: Float, h: Float, safe: Bounds): Pair<Float, Float> {
        val onSide = anchors(safe, w, h).filter { (x, _) ->
            if (side == Side.LEFT) x <= safe.left + 1f else x >= safe.right - w - 1f
        }
        return onSide[nearestAnchor(if (side == Side.LEFT) safe.left else safe.right - w, y, onSide)]
    }

    /** Width within [minW]..[maxW], and the height that keeps [aspect]. */
    fun clampSize(w: Float, aspect: Float, minW: Float, maxW: Float): Pair<Float, Float> {
        val width = clamp(w, minW, maxW)
        return width to width / aspect
    }

    /** iOS-style resistance for dragging [offset] px past an edge. */
    fun rubberBand(offset: Float, dimension: Float, constant: Float = 0.55f): Float {
        if (offset == 0f || dimension == 0f) return 0f
        val sign = if (offset < 0f) -1f else 1f
        return sign * (1f - 1f / (abs(offset) * constant / dimension + 1f)) * dimension
    }

    /** Like coerceIn, but [lo] wins when the range is empty instead of throwing. */
    fun clamp(v: Float, lo: Float, hi: Float): Float = max(lo, min(hi, v))
}
