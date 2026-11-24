package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import kotlin.math.abs
import kotlin.math.roundToLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SAMplay TV 1.0.2: a subtitle made for another cut keeps one offset up to some scene and another
 * after it. Every line must land on the embedded line it belongs to, the step included.
 */
class AutoSyncOffsetStepTest {
    @Test
    fun anOffsetThatStepsMidEpisodeIsFollowedLineByLine() {
        val reference = timeline(seed = 7)
        val stepAt = reference[reference.size / 2].startTimeMs
        // +4 s up to the step, +6.5 s after it (the subtitle's cut has 2.5 s more there).
        val target = reference.map { cue ->
            val offset = if (cue.startTimeMs < stepAt) 4_000L else 6_500L
            cue.copy(startTimeMs = cue.startTimeMs + offset, endTimeMs = cue.endTimeMs + offset)
        }

        val result = checkNotNull(AutoSyncTimelineRetimer.retime(reference, target, 1.0, 0.0, true))

        assertTrue(result.rejectReason ?: "", result.confident)
        assertEquals(2, result.steps.size)
        assertEquals(-4_000.0, result.steps[0].offsetMs, 1.0)
        assertEquals(-6_500.0, result.steps[1].offsetMs, 1.0)
        assertEquals(6_500.0, result.maxAlignmentShiftMs(), 1.0)
        val worst = result.cues.indices.maxBy { abs(result.cues[it].startTimeMs - reference[it].startTimeMs) }
        val error = result.cues[worst].startTimeMs - reference[worst].startTimeMs
        assertTrue("line $worst at ${reference[worst].startTimeMs}ms off by ${error}ms, steps ${result.steps}", abs(error) <= 50L)
    }

    @Test
    fun twoStepsAreFollowedToo() {
        val reference = timeline(seed = 11)
        val first = reference[reference.size / 3].startTimeMs
        val second = reference[2 * reference.size / 3].startTimeMs
        val target = reference.map { cue ->
            val offset = when {
                cue.startTimeMs < first -> -3_000L
                cue.startTimeMs < second -> -1_500L
                else -> 1_000L
            }
            cue.copy(startTimeMs = cue.startTimeMs + offset, endTimeMs = cue.endTimeMs + offset)
        }

        val result = checkNotNull(AutoSyncTimelineRetimer.retime(reference, target, 1.0, 0.0, true))

        assertTrue(result.rejectReason ?: "", result.confident)
        assertEquals(3, result.steps.size)
        val worst = result.cues.indices.maxBy { abs(result.cues[it].startTimeMs - reference[it].startTimeMs) }
        val error = result.cues[worst].startTimeMs - reference[worst].startTimeMs
        assertTrue("line $worst at ${reference[worst].startTimeMs}ms off by ${error}ms, steps ${result.steps}", abs(error) <= 50L)
    }

    @Test
    fun aFrameRateMismatchIsNotMistakenForSteps() {
        val reference = timeline(seed = 3)
        // 25 fps subtitle on a 23.976 fps video: drifts smoothly, no constant levels.
        val scale = 25.0 / 23.976
        val target = reference.map { cue ->
            cue.copy(
                startTimeMs = (cue.startTimeMs * scale).roundToLong() + 2_000L,
                endTimeMs = (cue.endTimeMs * scale).roundToLong() + 2_000L,
            )
        }

        val result = checkNotNull(AutoSyncTimelineRetimer.retime(reference, target, 1.0, 0.0, true))

        assertTrue(result.rejectReason ?: "", result.confident)
        assertTrue(result.steps.isEmpty())
    }

    @Test
    fun aConstantOffsetHasNoSteps() {
        val reference = timeline(seed = 5)
        val target = reference.map { it.copy(startTimeMs = it.startTimeMs + 3_000L, endTimeMs = it.endTimeMs + 3_000L) }

        val result = checkNotNull(AutoSyncTimelineRetimer.retime(reference, target, 1.0, 0.0, true))

        assertTrue(result.rejectReason ?: "", result.confident)
        assertTrue(result.steps.isEmpty())
        assertEquals(3_000.0, result.maxAlignmentShiftMs(), 1.0)
    }

    @Test
    fun aStepIsReadOnTheShiftedCopysOwnClock() {
        val stepped = AutoSyncTimelineRetimeResult(
            cues = emptyList(), groups = emptyList(), targetCoverage = 1.0, referenceCoverage = 1.0,
            skippedTargetCues = 0, skippedReferenceCues = 0, longestTargetSkipRun = 0, averageGroupCost = 0.0,
            oneToOneGroups = 0, oneToTwoGroups = 0, twoToOneGroups = 0, oneToThreeGroups = 0,
            threeToOneGroups = 0, twoToTwoGroups = 0, confident = true,
            steps = listOf(AutoSyncTimingStep(Long.MIN_VALUE, -4_000.0), AutoSyncTimingStep(600_000L, -6_500.0)),
        )
        assertEquals(100_000.0 - 4_000.0, stepped.alignedTimeMs(100_000L), 0.0)
        assertEquals(700_000.0 - 6_500.0, stepped.alignedTimeMs(700_000L), 0.0)
    }

    /** Twenty minutes of dialogue: 300 lines, 1.2 to 7.2 s apart. */
    private fun timeline(seed: Int): List<SubtitleSyncCue> {
        val random = kotlin.random.Random(seed)
        var start = 30_000L
        return (0 until 300).map { index ->
            if (index > 0) start += 1_200L + random.nextInt(6_000)
            SubtitleSyncCue(start, start + 900L + random.nextInt(2_000), "cue $index")
        }
    }
}
