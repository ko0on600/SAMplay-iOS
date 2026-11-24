package com.nuvio.app.features.player

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.nuvio.app.R
import com.nuvio.app.features.player.floating.FloatProperty
import com.nuvio.app.features.player.floating.FloatTouchLayout
import com.nuvio.app.features.player.floating.MotionSpec
import com.nuvio.app.features.player.floating.PipPhysics
import com.nuvio.app.features.player.floating.WindowSpring
import com.nuvio.app.features.player.floating.shadowFadeWidth
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Subtitle layers inside the floating window: the stream's own cues and the add-on (sidecar) cues. */
internal class FloatingSubtitleViews(val embedded: SubtitleView, val sidecar: SubtitleView)

/** What the floating window needs from the player engine playing behind it. */
internal interface FloatingVideoTarget {
    /** Sends the picture (and, where the engine draws them separately, the subtitles) to the window. */
    fun attachFloatingSurface(surface: Surface, width: Int, height: Int, subtitles: FloatingSubtitleViews)
    fun resizeFloatingSurface(width: Int, height: Int)
    /** Gives the picture back to the player screen. */
    fun detachFloatingSurface()
    val isPlaying: Boolean
    /** Picture width / height, or 0 while unknown. */
    val videoAspect: Float
    /** Played fraction 0..1, or negative while unknown (live streams). */
    val progress: Float
    fun togglePlayback()
    fun seekBy(deltaMs: Long)
    fun pause()
}

/**
 * Nuvio RS: the app's own floating video window, drawn over other apps, so it behaves the same on
 * every phone, like the iPhone's: drag it and it springs to the nearest resting spot, throw or push
 * it against a side and it tucks away leaving a tab with an arrow (tap or pull the tab to bring it
 * back, slide it to move it), pinch to resize, tap for close / back to the app / back 10 s /
 * play-pause / forward 10 s. Needs "Display over other apps"; without it the system
 * picture-in-picture is used.
 *
 * Two windows: the card (picture and controls, inside a margin for the lift shadow and scale) and,
 * while tucked, the tab on the screen edge. Geometry is in screen pixels: [axXValue], [axYValue]
 * are the card's top left; the card's window sits [pad] further out.
 */
@SuppressLint("StaticFieldLeak")
internal object FloatingPlayerWindow {
    private const val STEP_MS = 10_000L

    // SAMplay 2.0.3: the outer part of the shadow room that fades (see FloatTouchLayout.edgeFade).
    private const val SHADOW_FADE_DP = 12f
    private const val SHADOW_FADE_GAP_DP = 2f
    private const val TAP_TIMEOUT_MS = 500L

    // The window shows once the screen has settled (the launcher turning back to portrait).
    private const val REVEAL_MIN_MS = 120L
    private const val REVEAL_MAX_MS = 700L
    private const val REVEAL_SETTLE_MS = 140L

    private enum class State { NORMAL, TUCKING, TUCKED, UNTUCKING }

    private val handler = Handler(Looper.getMainLooper())
    private var target: FloatingVideoTarget? = null
    private var windowManager: WindowManager? = null
    private var displayManager: DisplayManager? = null
    private var winCtx: Context? = null
    private var surface: Surface? = null
    private var liveTexture: SurfaceTexture? = null
    private var liveTextureWidth = 0
    private var liveTextureHeight = 0
    private var liveSubtitles: FloatingSubtitleViews? = null
    private var composing: KeepComposing? = null
    private var taskId: Int = -1
    private var permissionAsked = false

    /** Set while the window has held the playback, until the player screen is back in front. */
    private var heldPlayback = false

    // Views.
    private var root: FloatTouchLayout? = null
    private var card: FrameLayout? = null
    private var controls: FrameLayout? = null
    private var veil: View? = null
    private var playBtn: ImageButton? = null
    private var progress: ProgressBar? = null
    private var closeBtn: ImageButton? = null
    private var restoreBtn: ImageButton? = null
    private var rewBtn: ImageButton? = null
    private var fwdBtn: ImageButton? = null
    private var tab: FrameLayout? = null
    private var tabChevron: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var tabLp: WindowManager.LayoutParams? = null
    private var rootAttached = false
    private var tabAttached = false

    // Showing up and going away.
    private var released = false
    private var revealed = false
    private var shownAt = 0L
    private var lastDisplayChange = 0L
    private var waitForRotation = false

    // Geometry.
    private var density = 1f
    private var pad = 0f
    private var slop = 0f
    private var maxFling = 0f
    private var screenW = 0f
    private var screenH = 0f
    private var safe = PipPhysics.Bounds(0f, 0f, 0f, 0f)
    private var aspect = 16f / 9f
    private var lastAspectCheck = 0f
    private var cardW = 0f
    private var cardH = 0f
    private var axXValue = 0f
    private var axYValue = 0f
    private var tabAxYValue = 0f
    private var tabH = 0f
    private var state = State.NORMAL
    private var tuckSide = PipPhysics.Side.LEFT

    // Where the user left it, kept for the session.
    private var anchorIndex = 3
    private var widthFraction = MotionSpec.DEFAULT_WIDTH_FRACTION

    private var move: WindowSpring<Unit>? = null
    private var scale: WindowSpring<View>? = null
    private var tabMove: WindowSpring<Unit>? = null

