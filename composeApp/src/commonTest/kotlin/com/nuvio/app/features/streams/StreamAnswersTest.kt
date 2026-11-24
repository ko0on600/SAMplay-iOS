package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class StreamAnswersTest {
    private var clock = 0L
    private val asked = mutableListOf<Pair<String, Boolean>>()
    private var failNext = false

    private fun TestScope.answers() = StreamAnswers(
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        fetch = { url, force ->
            asked += url to force
            if (failNext) {
                failNext = false
                error("no answer")
            }
            "answer ${asked.size}"
        },
        now = { clock },
    )

    @Test
    fun `an early question answers the real one without asking again`() = runTest {
        val answers = answers()
        answers.ask("u")
        advanceUntilIdle()
        assertEquals("answer 1", answers.get("u"))
        assertEquals(1, asked.size)
    }

    @Test
    fun `a real question joins an early one still waiting`() = runTest {
        val answers = answers()
        answers.ask("u")
        assertEquals("answer 1", answers.get("u"))
        assertEquals(1, asked.size)
    }

    @Test
    fun `without an early question the real one asks for itself`() = runTest {
        val answers = answers()
        assertEquals("answer 1", answers.get("u"))
        assertEquals("answer 2", answers.get("u"))
    }

    @Test
    fun `a failed early question is asked again`() = runTest {
        val answers = answers()
        failNext = true
        answers.ask("u")
        advanceUntilIdle()
        assertEquals("answer 2", answers.get("u"))
    }

    @Test
    fun `a real question that fails reports it`() = runTest {
        val answers = answers()
        failNext = true
        assertFailsWith<IllegalStateException> { answers.get("u") }
    }

    @Test
    fun `refreshing skips what was kept`() = runTest {
        val answers = answers()
        answers.ask("u")
        advanceUntilIdle()
        assertEquals("answer 2", answers.get("u", forceRefresh = true))
        assertEquals(true, asked.last().second)
    }

    @Test
    fun `an old answer is not used`() = runTest {
        val answers = answers()
        answers.ask("u")
        advanceUntilIdle()
        clock += 5 * 60_000L + 1
        assertEquals("answer 2", answers.get("u"))
    }
}
