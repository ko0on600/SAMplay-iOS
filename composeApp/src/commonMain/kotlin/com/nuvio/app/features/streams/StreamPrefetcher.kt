package com.nuvio.app.features.streams

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.servers.ServerStreams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Addons' answers to a link search, kept briefly so asking again costs nothing. */
internal class StreamAnswers(
    private val scope: CoroutineScope,
    private val fetch: suspend (url: String, forceRefresh: Boolean) -> String,
    private val now: () -> Long,
) {
    private class Answer(val text: Deferred<String>, val askedAt: Long)

    private val answers = LinkedHashMap<String, Answer>()
    private val mutex = Mutex()

    /** Starts asking without waiting for the answer. */
    suspend fun ask(url: String) {
        answerFor(url, ask = true)
    }

    suspend fun get(url: String, forceRefresh: Boolean = false): String {
        if (forceRefresh) {
            mutex.withLock { answers.remove(url) }
            return fetch(url, true)
        }
        val answer = answerFor(url, ask = false) ?: return fetch(url, false)
        return try {
            answer.text.await()
        } catch (error: Throwable) {
            currentCoroutineContext().ensureActive()
            // The early question failed: the real one gets its own try and reports its own error.
            mutex.withLock { if (answers[url] === answer) answers.remove(url) }
            fetch(url, false)
        }
    }

    private suspend fun answerFor(url: String, ask: Boolean): Answer? = mutex.withLock {
        val existing = answers[url]
        if (existing != null && now() - existing.askedAt <= TTL_MS) return@withLock existing
        answers.remove(url)
        if (!ask) return@withLock null
        Answer(scope.async { fetch(url, false) }, now()).also { answer ->
            answers[url] = answer
            while (answers.size > MAX_ANSWERS) answers.remove(answers.keys.first())
        }
    }

    private companion object {
        const val TTL_MS = 5 * 60_000L
        const val MAX_ANSWERS = 32
    }
}

/**
 * Nuvio RS: asks the addons for a title's links while the user rests on it, so the links screen
 * opens with them already there. Moving on before the rest is over asks nothing of the addons.
 * Plugins and drives are left out: they run on the device and answer when asked.
 */
internal object StreamPrefetcher {
    private const val REST_MS = 900L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pending: Job? = null

    val answers = StreamAnswers(scope, ::fetchAddonResponseText, ::epochMs)

    fun request(type: String, videoId: String) {
        if (ServerStreams.isNativeRequest(videoId)) return
        pending?.cancel()
        pending = scope.launch {
            delay(REST_MS)
            if (MetaDetailsRepository.findEmbeddedStreams(videoId).isNotEmpty()) return@launch
            AddonRepository.uiState.value.addons.enabledAddons().forEach { addon ->
                val manifest = addon.manifest ?: return@forEach
                if (!manifest.supportsStream(type, videoId)) return@forEach
                answers.ask(
                    buildAddonResourceUrl(
                        manifestUrl = manifest.transportUrl,
                        resource = "stream",
                        type = type,
                        id = videoId,
                    ),
                )
            }
        }
    }
}
