package com.nuvio.app.features.servers.webdav

import com.nuvio.app.features.servers.ServerException
import com.nuvio.app.features.servers.ServerFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * SAMplay 2.0.3: PikPak answers some folders with 404 (or a server error) for a while and then
 * lists them, as it does with files. On the user's phone a folder showed "This item is no longer
 * available on the server" twice and opened on the third Retry, and on the TV some folders did
 * not open at all. A folder page now asks again with growing waits before it shows the error. A
 * refused login or a server that does not speak WebDAV is never asked again.
 *
 * The real cause (found on the user's TV, 19 Nov 2026): PikPak finds a folder with an Arabic name
 * ("مسلسلات") only for about half a minute after the folder above it was listed; after that the
 * folder and everything under it answer 404 until that folder is listed again. Waiting alone did
 * not help, so [run]'s [beforeRetry] lists the folders above again before the next try
 * ([WebDavClient.revisitParents]).
 */
internal object FolderListRetry {
    /** The waits between tries: 8 tries, 25 s of waiting at most. */
    val WAITS_MS = longArrayOf(1_000, 2_000, 3_000, 4_000, 5_000, 5_000, 5_000)

    private val RETRIED = setOf(
        ServerFailure.NOT_FOUND,
        ServerFailure.UNREACHABLE,
        ServerFailure.FAILED,
        ServerFailure.INCOMPLETE,
    )

    /** Whether a try that failed with [error] is worth another. */
    fun retries(error: Throwable): Boolean = error is ServerException && error.failure in RETRIED

    /**
     * [block]'s answer, asked again after each of [waitsMs] while it fails with an error that
     * [retries] accepts. [onFailure] hears every failed try (counted from 1), the last one too.
     * [beforeRetry] runs after each wait, right before the next try, with the error that try is for.
     */
    suspend fun <T> run(
        waitsMs: LongArray = WAITS_MS,
        onFailure: (attempt: Int, error: Exception) -> Unit = { _, _ -> },
        beforeRetry: suspend (error: Exception) -> Unit = {},
        block: suspend () -> T,
    ): T {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onFailure(attempt, error)
                if (!retries(error) || attempt > waitsMs.size) throw error
                delay(waitsMs[attempt - 1])
                beforeRetry(error)
            }
        }
    }

    /** One line for the log: the drive's answer ("NOT_FOUND HTTP 404") or the error's kind. */
    fun describe(error: Throwable): String = when (error) {
        is ServerException ->
            listOfNotNull(error.failure.name, error.message?.takeIf { it != error.failure.name }).joinToString(" ")
        else -> listOfNotNull(error::class.simpleName, error.message).joinToString(": ")
    }
}
