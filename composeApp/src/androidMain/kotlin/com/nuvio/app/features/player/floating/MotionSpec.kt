package com.nuvio.app.features.player.floating

/** Nuvio RS: sizes, springs and timings of the floating window, in one place. */
internal object MotionSpec {
    // Card width, as a share of the screen's short side.
    const val DEFAULT_WIDTH_FRACTION = 0.57f
    const val MIN_WIDTH_FRACTION = 0.38f
    const val MAX_WIDTH_FRACTION = 0.92f

    const val CORNER_RADIUS_DP = 14f
    const val EDGE_MARGIN_DP = 12f

    // Springs: stiffness, damping ratio.
    const val ENTER_STIFFNESS = 420f
    const val ENTER_DAMPING = 0.78f
    const val LIFT_SCALE = 1.05f
    const val LIFT_STIFFNESS = 600f
    const val LIFT_DAMPING = 0.62f
    const val SNAP_STIFFNESS = 195f
    const val SNAP_DAMPING = 0.86f
    const val TUCK_STIFFNESS = 320f
    const val TUCK_DAMPING = 1f
    const val UNTUCK_STIFFNESS = 260f
    const val UNTUCK_DAMPING = 0.82f
    const val TAB_STIFFNESS = 700f
    const val TAB_DAMPING = 0.8f
    const val TAB_GLIDE_STIFFNESS = 260f

    /** A throw faster than this (dp/s) towards a side tucks the window. */
    const val TUCK_VELOCITY_DP = 900f

    // The tab left on screen while tucked.
    const val TAB_VISIBLE_DP = 22f
    const val TAB_HEIGHT_FRACTION = 0.78f

    const val CONTROLS_AUTO_HIDE_MS = 3_000L
    const val CONTROLS_FADE_MS = 180L
}
