package com.nuvio.app.features.player.floating

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Nuvio RS: a damped spring solved in closed form. Gives the offset from the rest point and the
 * velocity [t] seconds after it was let go at [x0] with [v0], so every frame lands exactly on the
 * curve however late it comes.
 */
internal class SpringSolver(
    stiffness: Float,
    dampingRatio: Float,
    private val x0: Float,
    private val v0: Float,
) {
    private val w0 = sqrt(stiffness.toDouble())
    private val z = dampingRatio.toDouble()

    fun position(t: Double): Double = eval(t).first

    fun velocity(t: Double): Double = eval(t).second

    /** Offset and velocity at [t] seconds. */
    fun eval(t: Double): Pair<Double, Double> {
        val x = x0.toDouble()
        val v = v0.toDouble()
        return when {
            z < 1.0 -> {
                val decay = z * w0
                val wd = w0 * sqrt(1.0 - z * z)
                val b = (v + decay * x) / wd
                val e = exp(-decay * t)
                val c = cos(wd * t)
                val s = sin(wd * t)
                (x * c + b * s) * e to e * ((b * wd - decay * x) * c + (-x * wd - decay * b) * s)
            }
            z == 1.0 -> {
                val e = exp(-w0 * t)
                val b = v + w0 * x
                val offset = x + b * t
                e * offset to e * (b - w0 * offset)
            }
            else -> {
                val root = sqrt(z * z - 1.0)
                val r1 = -w0 * (z - root)
                val r2 = -w0 * (z + root)
                val c2 = (v - r1 * x) / (r2 - r1)
                val c1 = x - c2
                c1 * exp(r1 * t) + c2 * exp(r2 * t) to c1 * r1 * exp(r1 * t) + c2 * r2 * exp(r2 * t)
            }
        }
    }

    fun isAtRest(t: Double, posThreshold: Double = 0.5, velThreshold: Double = 20.0): Boolean {
        val (offset, speed) = eval(t)
        return abs(offset) < posThreshold && abs(speed) < velThreshold
    }

    /** When the spring comes to rest, checked at 240 Hz, at most [maxSeconds]. */
    fun settleTime(maxSeconds: Double = 5.0): Double {
        var t = 0.0
        while (t < maxSeconds) {
            if (isAtRest(t)) return t
            t += 1.0 / 240.0
        }
        return maxSeconds
    }
}
