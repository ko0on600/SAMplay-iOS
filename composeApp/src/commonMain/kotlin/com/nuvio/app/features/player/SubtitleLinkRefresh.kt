package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/**
 * SAMplay 2.0.4: a subtitle file that only stands in for the real one: a line or two at 0:00
 * such as SubDL's "This subtitle action expired. Reopen the subtitle menu to refresh it." or an
 * add-on's "translating, try again" note. Shown as a subtitle, nobody ever sees it (it is over
 * before the first scene), so the subtitle just looks gone.
 */
internal object SubtitleStandIn {
    private val TIMING = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})\s*-->""")
    private const val MAX_CHARS = 2_000
    private const val MAX_CUES = 2
    private const val FIRST_START_MAX_MS = 1_000L

    fun isStandIn(body: String): Boolean {
        if (body.length > MAX_CHARS) return false
        val starts = TIMING.findAll(body).map { startMs(it) }.toList()
        return starts.size in 1..MAX_CUES && starts.first() < FIRST_START_MAX_MS
    }

    /** What the stand-in says, on one line. */
    fun text(body: String): String =
        body.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() && !line.startsWith("WEBVTT") && line.toIntOrNull() == null &&
                    !TIMING.containsMatchIn(line)
            }
            .joinToString(" ")
            .replace(Regex("<[^>]*>"), "")
            .take(160)

    private fun startMs(match: MatchResult): Long {
        val (hours, minutes, seconds, fraction) = match.destructured
        val ms = fraction.padEnd(3, '0').toLong()
        return ((hours.toLongOrNull() ?: 0L) * 3_600 + minutes.toLong() * 60 + seconds.toLong()) * 1_000 + ms
    }
}

/**
 * SAMplay 2.0.4: some subtitle add-ons hand out links that work for a short while only. The list
 * is asked for once when the episode starts, so a subtitle picked half an hour later came back as
 * SubDL's expired stand-in and no line showed for the rest of the episode (Z Nation S2E14 on the
 * TV). A stand-in now makes that add-on be asked again, and the subtitle is downloaded from its
 * fresh link. The link listed stays the subtitle's name everywhere else (selection, AutoSync, the
 * remembered choice); only the download goes to the fresh one.
 */
internal object SubtitleLinks {
    /** Listed link → the newest working link for the same subtitle. */
    private val fresh = HashMap<String, String>()
    private val askedAtMs = HashMap<String, Long>()
    private val mapLock = Mutex()
    private val asking = Mutex()
    private val origin = TimeSource.Monotonic.markNow()

    /** Asks the add-ons again for the subtitle listed at the given link; tests replace it. */
    internal var refresher: suspend (listedUrl: String) -> String? = { SubtitleRepository.freshLinkFor(it) }

    /** Clock for the wait between two askings of the same subtitle (tests replace it). */
    internal var nowMs: () -> Long = { origin.elapsedNow().inWholeMilliseconds }

    /** One add-on asking per subtitle within this long: a real stand-in does not change by asking again. */
    internal const val REASK_GAP_MS = 60_000L

    suspend fun clear() = mapLock.withLock {
        fresh.clear()
        askedAtMs.clear()
    }

    suspend fun current(listedUrl: String): String = mapLock.withLock { fresh[listedUrl] } ?: listedUrl

    suspend fun remember(listedUrl: String, freshUrl: String) {
        if (freshUrl != listedUrl) mapLock.withLock { fresh[listedUrl] = freshUrl }
    }

    /** Downloads [listedUrl] with [fetch], from a fresh link when the one in hand gives a stand-in. */
    suspend fun download(listedUrl: String, fetch: suspend (url: String) -> String): String {
        val tried = current(listedUrl)
        val body = fetch(tried)
        if (!SubtitleStandIn.isStandIn(body)) return body
        val newUrl = freshLink(listedUrl, tried)
        if (newUrl == null) {
            log.w { "subtitle came empty (\"${SubtitleStandIn.text(body)}\") and no fresh link was found" }
            return body
        }
        log.i { "subtitle link expired (\"${SubtitleStandIn.text(body)}\"); downloading it from a fresh link" }
        return fetch(newUrl)
    }

    private suspend fun freshLink(listedUrl: String, triedUrl: String): String? = asking.withLock {
        // Another download of the same subtitle got a fresh link meanwhile.
        current(listedUrl).takeIf { it != triedUrl }?.let { return@withLock it }
        val now = nowMs()
        val askedBefore = mapLock.withLock {
            val before = askedAtMs[listedUrl]
            if (before == null || now - before >= REASK_GAP_MS) askedAtMs[listedUrl] = now
            before
        }
        if (askedBefore != null && now - askedBefore < REASK_GAP_MS) return@withLock null
        val got = refresher(listedUrl)?.takeIf { it != triedUrl } ?: return@withLock null
        remember(listedUrl, got)
        got
    }

    private val log = Logger.withTag("SubtitleLinks")
}

/**
 * The same subtitle in a fresh list from the add-ons: by add-on and id (an id may be only its place
 * in the list, so the language and file name must agree too), else by a file name only it has.
 */
internal fun freshMatch(old: AddonSubtitle, freshList: List<AddonSubtitle>): AddonSubtitle? {
    val name = subtitleFileName(old.url)
    freshList.firstOrNull {
        it.addonName == old.addonName && it.id == old.id && it.language == old.language &&
            (name == null || subtitleFileName(it.url) == null || subtitleFileName(it.url) == name)
    }?.let { return it }
    if (name == null) return null
    return freshList.filter { it.addonName == old.addonName && it.language == old.language && subtitleFileName(it.url) == name }
        .singleOrNull()
}

private fun subtitleFileName(url: String): String? =
    url.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/').takeIf { '.' in it }
