package com.nuvio.app.features.autosync.bubble

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** SAMplay 1.8.2: a "working" AutoSync message can no longer stay on the video for good. */
class AutoSyncBubbleToastsTimeoutTest {

    /** A hand-driven clock standing in for the main-thread handler. */
    private class FakeScheduler : AutoSyncBubbleToasts.Scheduler {
        var now = 0L
        private val tasks = mutableListOf<Triple<Long, () -> Unit, BooleanArray>>()
        override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
            val cancelled = BooleanArray(1)
            tasks += Triple(now + delayMs, action, cancelled)
            return { cancelled[0] = true }
        }
        fun advance(ms: Long) {
            now += ms
            tasks.filter { it.first <= now }.forEach { (_, action, cancelled) -> if (!cancelled[0]) action() }
            tasks.removeAll { it.first <= now }
        }
        val pending get() = tasks.count { !it.third[0] }
    }

    private val clock = FakeScheduler()

    @Before fun setUp() {
        AutoSyncBubbleToasts.scheduler = clock
        AutoSyncBubbleToasts.setEnabled(true)
        AutoSyncBubbleToasts.attachHost()
        AutoSyncBubbleToasts.clear()
    }

    @After fun tearDown() {
        AutoSyncBubbleToasts.detachHost()
        AutoSyncBubbleToasts.resetSchedulerForTest()
    }

    @Test fun workingMessageLeavesOnItsOwn() {
        AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Working, "Auto Sync • Analyzing…")
        assertNotNull(AutoSyncBubbleToasts.current.value)
        clock.advance(AutoSyncBubbleToasts.WORKING_TIMEOUT_MS - 1)
        assertNotNull("still within its time", AutoSyncBubbleToasts.current.value)
        clock.advance(1)
        assertNull("the syncing message must not stay on screen", AutoSyncBubbleToasts.current.value)
    }

    @Test fun aResultCancelsTheWorkingTimeoutAndIsNotRemovedByIt() {
        AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Working, "Auto Sync • Analyzing…")
        AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Success, "Auto Sync • Subtitles synced")
        assertEquals(0, clock.pending)
        clock.advance(AutoSyncBubbleToasts.WORKING_TIMEOUT_MS * 2)
        // The bubble view times results out itself (2.8 s / 4.2 s); the state keeps the result.
        assertEquals(AutoSyncBubbleKind.Success, AutoSyncBubbleToasts.current.value?.kind)
    }

    @Test fun eachNewWorkingStepGetsItsOwnTime() {
        AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Working, "Auto Sync • Analyzing…")
        clock.advance(4_000)
        AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Working, "Auto Sync • No match found • syncing to the audio instead…")
        clock.advance(4_000)
        assertNotNull("the second step restarted the timer", AutoSyncBubbleToasts.current.value)
        clock.advance(AutoSyncBubbleToasts.WORKING_TIMEOUT_MS)
        assertNull(AutoSyncBubbleToasts.current.value)
    }

    @Test fun cancellingTheRunTakesTheWorkingMessageAwayAtOnce() {
        AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Working, "Auto Sync • Analyzing…")
        AutoSyncBubbleToasts.dismissWorking()
        assertNull(AutoSyncBubbleToasts.current.value)
        assertEquals(0, clock.pending)
    }

    @Test fun aStaleTimeoutDoesNotRemoveALaterMessage() {
        AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Working, "Auto Sync • Analyzing…")
        AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Failure, "Auto Sync • Sync failed • subtitle timing not changed")
        clock.advance(AutoSyncBubbleToasts.WORKING_TIMEOUT_MS)
        assertEquals(AutoSyncBubbleKind.Failure, AutoSyncBubbleToasts.current.value?.kind)
    }

    @Test fun closingThePlayerClearsEverything() {
        AutoSyncBubbleToasts.post(AutoSyncBubbleKind.Working, "Auto Sync • Analyzing…")
        AutoSyncBubbleToasts.clear()
        assertNull(AutoSyncBubbleToasts.current.value)
        assertEquals(0, clock.pending)
    }
}