    // The touch in progress on the card.
    private var velocity: VelocityTracker? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var downTime = 0L
    private var startCardX = 0f
    private var startCardY = 0f
    private var dragging = false
    private var pinching = false
    private var pinchHappened = false
    private var pinchStartSpan = 0f
    private var pinchStartW = 0f
    private var toChildren = false

    private val hideControlsRunnable = Runnable { hideControls() }

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (rootAttached) handler.postDelayed(this, 200L)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            lastDisplayChange = SystemClock.uptimeMillis()
            handler.post { onMetricsMaybeChanged() }
        }
    }

    private val revealCheck = object : Runnable {
        override fun run() {
            if (released || revealed) return
            val now = SystemClock.uptimeMillis()
            val settled = if (lastDisplayChange > 0L) {
                now - lastDisplayChange >= REVEAL_SETTLE_MS
            } else {
                !waitForRotation || now - shownAt >= REVEAL_MAX_MS
            }
            if (!settled && now - shownAt < REVEAL_MAX_MS) {
                handler.postDelayed(this, 32L)
                return
            }
            reveal()
        }
    }

    /** False as soon as it starts going away, even while the card still fades out. */
    val isShowing: Boolean
        get() = !released && (rootAttached || tabAttached)

    /**
     * The player engine now playing. While the window floats a new one (the next episode, or the
     * other engine) gets the window's picture straight away, so the window stays up.
     */
    fun register(player: FloatingVideoTarget) {
        if (target === player) return
        detachSurface()
        target = player
        val texture = liveTexture
        val subtitles = liveSubtitles
        if (rootAttached && texture != null && subtitles != null) {
            attachSurface(texture, liveTextureWidth, liveTextureHeight, subtitles)
        }
    }

    fun unregister(player: FloatingVideoTarget) {
        if (target !== player) return
        detachSurface()
        target = null
    }

    fun canDrawOverlays(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Settings.canDrawOverlays(context)

    /** True when the app's own window can take over from the system picture-in-picture. */
    fun isAvailable(context: Context): Boolean = target != null && canDrawOverlays(context)

    /** Opens the "Display over other apps" page, once per app session. */
    fun requestPermissionOnce(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || permissionAsked) return false
        permissionAsked = true
        return runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")),
            )
        }.isSuccess
    }

    fun show(activity: Activity): Boolean {
        if (isShowing) return true
        val player = target ?: return false
        if (!canDrawOverlays(activity)) return false
        // The last window may still be fading out.
        handler.removeCallbacksAndMessages(null)
        removeViews()
        released = false
        revealed = false
        state = State.NORMAL
        val context = overlayContext(activity)
        winCtx = context
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm
        displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        density = context.resources.displayMetrics.density
        pad = dp(18f)
        slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
        aspect = aspectOf(player)
        lastAspectCheck = aspect
        measureScreen()
        setCardSize(defaultCardW(), push = false)
        buildViews(context)
        placeOnAnchor()
        val layoutParams = WindowManager.LayoutParams().apply {
            type = overlayType()
            // Untouchable until it shows (see reveal).
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            cutoutMode(this)
            title = "SAMplay floating"
            width = (cardW + pad * 2f).roundToInt()
            height = (cardH + pad * 2f).roundToInt()
            x = (axXValue - pad).roundToInt()
            y = (axYValue - pad).roundToInt()
            alpha = 0f
        }
        params = layoutParams
        tabLp = WindowManager.LayoutParams().apply {
            type = overlayType()
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            cutoutMode(this)
            title = "SAMplay tab"
        }
        initSprings()
        val view = root ?: return false
        return runCatching {
            wm.addView(view, layoutParams)
            rootAttached = true
            taskId = activity.taskId
            heldPlayback = true
            keepComposing(activity)
            card?.apply {
                alpha = 0f
                scaleX = 0.6f
                scaleY = 0.6f
            }
            runCatching { displayManager?.registerDisplayListener(displayListener, handler) }
            shownAt = SystemClock.uptimeMillis()
            lastDisplayChange = 0L
            waitForRotation = screenW > screenH
            handler.postDelayed(revealCheck, REVEAL_MIN_MS)
            handler.post(ticker)
            handler.postDelayed({ onMetricsMaybeChanged() }, 300L)
            handler.postDelayed({ onMetricsMaybeChanged() }, 900L)
            true
        }.onFailure {
            rootAttached = false
            released = true
            root = null
        }.getOrDefault(false)
    }

    /** Takes the window away and gives the picture back to the player screen. */
    fun hide() = dismiss(animated = false)

    /**
     * The player screen is in front again: drops the window. True when the window had the playback,
     * so the screen keeps whatever play/pause state the user left it in.
     */
    fun returnToPlayer(): Boolean {
        val held = heldPlayback
        heldPlayback = false
        dismiss(animated = true)
        return held
    }

    /** The player screen closed: no window, and the next player starts as usual. */
    fun closeForPlayerExit() {
        heldPlayback = false
        dismiss(animated = false)
    }

    private fun dismiss(animated: Boolean) {
        if (released && !rootAttached && !tabAttached) return
        released = true
        handler.removeCallbacksAndMessages(null)
        move?.cancel()
        scale?.cancel()
        tabMove?.cancel()
        runCatching { displayManager?.unregisterDisplayListener(displayListener) }
        detachSurface()
        liveTexture = null
        liveSubtitles = null
        stopComposing()
        val closing = card
        if (animated && rootAttached && state == State.NORMAL && closing != null) {
            // Only this window: a new one may have shown meanwhile.
            val finish = { if (card === closing) removeViews() }
            closing.animate().scaleX(0.85f).scaleY(0.85f).alpha(0f).setDuration(160L).withEndAction(finish).start()
            handler.postDelayed(finish, 400L)
        } else {
            removeViews()
        }
    }

    private fun removeViews() {
        val wm = windowManager
        val rootView = root
        val tabView = tab
        if (rootAttached && rootView != null) runCatching { wm?.removeViewImmediate(rootView) }
        if (tabAttached && tabView != null) runCatching { wm?.removeViewImmediate(tabView) }
        rootAttached = false
        tabAttached = false
        root = null
        card = null
        params = null
        tabLp = null
        tab = null
        windowManager = null
        winCtx = null
    }

    /** A context made for an overlay window, so sizes and rotation follow the screen, not the app. */
    private fun overlayContext(activity: Activity): Context {
        val app = activity.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return app
        return runCatching {
            val display = (activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY) ?: return app
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                app.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else {
                app.createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            }
        }.getOrDefault(app)
    }

    private fun aspectOf(player: FloatingVideoTarget?): Float =
        player?.videoAspect?.takeIf { it > 0f }?.coerceIn(0.56f, 2.39f) ?: (16f / 9f)

    /**
     * The player screen is stopped while the window floats, and Compose then holds its effects
     * (next episode, saving progress); keep them running until the window goes away.
     */
    private class KeepComposing(
        val activity: ComponentActivity,
        val recomposer: Recomposer,
        val observer: LifecycleEventObserver,
    )

    private fun keepComposing(activity: Activity) {
        stopComposing()
        val owner = activity as? ComponentActivity ?: return
        val recomposer = activity.findViewById<View>(android.R.id.content)
            ?.findViewTreeCompositionContext() as? Recomposer ?: return
        val observer = LifecycleEventObserver { _, event ->
            // After every observer has seen the stop, Compose's own pause included.
            if (event == Lifecycle.Event.ON_STOP) handler.post { if (isShowing) recomposer.resumeCompositionFrameClock() }
        }
        owner.lifecycle.addObserver(observer)
        composing = KeepComposing(owner, recomposer, observer)
    }

    private fun stopComposing() {
        val current = composing ?: return
        composing = null
        current.activity.lifecycle.removeObserver(current.observer)
        if (!current.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            current.recomposer.pauseCompositionFrameClock()
        }
    }

    private fun attachSurface(texture: SurfaceTexture, width: Int, height: Int, subtitles: FloatingSubtitleViews) {
        liveTexture = texture
        liveTextureWidth = width
        liveTextureHeight = height
        liveSubtitles = subtitles
        val player = target ?: return
        detachSurface()
        val newSurface = Surface(texture)
        surface = newSurface
        runCatching { player.attachFloatingSurface(newSurface, width, height, subtitles) }
    }

    private fun detachSurface() {
        val current = surface ?: return
        surface = null
        runCatching { target?.detachFloatingSurface() }
        current.release()
    }

    private fun openApp(context: Context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val task = runCatching {
            manager?.appTasks?.firstOrNull { appTask ->
                val info = appTask.taskInfo ?: return@firstOrNull false
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.taskId else @Suppress("DEPRECATION") info.id) == taskId
            }
        }.getOrNull()
        if (task != null && runCatching { task.moveToFront() }.isSuccess) return
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return
        runCatching { context.startActivity(intent) }
    }

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun cutoutMode(p: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun dp(v: Float): Float = v * density

    private fun buildViews(context: Context) {
        fun button(icon: Int, size: Int, padding: Int, label: String, onClick: () -> Unit) = ImageButton(context).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            setPadding(padding, padding, padding, padding)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundResource(R.drawable.sam_float_bg_round_ripple)
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(0xF2FFFFFF.toInt())
            contentDescription = label
            setOnClickListener { onClick() }
        }

        val rootView = FloatTouchLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        val cardView = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.sam_float_bg_card)
            elevation = dp(10f)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(MotionSpec.CORNER_RADIUS_DP))
                }
            }
            clipToOutline = true
        }
        val textureView = TextureView(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val embedded = subtitleView(context)
        val sidecar = subtitleView(context)
        val controlsView = FrameLayout(context).apply {
            setBackgroundColor(0x66000000)
            alpha = 0f
            visibility = View.GONE
        }
        // Covers the picture while it slides into the edge.
        val veilView = View(context).apply {
            setBackgroundColor(0xE0707078.toInt())
            alpha = 0f
        }
        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressDrawable = context.getDrawable(R.drawable.sam_float_progress_thin)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val close = button(R.drawable.sam_float_ic_close, dp(34f).roundToInt(), dp(8f).roundToInt(), "Close") {
            target?.pause()
            heldPlayback = false
            dismiss(animated = true)
        }
        val restore = button(R.drawable.sam_float_ic_fullscreen, dp(34f).roundToInt(), dp(9f).roundToInt(), "Restore") {
            openApp(context)
        }
        val rewind = button(R.drawable.sam_float_ic_replay10, dp(40f).roundToInt(), dp(8f).roundToInt(), "Rewind 10") {
            target?.seekBy(-STEP_MS)
            bumpControls()
            tick()
        }
        val play = button(R.drawable.sam_float_ic_pause, dp(50f).roundToInt(), dp(10f).roundToInt(), "Play") {
            // Some engines report the new state only later.
            val wasPlaying = target?.isPlaying == true
            target?.togglePlayback()
            updatePlayIcon(!wasPlaying)
            bumpControls()
        }
        val forward = button(R.drawable.sam_float_ic_forward10, dp(40f).roundToInt(), dp(8f).roundToInt(), "Forward 10") {
            target?.seekBy(STEP_MS)
            bumpControls()
            tick()
        }
        close.layoutParams = FrameLayout.LayoutParams(dp(34f).roundToInt(), dp(34f).roundToInt(), Gravity.TOP or Gravity.START)
            .apply { setMargins(dp(4f).roundToInt(), dp(4f).roundToInt(), 0, 0) }
        restore.layoutParams = FrameLayout.LayoutParams(dp(34f).roundToInt(), dp(34f).roundToInt(), Gravity.TOP or Gravity.END)
            .apply { setMargins(0, dp(4f).roundToInt(), dp(4f).roundToInt(), 0) }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            addView(rewind)
            addView(
                play,
                LinearLayout.LayoutParams(dp(50f).roundToInt(), dp(50f).roundToInt()).apply {
                    marginStart = dp(14f).roundToInt()
                    marginEnd = dp(14f).roundToInt()
                },
            )
            addView(forward)
        }
        controlsView.addView(close)
        controlsView.addView(restore)
        controlsView.addView(row, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        controlsView.addView(
            progressBar,
            FrameLayout.LayoutParams(MATCH_PARENT, dp(3f).roundToInt(), Gravity.BOTTOM).apply {
                setMargins(dp(12f).roundToInt(), 0, dp(12f).roundToInt(), dp(7f).roundToInt())
            },
        )
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                attachSurface(st, width, height, FloatingSubtitleViews(embedded, sidecar))
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                liveTextureWidth = width
                liveTextureHeight = height
                runCatching { target?.resizeFloatingSurface(width, height) }
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                detachSurface()
                if (liveTexture === st) liveTexture = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
        cardView.addView(textureView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        cardView.addView(embedded, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        cardView.addView(sidecar, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        cardView.addView(controlsView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        cardView.addView(veilView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        rootView.addView(
            cardView,
            FrameLayout.LayoutParams(cardW.roundToInt(), cardH.roundToInt()).apply {
                leftMargin = pad.roundToInt()
                topMargin = pad.roundToInt()
                gravity = Gravity.TOP or Gravity.START
            },
        )

        val tabView = FrameLayout(context)
        val chevron = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(18f).roundToInt(), dp(26f).roundToInt(), Gravity.CENTER)
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = ColorStateList.valueOf(0xF0FFFFFF.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        tabView.addView(chevron)
        tabView.setOnTouchListener(TabTouch())

        rootView.callback = object : FloatTouchLayout.Callback {
            override fun routeToChildren(down: MotionEvent): Boolean = routeToChildrenImpl(down)

            override fun onWindowTouch(e: MotionEvent): Boolean = onWindowTouchImpl(e)
        }

        root = rootView
        rootView.edgeFade = shadowFade()
        card = cardView
        controls = controlsView
        veil = veilView
        playBtn = play
        progress = progressBar
        closeBtn = close
        restoreBtn = restore
        rewBtn = rewind
        fwdBtn = forward
        tab = tabView
        tabChevron = chevron
    }

    private fun subtitleView(context: Context) = SubtitleView(context).apply {
        setStyle(
            CaptionStyleCompat(
                Color.WHITE,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                Color.BLACK,
                null,
            ),
        )
        setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 0.85f)
    }

    private fun initSprings() {
        val axX = object : FloatProperty<Unit> {
            override fun getValue(target: Unit): Float = axXValue

            override fun setValue(target: Unit, value: Float) {
                axXValue = value
                params?.x = (value - pad).roundToInt()
            }
        }
        val axY = object : FloatProperty<Unit> {
            override fun getValue(target: Unit): Float = axYValue

            override fun setValue(target: Unit, value: Float) {
                axYValue = value
                params?.y = (value - pad).roundToInt()
            }
        }
        val tabAxY = object : FloatProperty<Unit> {
            override fun getValue(target: Unit): Float = tabAxYValue

            override fun setValue(target: Unit, value: Float) {
                tabAxYValue = value
                tabLp?.y = value.roundToInt()
            }
        }
        move = WindowSpring(Unit, listOf(axX, axY)) { pushLayout() }
        scale = card?.let { cardView ->
            WindowSpring(
                cardView,
                listOf(
                    object : FloatProperty<View> {
                        override fun getValue(target: View): Float = target.scaleX

                        override fun setValue(target: View, value: Float) {
                            target.scaleX = value
                        }
                    },
                    object : FloatProperty<View> {
                        override fun getValue(target: View): Float = target.scaleY

                        override fun setValue(target: View, value: Float) {
                            target.scaleY = value
                        }
                    },
                ),
            ) {}
        }
        tabMove = WindowSpring(Unit, listOf(tabAxY)) { pushTab() }
    }

    /** Reads the screen size and where the window may rest; true when either changed. */
    private fun measureScreen(): Boolean {
        val wm = windowManager ?: return false
        val context = winCtx ?: return false
        val oldW = screenW
        val oldH = screenH
        val oldSafe = safe
        val top: Int
        val bottom: Int
        val left: Int
        val right: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            screenW = metrics.bounds.width().toFloat()
            screenH = metrics.bounds.height().toFloat()
            val insets = metrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            top = insets.top
            bottom = insets.bottom
            left = insets.left
            right = insets.right
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenW = metrics.widthPixels.toFloat()
            screenH = metrics.heightPixels.toFloat()
            top = dimen(context, "status_bar_height")
            bottom = if (screenH > screenW) dimen(context, "navigation_bar_height") else 0
            left = 0
            right = 0
        }
        val margin = dp(MotionSpec.EDGE_MARGIN_DP)
        safe = PipPhysics.Bounds(left + margin, top + dp(8f), screenW - right - margin, screenH - bottom - margin)
        return oldW != screenW || oldH != screenH || oldSafe != safe
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun dimen(context: Context, name: String): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    private fun maxCardW(): Float =
        min(min(screenW, screenH) * MotionSpec.MAX_WIDTH_FRACTION, safe.height * 0.6f * aspect)

    private fun minCardW(): Float =
        min(screenW, screenH) * MotionSpec.MIN_WIDTH_FRACTION * (if (aspect < 1f) 0.7f else 1f)

    private fun defaultCardW(): Float =
        min(screenW, screenH) * widthFraction.coerceIn(MotionSpec.MIN_WIDTH_FRACTION, MotionSpec.MAX_WIDTH_FRACTION)

    /** The card's width kept within the limits for this screen and picture. */
    private fun fittedCardW(): Float =
        cardW.coerceIn(minCardW(), max(minCardW(), maxCardW())).takeIf { it > 0f } ?: defaultCardW()

    private fun setCardSize(w: Float, push: Boolean = true) {
        val (width, height) = PipPhysics.clampSize(w, aspect, minCardW(), max(minCardW(), maxCardW()))
        cardW = width
        cardH = height
        val cardView = card ?: return
        val layoutParams = cardView.layoutParams as FrameLayout.LayoutParams
        layoutParams.width = cardW.roundToInt()
        layoutParams.height = cardH.roundToInt()
        layoutParams.leftMargin = pad.roundToInt()
        layoutParams.topMargin = pad.roundToInt()
        layoutParams.gravity = Gravity.TOP or Gravity.START
        cardView.layoutParams = layoutParams
        cardView.invalidateOutline()
        root?.edgeFade = shadowFade()
        params?.width = (cardW + pad * 2f).roundToInt()
        params?.height = (cardH + pad * 2f).roundToInt()
        if (push) pushLayout()
    }

    private fun shadowFade(): Float =
        shadowFadeWidth(pad, cardW, cardH, MotionSpec.LIFT_SCALE, dp(SHADOW_FADE_GAP_DP), dp(SHADOW_FADE_DP))

    private fun setCardPos(x: Float, y: Float) {
        axXValue = x
        axYValue = y
        params?.x = (x - pad).roundToInt()
        params?.y = (y - pad).roundToInt()
    }

    private fun pushLayout() {
        val wm = windowManager
        val view = root
        val layoutParams = params
        if (!rootAttached || wm == null || view == null || layoutParams == null) return
        runCatching { wm.updateViewLayout(view, layoutParams) }
    }

    private fun pushTab() {
        val wm = windowManager
        val view = tab
        val layoutParams = tabLp
        if (!tabAttached || wm == null || view == null || layoutParams == null) return
        runCatching { wm.updateViewLayout(view, layoutParams) }
    }

    private fun placeOnAnchor() {
        val spots = PipPhysics.anchors(safe, cardW, cardH)
        if (spots.isEmpty()) {
            setCardPos(safe.left, safe.top)
            return
        }
        anchorIndex = anchorIndex.coerceIn(0, spots.size - 1)
        val (x, y) = spots[anchorIndex]
        setCardPos(x, y)
    }

    private fun reveal() {
        if (revealed || released) return
        revealed = true
        runCatching {
            measureScreen()
            setCardSize(fittedCardW(), push = false)
            if (state == State.NORMAL) placeOnAnchor()
        }
        params?.let {
            it.alpha = 1f
            it.flags = it.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        pushLayout()
        card?.animate()?.alpha(1f)?.setDuration(MotionSpec.CONTROLS_FADE_MS)?.start()
        scale?.animateTo(floatArrayOf(1f, 1f), floatArrayOf(0f, 0f), MotionSpec.ENTER_STIFFNESS, MotionSpec.ENTER_DAMPING)
    }

    private fun onMetricsMaybeChanged() {
        if (released || !measureScreen()) return
        setCardSize(fittedCardW(), push = false)
        if (state == State.TUCKED) {
            tabH = tabHeight()
            tabAxYValue = PipPhysics.clamp(tabAxYValue, safe.top, safe.bottom - tabH)
            tabLp?.y = tabAxYValue.roundToInt()
            placeTab()
            setCardPos(PipPhysics.tuckedX(tuckSide, screenW, cardW, dp(MotionSpec.TAB_VISIBLE_DP)), axYValue)
            pushLayout()
            return
        }
        if (dragging || pinching) return
        if (state != State.NORMAL) {
            // Stopped halfway into or out of the edge: show the picture again.
            veil?.animate()?.cancel()
            veil?.animate()?.alpha(0f)?.setDuration(120L)?.start()
        }
        state = State.NORMAL
        move?.cancel()
        placeOnAnchor()
        pushLayout()
    }

    private fun lift(up: Boolean) {
        val to = if (up) MotionSpec.LIFT_SCALE else 1f
        scale?.animateTo(floatArrayOf(to, to), floatArrayOf(0f, 0f), MotionSpec.LIFT_STIFFNESS, MotionSpec.LIFT_DAMPING)
        card?.animate()?.translationZ(if (up) dp(8f) else 0f)?.setDuration(150L)?.start()
    }

    private fun snapTo(
        x: Float,
        y: Float,
        vx: Float,
        vy: Float,
        stiffness: Float,
        damping: Float,
        end: (() -> Unit)? = null,
    ) {
        move?.animateTo(floatArrayOf(x, y), floatArrayOf(vx, vy), stiffness, damping, end)
    }

    /** True when the touch starts on a shown button, so the button gets it too. */
    private fun routeToChildrenImpl(down: MotionEvent): Boolean {
        toChildren = false
        val controlsView = controls
        if (state != State.NORMAL || controlsView == null || controlsView.visibility != View.VISIBLE || controlsView.alpha < 0.5f) {
            return false
        }
        val cardView = card ?: return false
        val location = IntArray(2)
        val rect = Rect()
        for (button in listOfNotNull(closeBtn, restoreBtn, rewBtn, playBtn, fwdBtn)) {
            if (!button.isShown) continue
            button.getLocationInWindow(location)
            rect.set(
                location[0],
                location[1],
                location[0] + (button.width * cardView.scaleX).roundToInt(),
                location[1] + (button.height * cardView.scaleY).roundToInt(),
            )
            rect.inset(-dp(4f).toInt(), -dp(4f).toInt())
            if (rect.contains(down.x.roundToInt(), down.y.roundToInt())) {
                toChildren = true
                return true
            }
        }
        return false
    }

    private fun onWindowTouchImpl(e: MotionEvent): Boolean {
        if (state == State.TUCKED) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                measureScreen()
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                move?.cancel()
                if (state == State.TUCKING || state == State.UNTUCKING) {
                    // Caught on the way: it stays where the finger has it.
                    state = State.NORMAL
                    veil?.animate()?.cancel()
                    veil?.animate()?.alpha(0f)?.setDuration(120L)?.start()
                }
                downRawX = e.rawX
                downRawY = e.rawY
                downTime = e.eventTime
                startCardX = axXValue
                startCardY = axYValue
                dragging = false
                pinching = false
                pinchHappened = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount >= 2 && state == State.NORMAL) {
                pinching = true
                pinchHappened = true
                dragging = false
                pinchStartSpan = span(e)
                pinchStartW = cardW
                lift(false)
            }
        }
        velocity?.let { addRaw(it, e) }
        when (e.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val tracker = velocity
                tracker?.computeCurrentVelocity(1000, maxFling)
                val vx = tracker?.xVelocity ?: 0f
                val vy = tracker?.yVelocity ?: 0f
                val wasDragging = dragging
                if (wasDragging) {
                    release(vx, vy)
                } else if (pinchHappened) {
                    if (pinching) {
                        pinching = false
                        settle()
                    }
                } else if (!toChildren && e.eventTime - downTime < TAP_TIMEOUT_MS) {
                    toggleControls()
                }
                dragging = false
                pinching = false
                velocity?.recycle()
                velocity = null
                return wasDragging
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinching && e.pointerCount >= 2) {
                    val span = span(e)
                    if (pinchStartSpan > 0f && span > 0f) {
                        // Grow around the card's centre.
                        val centerX = axXValue + cardW / 2f
                        val centerY = axYValue + cardH / 2f
                        setCardSize(pinchStartW * span / pinchStartSpan, push = false)
                        setCardPos(centerX - cardW / 2f, centerY - cardH / 2f)
                        pushLayout()
                    }
                    return true
                }
                if (pinchHappened) return true
                if (!dragging && hypot(e.rawX - downRawX, e.rawY - downRawY) > slop) {
                    dragging = true
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startCardX = axXValue
                    startCardY = axYValue
                    lift(true)
                    if (controls?.visibility == View.VISIBLE) bumpControls()
                }
                if (dragging) {
                    var y = startCardY + (e.rawY - downRawY)
                    val top = safe.top
                    val bottom = safe.bottom - cardH
                    if (y < top) {
                        y = top + PipPhysics.rubberBand(y - top, screenH)
                    } else if (y > bottom) {
                        y = bottom + PipPhysics.rubberBand(y - bottom, screenH)
                    }
                    setCardPos(startCardX + (e.rawX - downRawX), y)
                    pushLayout()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging || pinching) {
                    lift(false)
                    settle()
                }
                dragging = false
                pinching = false
                velocity?.recycle()
                velocity = null
            }
            MotionEvent.ACTION_POINTER_UP -> if (pinching && e.pointerCount <= 2) {
                pinching = false
                widthFraction = cardW / min(screenW, screenH)
                settle()
            }
        }
        return dragging || pinching || pinchHappened
    }

    private fun span(e: MotionEvent): Float =
        if (e.pointerCount < 2) 0f else hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    /** Velocity in screen coordinates: the window moves under the finger. */
    private fun addRaw(tracker: VelocityTracker, e: MotionEvent) {
        val raw = MotionEvent.obtain(e)
        raw.setLocation(e.rawX, e.rawY)
        tracker.addMovement(raw)
        raw.recycle()
    }

    private fun release(vx: Float, vy: Float) {
        lift(false)
        val config = PipPhysics.Config(
            tuckVelocity = dp(MotionSpec.TUCK_VELOCITY_DP),
            tabVisible = dp(MotionSpec.TAB_VISIBLE_DP),
        )
        when (val landing = PipPhysics.resolveRelease(axXValue, axYValue, vx, vy, cardW, cardH, screenW, safe, config)) {
            is PipPhysics.Target.Anchor -> {
                anchorIndex = landing.index
                snapTo(landing.x, landing.y, vx, vy, MotionSpec.SNAP_STIFFNESS, MotionSpec.SNAP_DAMPING)
            }
            is PipPhysics.Target.Tuck -> tuck(landing.side, landing.x, landing.y, vx, vy)
        }
    }

    private fun settle() {
        val spots = PipPhysics.anchors(safe, cardW, cardH)
        if (spots.isEmpty()) return
        anchorIndex = PipPhysics.nearestAnchor(axXValue, axYValue, spots)
        val (x, y) = spots[anchorIndex]
        snapTo(x, y, 0f, 0f, MotionSpec.SNAP_STIFFNESS, MotionSpec.SNAP_DAMPING)
    }

    private fun tabHeight(): Float = max(dp(72f), min(dp(110f), cardH * MotionSpec.TAB_HEIGHT_FRACTION))

    private fun tuck(side: PipPhysics.Side, x: Float, y: Float, vx: Float, vy: Float) {
        state = State.TUCKING
        tuckSide = side
        hideControls(immediate = true)
        veil?.animate()?.cancel()
        veil?.animate()?.alpha(1f)?.setDuration(140L)?.start()
        snapTo(x, y, vx, vy * 0.5f, MotionSpec.TUCK_STIFFNESS, MotionSpec.TUCK_DAMPING) {
            if (state == State.TUCKING) finishTuck()
        }
    }

    /** Off the screen the card hides and stops taking touches; the tab takes its place. */
    private fun finishTuck() {
        state = State.TUCKED
        veil?.alpha = 1f
        tabH = tabHeight()
        tabAxYValue = PipPhysics.clamp(axYValue + cardH / 2f - tabH / 2f, safe.top, safe.bottom - tabH)
        tabLp?.y = tabAxYValue.roundToInt()
        root?.visibility = View.INVISIBLE
        params?.let { it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE }
        pushLayout()
        showTab(fromScaleY = cardH / tabH)
    }

    private fun placeTab() {
        val tabWidth = dp(MotionSpec.TAB_VISIBLE_DP)
        tabLp?.width = tabWidth.roundToInt()
        tabLp?.height = tabH.roundToInt()
        tabLp?.x = if (tuckSide == PipPhysics.Side.LEFT) 0 else (screenW - tabWidth).roundToInt()
        pushTab()
    }

    private fun showTab(fromScaleY: Float) {
        val tabWidth = dp(MotionSpec.TAB_VISIBLE_DP)
        val radius = dp(12f)
        val tabView = tab ?: return
        tabView.background = GradientDrawable().apply {
            setColor(0xC2707078.toInt())
            // Rounded on the screen side only.
            cornerRadii = if (tuckSide == PipPhysics.Side.LEFT) {
                floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
            } else {
                floatArrayOf(radius, radius, 0f, 0f, 0f, 0f, radius, radius)
            }
        }
        tabChevron?.setImageResource(
            if (tuckSide == PipPhysics.Side.LEFT) R.drawable.sam_float_ic_chevron_right else R.drawable.sam_float_ic_chevron_left,
        )
        tabLp?.width = tabWidth.roundToInt()
        tabLp?.height = tabH.roundToInt()
        tabLp?.x = if (tuckSide == PipPhysics.Side.LEFT) 0 else (screenW - tabWidth).roundToInt()
        val wm = windowManager
        val layoutParams = tabLp
        if (!tabAttached && wm != null && layoutParams != null) {
            runCatching {
                wm.addView(tabView, layoutParams)
                tabAttached = true
            }
        } else {
            pushTab()
        }
        tabView.visibility = View.VISIBLE
        tabView.pivotY = tabH / 2f
        tabView.pivotX = if (tuckSide == PipPhysics.Side.LEFT) 0f else tabWidth
        // Grows out of the card's height.
        tabView.scaleY = fromScaleY.coerceIn(0.5f, 3f)
        tabView.alpha = 1f
        tabView.animate().scaleY(1f).setDuration(220L).setInterpolator(OvershootInterpolator(1.2f)).start()
        tabChevron?.alpha = 0f
        tabChevron?.animate()?.alpha(1f)?.setStartDelay(60L)?.setDuration(160L)?.start()
    }

    private fun untuck(vx: Float) {
        if (state != State.TUCKED) return
        tabMove?.cancel()
        state = State.UNTUCKING
        setCardPos(
            PipPhysics.tuckedX(tuckSide, screenW, cardW, dp(MotionSpec.TAB_VISIBLE_DP)),
            PipPhysics.clamp(tabAxYValue + tabH / 2f - cardH / 2f, safe.top, safe.bottom - cardH),
        )
        params?.let { it.flags = it.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() }
        pushLayout()
        root?.visibility = View.VISIBLE
        card?.alpha = 1f
        veil?.alpha = 1f
        veil?.animate()?.alpha(0f)?.setStartDelay(40L)?.setDuration(220L)?.start()
        tab?.animate()?.alpha(0f)?.setDuration(90L)?.withEndAction { removeTab() }?.start()
        handler.postDelayed({ removeTab() }, 300L)
        val (x, y) = PipPhysics.untuckTarget(tuckSide, axYValue, cardW, cardH, safe)
        anchorIndex = PipPhysics.nearestAnchor(x, y, PipPhysics.anchors(safe, cardW, cardH))
        val outwards = if (tuckSide == PipPhysics.Side.LEFT) max(0f, vx) else min(0f, vx)
        snapTo(x, y, outwards, 0f, MotionSpec.UNTUCK_STIFFNESS, MotionSpec.UNTUCK_DAMPING) {
            if (state == State.UNTUCKING) state = State.NORMAL
        }
        // Takes touches again before the spring has quite settled.
        handler.postDelayed({ if (state == State.UNTUCKING) state = State.NORMAL }, 120L)
    }

    private fun removeTab() {
        if (!tabAttached) return
        val tabView = tab
        tabView?.visibility = View.GONE
        runCatching { windowManager?.removeViewImmediate(tabView) }
        tabAttached = false
    }

    /** The tab: tap or pull it out to bring the window back, slide it up and down to move it. */
    private class TabTouch : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startY = 0f
        private var moved = false
        private var vt: VelocityTracker? = null

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            if (state != State.TUCKED) return true
            vt?.let { addRaw(it, e) }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tabMove?.cancel()
                    measureScreen()
                    downX = e.rawX
                    downY = e.rawY
                    startY = tabAxYValue
                    moved = false
                    vt?.recycle()
                    vt = VelocityTracker.obtain().also { addRaw(it, e) }
                    v.animate().scaleX(1.15f).setDuration(90L).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    var dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (tuckSide != PipPhysics.Side.LEFT) dx = -dx
                    if (dx > slop * 1.2f && abs(dx) > abs(dy)) {
                        vt?.computeCurrentVelocity(1000, maxFling)
                        val vx = vt?.xVelocity ?: 0f
                        v.scaleX = 1f
                        untuck(vx)
                        return true
                    }
                    if (abs(dy) > slop) moved = true
                    if (moved) {
                        tabAxYValue = PipPhysics.clamp(startY + dy, safe.top, safe.bottom - tabH)
                        tabLp?.y = tabAxYValue.roundToInt()
                        pushTab()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).setDuration(120L).start()
                    if (!moved && e.actionMasked == MotionEvent.ACTION_UP) {
                        untuck(0f)
                    } else if (moved) {
                        vt?.computeCurrentVelocity(1000, maxFling)
                        val vy = vt?.yVelocity ?: 0f
                        val to = PipPhysics.clamp(tabAxYValue + PipPhysics.project(vy), safe.top, safe.bottom - tabH)
                        tabMove?.animateTo(floatArrayOf(to), floatArrayOf(vy), MotionSpec.TAB_GLIDE_STIFFNESS, 1f)
                    }
                    vt?.recycle()
                    vt = null
                }
            }
            return true
        }
    }

    private fun toggleControls() {
        val controlsView = controls ?: return
        if (controlsView.visibility != View.VISIBLE || controlsView.alpha <= 0.5f) showControls() else hideControls()
    }

    private fun showControls(autoHide: Boolean = true) {
        val controlsView = controls ?: return
        handler.removeCallbacks(hideControlsRunnable)
        controlsView.animate().cancel()
        controlsView.visibility = View.VISIBLE
        controlsView.alpha = max(controlsView.alpha, 0.01f)
        controlsView.animate().alpha(1f).setDuration(MotionSpec.CONTROLS_FADE_MS).start()
        updatePlayIcon(target?.isPlaying == true)
        tick()
        if (autoHide) handler.postDelayed(hideControlsRunnable, MotionSpec.CONTROLS_AUTO_HIDE_MS)
    }

    /** Keeps the controls up a while longer. */
    private fun bumpControls() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, MotionSpec.CONTROLS_AUTO_HIDE_MS)
    }

    private fun hideControls(immediate: Boolean = false) {
        val controlsView = controls ?: return
        handler.removeCallbacks(hideControlsRunnable)
        controlsView.animate().cancel()
        if (immediate) {
            controlsView.alpha = 0f
            controlsView.visibility = View.GONE
        } else {
            controlsView.animate().alpha(0f).setDuration(MotionSpec.CONTROLS_FADE_MS)
                .withEndAction { controlsView.visibility = View.GONE }
                .start()
        }
    }

    private fun updatePlayIcon(playing: Boolean) {
        playBtn?.setImageResource(if (playing) R.drawable.sam_float_ic_pause else R.drawable.sam_float_ic_play)
    }

    /** Every 200 ms: play state, progress, and the card's shape when the picture's changes. */
    private fun tick() {
        if (released) return
        val player = target
        updatePlayIcon(player?.isPlaying == true)
        val played = player?.progress ?: -1f
        val bar = progress
        if (bar != null && controls?.visibility == View.VISIBLE) {
            if (played >= 0f) {
                bar.visibility = View.VISIBLE
                bar.progress = (played.coerceIn(0f, 1f) * 1000f).roundToInt()
            } else {
                bar.visibility = View.GONE
            }
        }
        val videoAspect = player?.videoAspect?.takeIf { it > 0f } ?: return
        if (abs(videoAspect - lastAspectCheck) > 0.01f) {
            // Not while tucked or held: taken once the window is free again, so it isn't lost.
            if (state != State.NORMAL || dragging || pinching) return
            lastAspectCheck = videoAspect
            aspect = videoAspect.coerceIn(0.56f, 2.39f)
            setCardSize(cardW)
            settle()
        }
    }
}
