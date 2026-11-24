package com.nuvio.app.features.player.volumeboost

import com.nuvio.app.features.player.PlayerAudioLevel
import com.nuvio.app.features.player.PlayerGestureController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.log2
import kotlin.math.pow

/**
 * Nuvio Reshaped volume boost: the swipe volume bar goes past 100% up to 300% (SAMplay 2.0.3;
 * it stopped at 200%).
 *
 * Levels up to 1.0 drive the system volume exactly as before. Past that the system volume
 * stays at max and the player adds gain, +10 dB for each doubling ([boostGainDbForLevel]): 200%
 * is +10 dB, about twice as loud to the ear, as before, and 300% is +15.8 dB, three times.
 * The boost only lasts while the player is open, so the next video never starts loud.
 * Platforms that apply the gain install persistence; elsewhere the bar stays at 100%.
 *
 * "Louder videos" (on by default) adds [LOUDER_GAIN_DB] to every video on top of that, so videos
 * start at 200% without touching the bar; the players' limiters keep peaks from clipping.
 */
object VolumeBoost {
    const val MAX_LEVEL = 3f
    /** The gain at [MAX_LEVEL]: 10 * log2(3). */
    const val MAX_GAIN_DB = 15.849625f
    const val LOUDER_GAIN_DB = 10f
    const val MAX_OUTPUT_GAIN_DB = MAX_GAIN_DB + LOUDER_GAIN_DB

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _gainDb = MutableStateFlow(0f)
    /** Extra gain from the volume bar past 100%, 0 to [MAX_GAIN_DB]. */
    val gainDb: StateFlow<Float> = _gainDb.asStateFlow()

    private val _louder = MutableStateFlow(true)
    val louder: StateFlow<Boolean> = _louder.asStateFlow()

    private val _outputGainDb = MutableStateFlow(0f)
    /** Total gain the active player applies: "louder videos" plus the bar's boost. */
    val outputGainDb: StateFlow<Float> = _outputGainDb.asStateFlow()

    private var save: ((Boolean) -> Unit)? = null
    private var saveLouder: ((Boolean) -> Unit)? = null

    val isAvailable: Boolean
        get() = save != null

    private val active: Boolean
        get() = _enabled.value && save != null

    /** Top of the volume bar: 3.0 (300%) with boost on, else 1.0. */
    val maxLevel: Float
        get() = if (active) MAX_LEVEL else 1f

    /**
     * SAMplay 2.0.3: where a swipe or a key step of [delta] from [level] lands. Past 100% it runs
     * [BOOST_SWIPE_SPEED] times as fast, so 100% to 300% takes the swipe 100% to 200% took.
     */
    fun stepLevel(level: Float, delta: Float): Float =
        if (maxLevel <= 1f) level + delta else swipeToLevel(levelToSwipe(level) + delta)

    fun installPersistence(
        load: () -> Boolean,
        save: (Boolean) -> Unit,
        loadLouder: () -> Boolean = { true },
        saveLouder: ((Boolean) -> Unit)? = null,
    ) {
        _enabled.value = load()
        this.save = save
        _louder.value = loadLouder()
        this.saveLouder = saveLouder
        publish()
    }

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        if (!enabled) setGain(0f)
        save?.invoke(enabled)
    }

    fun setLouder(louder: Boolean) {
        _louder.value = louder
        saveLouder?.invoke(louder)
        publish()
    }

    fun resetSession() {
        setGain(0f)
    }

    private fun setGain(gainDb: Float) {
        _gainDb.value = gainDb
        publish()
    }

    private fun publish() {
        val base = if (_louder.value && saveLouder != null) LOUDER_GAIN_DB else 0f
        _outputGainDb.value = (base + _gainDb.value).coerceIn(0f, MAX_OUTPUT_GAIN_DB)
    }

    fun currentVolume(system: PlayerGestureController): PlayerAudioLevel? {
        val level = system.currentVolume() ?: return null
        if (!active) return level
        // Volume keys lowered the system volume while boosted: the boost no longer applies.
        if (!level.isSystemMax()) {
            setGain(0f)
            return level
        }
        return level.copy(fraction = boostLevelForGainDb(_gainDb.value))
    }

    fun setVolume(system: PlayerGestureController, level: Float): PlayerAudioLevel? {
        if (!active || !level.isFinite() || level <= 1f) {
            setGain(0f)
            return system.setVolume(level)
        }
        val systemLevel = system.setVolume(1f) ?: return null
        if (!systemLevel.isSystemMax()) {
            setGain(0f)
            return systemLevel
        }
        val boosted = level.coerceAtMost(MAX_LEVEL)
        setGain(boostGainDbForLevel(boosted))
        return systemLevel.copy(fraction = boosted, isMuted = false)
    }

    private fun PlayerAudioLevel.isSystemMax(): Boolean = fraction >= 0.999f
}

/** Gain for a bar level past 100%: +10 dB per doubling, 0 at 100% and [VolumeBoost.MAX_GAIN_DB] at 300%. */
internal fun boostGainDbForLevel(level: Float): Float =
    (10f * log2(level.coerceIn(1f, VolumeBoost.MAX_LEVEL))).coerceIn(0f, VolumeBoost.MAX_GAIN_DB)

/** The bar level a boost gain stands for: [boostGainDbForLevel] backwards. */
internal fun boostLevelForGainDb(gainDb: Float): Float =
    2f.pow(gainDb.coerceIn(0f, VolumeBoost.MAX_GAIN_DB) / 10f).coerceIn(1f, VolumeBoost.MAX_LEVEL)

/** Past 100% the swipe and the bar run this many times as fast: the boost zone is as long as 0 to 100%. */
internal const val BOOST_SWIPE_SPEED = VolumeBoost.MAX_LEVEL - 1f

/** A level as a distance along the swipe (and the bar): 100% is 1, 300% is 2. */
internal fun levelToSwipe(level: Float): Float =
    if (level <= 1f) level else 1f + (level - 1f) / BOOST_SWIPE_SPEED

internal fun swipeToLevel(position: Float): Float =
    if (position <= 1f) position else 1f + (position - 1f) * BOOST_SWIPE_SPEED
