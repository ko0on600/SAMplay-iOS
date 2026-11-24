package com.nuvio.app.features.player.floating

import android.view.Choreographer

/** One animated number of a target, such as a window's x. */
internal interface FloatProperty<T> {
    fun getValue(target: T): Float
    fun setValue(target: T, value: Float)
}

/**
 * Nuvio RS: moves several properties of one target with the same spring, frame by frame on the
 * Choreographer. Windows can't use view animations, so this is how the floating window glides;
 * [onFrame] pushes each new frame (for a window, its layout).
 */
internal class WindowSpring<T>(
    private val target: T,
    private val props: List<FloatProperty<T>>,
    private val onFrame: () -> Unit,
) {
    private val choreographer = Choreographer.getInstance()
    private var finals = FloatArray(props.size)
    private var solvers: Array<SpringSolver>? = null
    private var startNanos = 0L
    private var lastT = 0.0
    private var end: (() -> Unit)? = null

    var isRunning = false
        private set

    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val current = solvers
            if (!isRunning || current == null) return
            val t = ((frameTimeNanos - startNanos) / 1e9).coerceAtLeast(0.0)
            lastT = t
            var atRest = true
            for (i in props.indices) {
                props[i].setValue(target, (finals[i] + current[i].eval(t).first).toFloat())
                if (!current[i].isAtRest(t)) atRest = false
            }
            if (atRest || t > MAX_SECONDS) {
                for (i in props.indices) props[i].setValue(target, finals[i])
                onFrame()
                isRunning = false
                val done = end
                end = null
                done?.invoke()
                return
            }
            onFrame()
            choreographer.postFrameCallback(this)
        }
    }

    /** Springs every property to [to], starting at its current value with [velocities] (per second). */
    fun animateTo(
        to: FloatArray,
        velocities: FloatArray,
        stiffness: Float,
        dampingRatio: Float,
        onEnd: (() -> Unit)? = null,
    ) {
        cancel()
        finals = to.copyOf()
        solvers = Array(props.size) { i ->
            SpringSolver(stiffness, dampingRatio, props[i].getValue(target) - to[i], velocities.getOrElse(i) { 0f })
        }
        end = onEnd
        startNanos = System.nanoTime()
        lastT = 0.0
        isRunning = true
        choreographer.postFrameCallback(callback)
    }

    /** The property's velocity at the last frame, 0 when not moving. */
    fun velocity(index: Int): Float {
        val current = solvers
        return if (isRunning && current != null) current[index].velocity(lastT).toFloat() else 0f
    }

    /** Stops where it is; the end action doesn't run. */
    fun cancel() {
        if (isRunning) choreographer.removeFrameCallback(callback)
        isRunning = false
        end = null
    }

    companion object {
        const val MAX_SECONDS = 3.0
    }
}
