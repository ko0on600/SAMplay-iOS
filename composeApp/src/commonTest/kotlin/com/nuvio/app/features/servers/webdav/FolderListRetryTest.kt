package com.nuvio.app.features.servers.webdav

import com.nuvio.app.features.servers.ServerException
import com.nuvio.app.features.servers.ServerFailure
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FolderListRetryTest {
    private val notFound = ServerException(ServerFailure.NOT_FOUND, "HTTP 404")

    @Test
    fun aFolderPikPakAnswers404ForAWhileStillOpens() = runTest {
        var calls = 0
        val failures = mutableListOf<Int>()
        val listed = FolderListRetry.run(onFailure = { attempt, _ -> failures += attempt }) {
            calls++
            if (calls < 3) throw notFound
            listOf("4PM (2024).mp4")
        }
        assertEquals(listOf("4PM (2024).mp4"), listed)
        assertEquals(3, calls)
        assertEquals(listOf(1, 2), failures)
        // 1 s, then 2 s.
        assertEquals(3_000L, testScheduler.currentTime)
    }

    @Test
    fun aRefusedLoginIsNotAskedAgain() = runTest {
        var calls = 0
        val refused = ServerException(ServerFailure.AUTH_REQUIRED, "HTTP 401")
        val thrown = assertFailsWith<ServerException> { FolderListRetry.run { calls++; throw refused } }
        assertSame(refused, thrown)
        assertEquals(1, calls)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun aFolderThatNeverAnswersFailsAfterAllTheWaits() = runTest {
        var calls = 0
        val failures = mutableListOf<Int>()
        val thrown = assertFailsWith<ServerException> {
            FolderListRetry.run(onFailure = { attempt, _ -> failures += attempt }) { calls++; throw notFound }
        }
        assertSame(notFound, thrown)
        assertEquals(FolderListRetry.WAITS_MS.size + 1, calls)
        assertEquals((1..calls).toList(), failures)
        assertEquals(FolderListRetry.WAITS_MS.sum(), testScheduler.currentTime)
        assertEquals(25_000L, FolderListRetry.WAITS_MS.sum())
    }

    @Test
    fun theFoldersAboveAreListedAgainRightBeforeEachRetry() = runTest {
        // PikPak finds an Arabic-named folder again only after the folder above it is listed.
        val events = mutableListOf<String>()
        val listed = FolderListRetry.run(beforeRetry = { error ->
            events += "revisit ${(error as ServerException).failure} at ${testScheduler.currentTime}"
        }) {
            events += "try at ${testScheduler.currentTime}"
            if (events.size < 4) throw notFound
            listOf("Cougar Town - S01E02.mp4")
        }
        assertEquals(listOf("Cougar Town - S01E02.mp4"), listed)
        // Never before the first try, then after each wait and before the try it is for.
        assertEquals(
            listOf("try at 0", "revisit NOT_FOUND at 1000", "try at 1000", "revisit NOT_FOUND at 3000", "try at 3000"),
            events,
        )
    }

    @Test
    fun aRefusedLoginIsNeitherRetriedNorRevisited() = runTest {
        var revisits = 0
        assertFailsWith<ServerException> {
            FolderListRetry.run(beforeRetry = { revisits++ }) { throw ServerException(ServerFailure.AUTH_REQUIRED, "HTTP 401") }
        }
        assertEquals(0, revisits)
    }

    @Test
    fun theFoldersAboveAPathAreListedFromTheTopDown() {
        assertEquals(
            listOf("/", "/sam/", "/sam/english/", "/sam/english/مسلسلات/", "/sam/english/مسلسلات/Cougar Town/"),
            WebDavClient.parentFolders("/sam/english/مسلسلات/Cougar Town/S01/"),
        )
        // A file's own folder is the last one.
        assertEquals(listOf("/", "/sam/", "/sam/S01/"), WebDavClient.parentFolders("/sam/S01/ep 1.mkv"))
        // Nothing above the address's own folder, and nothing above the top.
        assertEquals(listOf("/dav/", "/dav/a/"), WebDavClient.parentFolders("/dav/a/b/", rootPath = "/dav/"))
        assertEquals(emptyList(), WebDavClient.parentFolders("/"))
        assertEquals(listOf("/"), WebDavClient.parentFolders("/movie/"))
    }

    @Test
    fun onlyDriveAnswersAreAskedAgain() {
        assertTrue(FolderListRetry.retries(notFound))
        assertTrue(FolderListRetry.retries(ServerException(ServerFailure.UNREACHABLE, "HTTP 503")))
        assertTrue(FolderListRetry.retries(ServerException(ServerFailure.FAILED, "HTTP 429")))
        assertFalse(FolderListRetry.retries(ServerException(ServerFailure.AUTH_REQUIRED)))
        assertFalse(FolderListRetry.retries(ServerException(ServerFailure.FORBIDDEN)))
        assertFalse(FolderListRetry.retries(ServerException(ServerFailure.UNSUPPORTED)))
        assertFalse(FolderListRetry.retries(IllegalStateException("bug")))
        assertEquals("NOT_FOUND HTTP 404", FolderListRetry.describe(notFound))
        assertEquals("UNREACHABLE", FolderListRetry.describe(ServerException(ServerFailure.UNREACHABLE)))
    }
}
