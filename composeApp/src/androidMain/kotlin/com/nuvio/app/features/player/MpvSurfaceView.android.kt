package com.nuvio.app.features.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import `is`.xyz.mpv.MPV
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal abstract class MpvSurfaceView(context: Context, private val vo: String) : FrameLayout(context) {
    val mpv = MPV()
    protected val mpvDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NuvioLibmpv").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val mpvScope = CoroutineScope(SupervisorJob() + mpvDispatcher)
    protected val surfaceReady = CompletableDeferred<Unit>()
    protected val released = AtomicBoolean(false)
    private var destroyJob: Job? = null
    private var surfaceAttached = false

    // Nuvio RS: the app's floating window borrows the picture while the player screen is away. The
    // screen's own surface is kept meanwhile (not drawn into) and gets the picture back afterwards.
    // These four are touched on the main thread only.
    private var screenOutput: Surface? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var externalSurface: Surface? = null

    protected abstract fun initOptions()
    protected abstract fun observeProperties()

    fun initialize(configDir: String, cacheDir: String) {
        mpv.create(context)
        mpv.setOptionString("config", "yes")
        mpv.setOptionString("config-dir", configDir)
        mpv.setOptionString("gpu-shader-cache-dir", cacheDir)
        mpv.setOptionString("icc-cache-dir", cacheDir)
        mpv.setOptionString("vo", vo)
        initOptions()
        mpv.init()
        mpv.setOptionString("force-window", "no")
        mpv.setOptionString("idle", "once")
        observeProperties()
        addView(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) createSurfaceView() else createTextureView())
    }

    protected fun executeMpv(block: () -> Unit) {
        if (released.get()) return
        mpvScope.launch {
            if (!released.get()) runCatching(block).onFailure(::logFailure)
        }
    }

    private fun attach(surface: Surface) = executeMpv { attachNow(surface) }

    private fun attachNow(surface: Surface) {
        mpv.attachSurface(surface)
        surfaceAttached = true
        mpv.setOptionString("force-window", "yes")
        mpv.setPropertyString("vo", vo)
        surfaceReady.complete(Unit)
    }

    private fun resize(width: Int, height: Int) = executeMpv { resizeNow(width, height) }

    private fun resizeNow(width: Int, height: Int) {
        if (width > 0 && height > 0) mpv.setPropertyString("android-surface-size", "${width}x$height")
    }

    /** The screen's surface is there: mpv draws into it unless the floating window has the picture. */
    private fun showScreenSurface(surface: Surface, width: Int, height: Int, resizeNow: Boolean) {
        screenOutput = surface
        screenWidth = width
        screenHeight = height
        if (externalSurface != null) {
            Log.i(TAG, "screen surface kept aside: the floating window has the picture")
            return
        }
        attach(surface)
        if (resizeNow) resize(width, height)
    }

    private fun resizeScreenSurface(width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
        if (externalSurface == null) resize(width, height)
    }

    private fun dropScreenSurface(releaseSurface: () -> Unit) {
        screenOutput = null
        if (externalSurface == null) {
            detach(releaseSurface)
        } else {
            // mpv draws into the floating window: only let go of the screen's surface, once what
            // was already sent to mpv (the hand-over away from it) has run.
            val job = destroyJob ?: mpvScope.launch { }
            job.invokeOnCompletion { releaseSurface() }
        }
    }

    /** Nuvio RS: mpv draws into [surface] (the app's floating window) instead of the screen. */
    fun attachExternalSurface(surface: Surface, width: Int, height: Int) {
        Log.i(TAG, "floating surface attach ${width}x$height released=${released.get()}")
        if (released.get()) return
        externalSurface = surface
        runOnMpvBriefly {
            detachSurface()
            attachNow(surface)
            resizeNow(width, height)
        }
    }

    fun resizeExternalSurface(width: Int, height: Int) {
        if (released.get() || externalSurface == null) return
        // Off the main thread: a busy mpv core must not freeze the floating window.
        resize(width, height)
    }

    /** Nuvio RS: the floating window closed; the screen gets the picture back when it is showing. */
    fun detachExternalSurface() {
        Log.i(TAG, "floating surface detach, had=${externalSurface != null}")
        if (externalSurface == null) return
        externalSurface = null
        if (released.get()) return
        val screen = screenOutput?.takeIf { it.isValid }
        val width = screenWidth
        val height = screenHeight
        runOnMpvBriefly {
            detachSurface()
            if (screen != null) {
                attachNow(screen)
                resizeNow(width, height)
            }
        }
    }

    /**
     * Nuvio RS: hands the picture over on mpv's own thread (in order with everything else sent to
     * mpv) and waits for it only briefly. A busy mpv, such as one still opening a file from a slow
     * drive, then finishes the hand-over there instead of holding the screen while the app goes to
     * the background or comes back, which Android reports as "isn't responding".
     */
    private fun runOnMpvBriefly(block: () -> Unit) {
        val done = CountDownLatch(1)
        executeMpv {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        done.await(SURFACE_HANDOVER_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    private fun detach(releaseSurface: () -> Unit) {
        val job = destroyJob ?: mpvScope.launch { detachSurface() }
        job.invokeOnCompletion { releaseSurface() }
    }

    private fun detachSurface() {
        if (!surfaceAttached) return
        surfaceAttached = false
        runCatching {
            try {
                mpv.setPropertyString("vo", "null")
                mpv.setPropertyString("force-window", "no")
            } finally {
                mpv.detachSurface()
            }
        }.onFailure(::logFailure)
    }

    fun releaseMpv() {
        if (!released.compareAndSet(false, true)) return
        surfaceReady.cancel()
        destroyJob = mpvScope.launch {
            try {
                detachSurface()
                runCatching { mpv.destroy() }.onFailure(::logFailure)
            } finally {
                mpvDispatcher.close()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createSurfaceView() = SurfaceView(context).apply {
        holder.addCallback(object : SurfaceHolder.Callback {
            private lateinit var layer: SurfaceControl
            private lateinit var output: Surface

            override fun surfaceCreated(holder: SurfaceHolder) {
                val frame = holder.surfaceFrame
                layer = SurfaceControl.Builder()
                    .setName("MPV video")
                    .setParent(surfaceControl)
                    .setBufferSize(frame.width(), frame.height())
                    .build()
                output = Surface(layer)
                SurfaceControl.Transaction().use {
                    it.setLayer(layer, 1).setVisibility(layer, true).apply()
                }
                showScreenSurface(output, frame.width(), frame.height(), resizeNow = false)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                SurfaceControl.Transaction().use { it.setBufferSize(layer, width, height).apply() }
                resizeScreenSurface(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                val oldLayer = layer
                val oldOutput = output
                SurfaceControl.Transaction().use { it.reparent(oldLayer, null).apply() }
                dropScreenSurface {
                    oldOutput.release()
                    oldLayer.release()
                }
            }
        })
    }

    private fun createTextureView() = TextureView(context).apply {
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            private lateinit var output: Surface

            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                output = Surface(texture)
                showScreenSurface(output, width, height, resizeNow = true)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                resizeScreenSurface(width, height)
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                val oldOutput = output
                dropScreenSurface {
                    oldOutput.release()
                    texture.release()
                }
                return false
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
    }

    private fun logFailure(error: Throwable) {
        Log.w(TAG, "libmpv operation failed", error)
    }

    private companion object {
        const val TAG = "MpvSurfaceView"
        const val SURFACE_HANDOVER_WAIT_MS = 300L
    }
}
