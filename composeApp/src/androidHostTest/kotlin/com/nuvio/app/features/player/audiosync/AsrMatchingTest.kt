package com.nuvio.app.features.player.audiosync

import com.nuvio.app.features.player.audiosync.asr.AsrSyncEngine
import com.nuvio.app.features.player.audiosync.asr.HeardWord
import com.nuvio.app.features.player.audiosync.asr.SubtitleBridge
import com.nuvio.app.features.player.audiosync.asr.WordAnchorMatcher
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsrMatchingTest {
    /** Like real dialogue: a few common words and many that occur only now and then. */
    private val vocabulary = List(600) { index ->
        val syllables = listOf("ka", "lo", "mir", "den", "sa", "tor", "vel", "ni", "ra", "bex")
        syllables[index % 10] + syllables[(index / 10) % 10] + syllables[(index / 100) % 10]
    }

    private fun script(random: Random, lines: Int): List<Triple<Long, Long, String>> {
        var t = 20_000L
        return List(lines) {
            val words = List(3 + random.nextInt(5)) { vocabulary[random.nextInt(vocabulary.size)] }
            val length = 1_200L + random.nextLong(2_500L)
            Triple(t, t + length, words.joinToString(" ")).also { t += length + 500L + random.nextLong(3_000L) }
        }
    }

    /** Words heard at media = subtitle + shift, with some misrecognised. */
    private fun hear(cues: List<Triple<Long, Long, String>>, shiftSec: Double, random: Random, upToLine: Int): List<HeardWord> =
        cues.take(upToLine).flatMapIndexed { line, (start, end, text) ->
            val words = text.split(' ')
            words.mapIndexedNotNull { k, word ->
                if (random.nextFloat() < 0.3f) return@mapIndexedNotNull null
                val t = (start + (end - start) * k / words.size) / 1_000.0 + shiftSec + random.nextDouble(-0.15, 0.15)
                HeardWord(t, word, line)
            }
        }

    @Test
    fun locksWithinAFewLines() {
        val random = Random(1)
        val cues = script(random, 60)
        val matcher = WordAnchorMatcher(cues)
        val fit = assertNotNull(matcher.fit(hear(cues, 7.3, random, upToLine = 6)))
        assertTrue(fit.isConfident, "fit $fit")
        assertTrue(abs(fit.shiftSec - 7.3) < 0.3, "shift ${fit.shiftSec}")
    }

    @Test
    fun unrelatedWordsDoNotLock() {
        val random = Random(2)
        val matcher = WordAnchorMatcher(script(random, 60))
        val heard = List(80) { HeardWord(30.0 + it * 2.5, "unrelated${it % 7}", it / 3) }
        val fit = matcher.fit(heard)
        assertTrue(fit == null || !fit.isConfident, "fit $fit")
    }

    @Test
    fun bridgesTranslationToReference() {
        val random = Random(3)
        val english = script(random, 80)
        // Same line timings, different words, shifted by 4.2 s relative to the English file.
        val translated = english.map { (a, b, _) -> Triple(a - 4_200L, b - 4_200L, "linie tradusa aici") }
        val bridge = assertNotNull(
            SubtitleBridge.align(SubtitleSpeechTrack.fromCues(translated), SubtitleSpeechTrack.fromCues(english)),
        )
        assertTrue(abs(bridge.shiftSec - 4.2) < 0.05, "bridge $bridge")
    }

    @Test
    fun speechIsRecognisedForTheOtherChecksWithoutABridgedReference() {
        // Nuvio RS: no English file lined up with the chosen subtitle as a whole, but the check of
        // the other files places English files by the words heard: they must still be recognised.
        val recognised = java.util.concurrent.CountDownLatch(2)
        val engine = AsrSyncEngine(SpeechTimeline(), onLock = {})
        try {
            engine.startSession(SubtitleSpeechTrack.fromCues(script(Random(9), 30)), emptyList())
            engine.setWordsUsedElsewhere(true)
            engine.setRecognizer { recognised.countDown(); listOf(0.2 to "kaloka") }
            listOf(100, 400).forEach { engine.offerSegment(it, FloatArray(SileroVad.CHUNK_SAMPLES * 10)) }
            assertTrue(recognised.await(10, java.util.concurrent.TimeUnit.SECONDS), "speech was never recognised")
            val deadline = System.currentTimeMillis() + 10_000
            while (engine.heardWordCount < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(2, engine.heardWordCount)
        } finally {
            engine.release()
        }
    }

    @Test
    fun speechWaitsWhileNoWordsCanBeUsed() {
        val recognised = java.util.concurrent.atomic.AtomicInteger()
        val engine = AsrSyncEngine(SpeechTimeline(), onLock = {})
        try {
            engine.startSession(SubtitleSpeechTrack.fromCues(script(Random(10), 30)), emptyList())
            engine.setRecognizer { recognised.incrementAndGet(); emptyList() }
            engine.offerSegment(100, FloatArray(SileroVad.CHUNK_SAMPLES * 10))
            Thread.sleep(500)
            assertEquals(0, recognised.get(), "nothing to match words against")
        } finally {
            engine.release()
        }
    }

    @Test
    fun sampledSpeechIsRecognisedFirstAcrossPlaces() {
        val order = java.util.Collections.synchronizedList(ArrayList<Int>())
        val done = java.util.concurrent.CountDownLatch(6)
        val engine = AsrSyncEngine(SpeechTimeline(), onLock = {})
        val cues = script(Random(6), 30)
        val track = SubtitleSpeechTrack.fromCues(cues)
        engine.startSession(track, listOf(com.nuvio.app.features.player.audiosync.asr.ReferenceSubtitle("en", cues, null)))
        fun segment(frame: Int) = FloatArray(SileroVad.CHUNK_SAMPLES * 10) { frame.toFloat() }
        engine.onPlayhead(0L)
        listOf(100, 200, 300).forEach { engine.offerSegment(it, segment(it)) }
        // Two segments sampled at one place (~30 min), one at another (~60 min).
        listOf(56_000, 56_100, 112_000).forEach { engine.offerSegment(it, segment(it), spread = true) }
        engine.setRecognizer { samples ->
            order += samples[0].toInt()
            done.countDown()
            emptyList()
        }
        try {
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            engine.release()
        }
        assertEquals(listOf(56_000, 112_000, 56_100, 100, 200, 300), order.toList())
    }

    @Test
    fun englishReferenceAtAnotherFrameRateDoesNotSkewTheLock() {
        // Video and translated subtitle agree; the English reference was made for 25 fps.
        val lock = lockAcrossFrameRates(videoMatchesEnglish = false)
        assertEquals(1.0, lock.scale, 0.002, "lock $lock")
        assertTrue(abs(lock.shiftMs) < 150.0, "lock $lock")
        assertTrue(!lock.final, "a rate chosen from a few lines stays open: $lock")
    }

    @Test
    fun translationAtAnotherFrameRateIsCorrected() {
        // Video and English reference agree; the translated subtitle was made for 25 fps.
        val lock = lockAcrossFrameRates(videoMatchesEnglish = true)
        assertEquals(25.0 / 23.976, lock.scale, 0.002, "lock $lock")
    }

    @Test
    fun sceneAddedByTheReleaseBecomesAStep() {
        // Subtitle made for a cut without 2.4 s at 5:00: it is 1.5 s early before and 3.9 s after.
        val subtitle = script(Random(9), 250)
        val media = subtitle.map { (a, b, t) ->
            val shift = if (a < 300_000L) 1_500L else 3_900L
            Triple(a + shift, b + shift, t)
        }
        val target = SubtitleSpeechTrack.fromCues(subtitle.map { (a, b, _) -> Triple(a, b, "linie") })
        val bridge = assertNotNull(SubtitleBridge.align(target, SubtitleSpeechTrack.fromCues(subtitle)))
        val locks = java.util.Collections.synchronizedList(ArrayList<com.nuvio.app.features.player.audiosync.asr.AsrLock>())
        val engine = AsrSyncEngine(speech(media), onLock = { locks += it })
        engine.startSession(target, listOf(com.nuvio.app.features.player.audiosync.asr.ReferenceSubtitle("en", subtitle, bridge)))
        // Words heard in three places, as from sampled spots.
        val heardLines = media.indices.filter { media[it].first / 1_000 in 30..150 || media[it].first / 1_000 in 500..700 || media[it].first / 1_000 in 900..1_000 }
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        heardLines.forEach { line ->
            engine.offerSegment((media[line].first / frameMs).toInt(), FloatArray(SileroVad.CHUNK_SAMPLES * 10) { line.toFloat() }, spread = true)
        }
        val random = Random(10)
        val recognised = java.util.concurrent.atomic.AtomicInteger()
        engine.setRecognizer { samples ->
            recognised.incrementAndGet()
            val (start, end, text) = media[samples[0].toInt()]
            val segmentStartSec = (start / frameMs).toInt() * frameMs / 1_000.0
            val words = text.split(' ')
            words.mapIndexed { k, word ->
                ((start + (end - start) * k / words.size) / 1_000.0 + random.nextDouble(-0.1, 0.1) - segmentStartSec) to word
            }
        }
        val deadline = System.currentTimeMillis() + 60_000
        while (recognised.get() < heardLines.size && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(1_500)
        engine.release()
        val lock = locks.last()
        assertTrue(lock.final, "lock $lock")
        assertEquals(2, lock.segments.size, "segments ${lock.segments}")
        val (before, after) = lock.segments
        assertTrue(abs(before.shiftMs - 1_500.0) < 150.0, "before $before")
        assertTrue(abs(after.shiftMs - 3_900.0) < 150.0, "after $after")
        assertTrue(abs(after.fromMediaMs - 301_500L) < 20_000L, "step at ${after.fromMediaMs}")
        assertEquals(1.0, before.scale, 1e-9)
    }

    @Test
    fun translationWithASceneTheReferenceLacksBecomesAStep() {
        // The English file matches the video; the translation was made for a cut with 30 s more
        // at 5:00, so one whole-file offset between the two is wrong on one side of it.
        val english = script(Random(11), 250)
        val translation = english.map { (a, b, _) ->
            val extra = if (a < 300_000L) 0L else 30_000L
            Triple(a + extra, b + extra, "linie")
        }
        val target = SubtitleSpeechTrack.fromCues(translation)
        val bridge = assertNotNull(SubtitleBridge.align(target, SubtitleSpeechTrack.fromCues(english)))
        val lock = lockFromHeardWords(english, target, english, bridge, listOf(30..150, 500..700, 900..1_000))
        assertTrue(lock.final, "lock $lock")
        assertEquals(2, lock.segments.size, "segments ${lock.segments}")
        val (before, after) = lock.segments
        assertTrue(abs(before.shiftMs) < 150.0, "before $before")
        assertTrue(abs(after.shiftMs + 30_000.0) < 150.0, "after $after")
        assertTrue(abs(after.fromMediaMs - 300_000L) < 20_000L, "step at ${after.fromMediaMs}")
    }

    @Test
    fun translationOffsetWhereTheWordsAreHeardWins() {
        // Words heard only after the scene the translation adds: the offset there, not the
        // whole-file one, which the earlier (longer) part of the file decides.
        val english = script(Random(12), 250)
        val translation = english.map { (a, b, _) ->
            val extra = if (a < 780_000L) 0L else 30_000L
            Triple(a + extra, b + extra, "linie")
        }
        val target = SubtitleSpeechTrack.fromCues(translation)
        val bridge = assertNotNull(SubtitleBridge.align(target, SubtitleSpeechTrack.fromCues(english)))
        assertTrue(abs(bridge.shiftSec) < 1.0, "bridge $bridge")
        val lock = lockFromHeardWords(english, target, english, bridge, listOf(830..1_050))
        val delayMs = lock.segments.last().shiftMs
        assertTrue(abs(delayMs + 30_000.0) < 150.0, "lock $lock")
    }

    /** Runs recognition on the lines of [media] starting within [heardSec], returning the last lock. */
    private fun lockFromHeardWords(
        media: List<Triple<Long, Long, String>>,
        target: SubtitleSpeechTrack,
        english: List<Triple<Long, Long, String>>,
        bridge: com.nuvio.app.features.player.audiosync.asr.BridgeFit,
        heardSec: List<IntRange>,
        timeline: SpeechTimeline = speech(media),
    ): com.nuvio.app.features.player.audiosync.asr.AsrLock {
        val locks = java.util.Collections.synchronizedList(ArrayList<com.nuvio.app.features.player.audiosync.asr.AsrLock>())
        val engine = AsrSyncEngine(timeline, onLock = { locks += it })
        engine.startSession(target, listOf(com.nuvio.app.features.player.audiosync.asr.ReferenceSubtitle("en", english, bridge)))
        val heardLines = media.indices.filter { line -> heardSec.any { media[line].first / 1_000 in it } }
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        heardLines.forEach { line ->
            engine.offerSegment((media[line].first / frameMs).toInt(), FloatArray(SileroVad.CHUNK_SAMPLES * 10) { line.toFloat() }, spread = true)
        }
        val random = Random(13)
        val recognised = java.util.concurrent.atomic.AtomicInteger()
        engine.setRecognizer { samples ->
            recognised.incrementAndGet()
            val (start, end, text) = media[samples[0].toInt()]
            val segmentStartSec = (start / frameMs).toInt() * frameMs / 1_000.0
            val words = text.split(' ')
            words.mapIndexed { k, word ->
                ((start + (end - start) * k / words.size) / 1_000.0 + random.nextDouble(-0.1, 0.1) - segmentStartSec) to word
            }
        }
        val deadline = System.currentTimeMillis() + 60_000
        while (recognised.get() < heardLines.size && System.currentTimeMillis() < deadline) Thread.sleep(20)
        Thread.sleep(1_500)
        engine.release()
        return locks.last()
    }

    @Test
    fun littleHeardAudioNeverStretches() {
        // As on a phone without sampling: about 70 s heard. Even if a stretch is right, it is not
        // applied on so little evidence; the subtitle keeps its own rate until more is heard.
        val lock = lockAcrossFrameRates(videoMatchesEnglish = true, heardMs = 70_000L)
        assertEquals(1.0, lock.scale, 1e-9, "lock $lock")
        assertTrue(!lock.final, "lock $lock")
    }

    private fun lockAcrossFrameRates(
        videoMatchesEnglish: Boolean,
        heardMs: Long = Long.MAX_VALUE,
    ): com.nuvio.app.features.player.audiosync.asr.AsrLock {
        val rate = 25.0 / 23.976
        val media = script(Random(7), 160)
        fun at25(cues: List<Triple<Long, Long, String>>) =
            cues.map { (a, b, t) -> Triple((a / rate).toLong(), (b / rate).toLong(), t) }
        val english = if (videoMatchesEnglish) media else at25(media)
        val translated = (if (videoMatchesEnglish) at25(media) else media).map { (a, b, _) -> Triple(a, b, "linie tradusa") }
        val target = SubtitleSpeechTrack.fromCues(translated)
        val bridge = assertNotNull(SubtitleBridge.align(target, SubtitleSpeechTrack.fromCues(english)))
        val locks = java.util.Collections.synchronizedList(ArrayList<com.nuvio.app.features.player.audiosync.asr.AsrLock>())
        val latch = java.util.concurrent.CountDownLatch(1)
        val engine = AsrSyncEngine(speech(media, heardMs), onLock = { locks += it; latch.countDown() })
        engine.startSession(target, listOf(com.nuvio.app.features.player.audiosync.asr.ReferenceSubtitle("en", english, bridge)))
        // Twelve lines heard near the start: far too short a span to judge a frame rate from words.
        val random = Random(8)
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        media.take(12).forEachIndexed { line, (start, _, _) ->
            engine.offerSegment((start / frameMs).toInt(), FloatArray(SileroVad.CHUNK_SAMPLES * 10) { line.toFloat() })
        }
        val recognised = java.util.concurrent.atomic.AtomicInteger()
        engine.setRecognizer { samples ->
            recognised.incrementAndGet()
            val line = samples[0].toInt()
            val (start, end, text) = media[line]
            val segmentStartSec = (start / frameMs).toInt() * frameMs / 1_000.0
            val words = text.split(' ')
            words.mapIndexed { k, word ->
                val t = (start + (end - start) * k / words.size) / 1_000.0 + random.nextDouble(-0.1, 0.1)
                (t - segmentStartSec) to word
            }
        }
        assertTrue(latch.await(30, java.util.concurrent.TimeUnit.SECONDS), "no lock")
        // Let every line be recognised and evaluated before reading the latest result.
        val deadline = System.currentTimeMillis() + 60_000
        while (recognised.get() < 12 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        var settled = locks.size
        do {
            Thread.sleep(1_000)
            val now = locks.size
            val stable = now == settled
            settled = now
        } while (!stable && System.currentTimeMillis() < deadline)
        engine.release()
        return locks.last()
    }

    /** Clean speech exactly where [cues] are; only within [knownSec] when given (unanalysed elsewhere). */
    private fun speech(
        cues: List<Triple<Long, Long, String>>,
        heardMs: Long = Long.MAX_VALUE,
        knownSec: List<IntRange>? = null,
    ): SpeechTimeline {
        val frameMs = SpeechTimeline.FRAME_DURATION_MS
        val timeline = SpeechTimeline()
        val frames = (minOf(cues.maxOf { it.second } + 30_000, heardMs) / frameMs).toInt()
        val speaking = BooleanArray(frames)
        for ((a, b, _) in cues) {
            val from = ((a + SubtitleAudioAligner.DETECTOR_BIAS_MS) / frameMs).toInt()
            val to = ((b + SubtitleAudioAligner.DETECTOR_BIAS_MS) / frameMs).toInt()
            for (f in from until to.coerceAtMost(frames)) speaking[f] = true
        }
        for (f in 0 until frames) {
            if (knownSec != null && knownSec.none { (f * frameMs / 1_000).toInt() in it }) continue
            timeline.record(f, if (speaking[f]) 0.9f else 0.05f)
        }
        return timeline
    }

    /** Lines starting at [startsSec] (subtitle time), [words] words each, 1.5 s long. */
    private fun lines(random: Random, startsSec: List<Double>, words: Int): List<Triple<Long, Long, String>> =
        startsSec.map { start ->
            val a = (start * 1_000).toLong()
            Triple(a, a + 1_500L, List(words) { vocabulary[random.nextInt(vocabulary.size)] }.joinToString(" "))
        }

    /**
     * Like Z Nation 2x13's end: a line every few seconds until a break (here at 13:20), then a few
     * short shouted lines a minute or so apart, two in each 2 minutes of the film. The release is
     * 4.27 s later than the subtitle before the break and 5.57 s after it.
     */
    private fun quietLastAct(random: Random): Pair<List<Triple<Long, Long, String>>, List<Triple<Long, Long, String>>> {
        // Irregular like real dialogue (evenly spaced lines fit every multiple of their spacing).
        val starts = generateSequence(20.0) { it + 1.7 + random.nextDouble(4.5) }.takeWhile { it < 765.0 }.toList()
        val subtitle = lines(random, starts, words = 5) +
            lines(random, listOf(850.0, 900.0, 980.0, 1_030.0).map { it - 5.57 }, words = 2)
        val media = subtitle.map { (a, b, t) ->
            val shift = if (a < 800_000L) 4_270L else 5_570L
            Triple(a + shift, b + shift, t)
        }
        return subtitle to media
    }

    @Test
    fun aQuietLastActIsJudgedOverSeveralWindows() {
        val (subtitle, media) = quietLastAct(Random(21))
        val heard = media.flatMapIndexed { line, (start, end, text) ->
            val words = text.split(' ')
            words.mapIndexed { k, word -> HeardWord((start + (end - start) * k / words.size) / 1_000.0, word, line) }
        }
        val fits = WordAnchorMatcher(subtitle).localFits(heard, 1.0)
        val after = fits.filter { it.anchorSec > 800.0 }
        assertTrue(after.isNotEmpty(), "no region after the break: $fits")
        assertTrue(after.all { abs(it.shiftSec - 5.57) < 0.2 }, "after $after")
        assertTrue(after.sumOf { it.segments } >= 4, "after $after")
    }

    @Test
    fun theStepInAQuietLastActIsFound() {
        val (subtitle, media) = quietLastAct(Random(22))
        val target = SubtitleSpeechTrack.fromCues(subtitle.map { (a, b, _) -> Triple(a, b, "linie") })
        val bridge = assertNotNull(SubtitleBridge.align(target, SubtitleSpeechTrack.fromCues(subtitle)))
        val lock = lockFromHeardWords(media, target, subtitle, bridge, listOf(0..1_100))
        assertTrue(lock.final, "lock $lock")
        assertEquals(2, lock.segments.size, "segments ${lock.segments}")
        val (before, after) = lock.segments
        assertTrue(abs(before.shiftMs - 4_270.0) < 200.0, "before $before")
        assertTrue(abs(after.shiftMs - 5_570.0) < 200.0, "after $after")
        assertTrue(after.fromMediaMs in 765_000L..855_000L, "step at ${after.fromMediaMs}")
    }

    @Test
    fun aSettledMappingIsNotRedrawnBehindThePlayheadOrForLittleAhead() {
        // SAMplay 2.0.6: the locks the phone took in turn on Z Nation 3x7 between 34:00 and 38:00.
        fun seg(fromMs: Long, shiftMs: Double) = SubtitleSyncSegment(fromMs, 1.0, shiftMs)
        val head = listOf(seg(0, 186.0), seg(1_713_776, 2_638.0), seg(1_774_432, 3_612.0))
        val at2031 = head + seg(2_031_008, 4_561.0)
        val at1989 = head + seg(1_989_472, 4_178.0)
        val at2003 = head + seg(2_003_648, 4_622.0)
        val subStep = head + listOf(seg(2_031_008, 4_578.0), seg(2_098_720, 4_281.0))
        // Where the step falls only matters before the playhead reaches it.
        assertTrue(AsrSyncEngine.placesAlikeAhead(at2031, at2003, 2_058_000L, 150.0))
        assertFalse(AsrSyncEngine.placesAlikeAhead(at2031, at2003, 1_900_000L, 150.0))
        // 2031 s and 1989 s differ by 0.38 s ahead: close enough to count as a flip back (0.4 s).
        assertTrue(AsrSyncEngine.placesAlikeAhead(at1989, at2031, 2_058_000L, 400.0))
        assertFalse(AsrSyncEngine.placesAlikeAhead(at1989, at2031, 2_058_000L, 150.0))
        // The 0.3 s step that came and went is within the flip range of the mapping without it.
        assertTrue(AsrSyncEngine.placesAlikeAhead(at2031, subStep, 2_200_000L, 400.0))
        // A new step ahead of the playhead is never a flip.
        assertFalse(AsrSyncEngine.placesAlikeAhead(at2031, at2031 + seg(2_424_448, 6_536.0), 2_300_000L, 400.0))
    }

    @Test
    fun aSettledMappingChangesOnlyWhereTheWordsPlaceItBetter() {
        // SAMplay 2.0.6: 2.0.6 on the phone, Z Nation 3x7 at 34:00-36:00 (each change 0.37-0.42 s).
        fun seg(fromMs: Long, shiftMs: Double) = SubtitleSyncSegment(fromMs, 1.0, shiftMs)
        val head = listOf(seg(0, 159.0), seg(1_761_311, 2_376.0), seg(1_774_144, 3_613.0))
        val at2031 = head + seg(2_031_008, 4_505.0)
        val at1989 = head + seg(1_989_472, 4_139.0)
        val heard = listOf(1_982.0 to 3_300.0, 2_042.0 to 4_600.0, 2_101.0 to 4_200.0)
        val weights = listOf(4, 5, 1)
        // The words after 2031 s say 4.6 s: moving the step to 1989 s at 4.1 s is not taken, the way back is.
        assertFalse(AsrSyncEngine.fitsWordsBetterAhead(at2031, at1989, heard, weights, 1.0, 2_037_000L))
        assertTrue(AsrSyncEngine.fitsWordsBetterAhead(at1989, at2031, heard, weights, 1.0, 2_037_000L))
        // A new step the words after it agree with is taken, and kept when the next result drops it.
        val withStep = at2031 + seg(2_177_856, 3_753.0)
        val later = heard + listOf(2_118.0 to 4_300.0, 2_190.0 to 3_700.0)
        val laterWeights = weights + listOf(9, 6)
        assertTrue(AsrSyncEngine.fitsWordsBetterAhead(at2031, withStep, later, laterWeights, 1.0, 2_135_000L))
        assertFalse(AsrSyncEngine.fitsWordsBetterAhead(withStep, head + seg(2_003_648, 4_658.0), later, laterWeights, 1.0, 2_135_000L))
        // No words where the two differ: the change is not held back.
        assertTrue(AsrSyncEngine.fitsWordsBetterAhead(at2031, at2031 + seg(2_424_448, 6_536.0), heard, weights, 1.0, 2_037_000L))
        // 2.0.7 on the phone at 38:30: better at the step at 40:24, 0.4 s worse where the playhead is. Not taken;
        // the step alone is.
        val shown = listOf(seg(0, 1_401.0), seg(1_761_311, 3_613.0), seg(2_031_008, 4_577.0), seg(2_098_720, 4_280.0))
        val mixed = listOf(seg(0, 1_401.0), seg(1_761_311, 3_613.0), seg(2_003_648, 4_680.0), seg(2_424_448, 8_376.0))
        val there = listOf(2_321.0 to 4_300.0, 2_353.0 to 4_000.0, 2_494.0 to 7_500.0)
        val thereWeights = listOf(7, 4, 11)
        assertFalse(AsrSyncEngine.fitsWordsBetterAhead(shown, mixed, there, thereWeights, 1.0, 2_310_000L))
        assertTrue(AsrSyncEngine.fitsWordsBetterAhead(shown, shown + seg(2_424_448, 7_500.0), there, thereWeights, 1.0, 2_310_000L))
    }

    @Test
    fun aFoundStepStaysUntilTheWordsAfterItSayOtherwise() {
        fun seg(fromMs: Long, shiftMs: Double) = SubtitleSyncSegment(fromMs, 1.0, shiftMs)
        val previous = listOf(seg(0, -65.0), seg(888_384, 1_290.0), seg(1_349_504, 2_522.0), seg(1_807_328, 3_927.0))
        val dropped = listOf(seg(0, -65.0), seg(888_384, 1_290.0), seg(1_349_504, 2_531.0))
        // Z Nation at 30:33: one more word and the region after the last step was not sure for a moment.
        assertTrue(AsrSyncEngine.keepsLaterSteps(previous, dropped, listOf(1_100.0 to 1_290.0, 1_600.0 to 2_530.0)))
        // Words after the step that agree with it keep it too.
        assertTrue(AsrSyncEngine.keepsLaterSteps(previous, dropped, listOf(1_600.0 to 2_530.0, 1_850.0 to 3_950.0)))
        // Words after it at the offset before it: the step was wrong.
        assertFalse(AsrSyncEngine.keepsLaterSteps(previous, dropped, listOf(1_600.0 to 2_530.0, 1_850.0 to 2_560.0)))
        // Another earlier mapping, or no step dropped: an ordinary update.
        assertFalse(AsrSyncEngine.keepsLaterSteps(previous, listOf(seg(0, 400.0), seg(888_384, 1_290.0)), emptyList()))
        assertFalse(AsrSyncEngine.keepsLaterSteps(previous, previous.map { it.copy(shiftMs = it.shiftMs + 500.0) }, emptyList()))
    }

    /**
     * Like Z Nation 2x15: steady dialogue, and from 16:58.8 on the release is 1.7 s later than
     * before it (3.9 s after the subtitle instead of 2.2 s). Each line is one speech segment with a
     * third of its words lost.
     */
    private fun stepInSteadyDialogue(random: Random): Pair<List<Triple<Long, Long, String>>, List<HeardWord>> {
        val subtitle = script(random, 420)
        val media = subtitle.map { (a, b, t) ->
            val shift = if (a + 2_200L < 1_018_800L) 2_200L else 3_900L
            Triple(a + shift, b + shift, t)
        }
        val heard = media.flatMapIndexed { line, (start, end, text) ->
            val words = text.split(' ')
            words.mapIndexedNotNull { k, word ->
                if (random.nextFloat() < 0.33f) return@mapIndexedNotNull null
                HeardWord((start + (end - start) * k / words.size) / 1_000.0 + random.nextDouble(-0.2, 0.2), word, line)
            }
        }
        return subtitle to heard
    }

    @Test
    fun aStepInsideSteadyDialogueIsFoundAFewLinesAfterIt() {
        // It used to wait for the next fixed 2-minute window to fill: 85 s on the TV.
        for (seed in 31..36) {
            val (subtitle, heard) = stepInSteadyDialogue(Random(seed))
            val matcher = WordAnchorMatcher(subtitle)
            var foundAt: Double? = null
            var between: Pair<Double, Double>? = null
            var edge = 990.0
            while (edge < 1_150.0) {
                val fits = matcher.localFits(heard.filter { it.timeSec <= edge }, 1.0)
                assertTrue(fits.isNotEmpty(), "seed $seed at $edge: nothing")
                val new = fits.firstOrNull { abs(it.shiftSec - 3.9) < 0.3 }
                if (new == null) {
                    assertTrue(fits.all { abs(it.shiftSec - 2.2) < 0.3 }, "seed $seed at $edge: $fits")
                } else {
                    assertTrue(fits.all { abs(it.shiftSec - 2.2) < 0.3 || abs(it.shiftSec - 3.9) < 0.3 }, "seed $seed at $edge: $fits")
                    val old = fits.last { it.lastSec < new.firstSec }
                    val now = old.lastSec to new.firstSec
                    if (between == null) {
                        foundAt = edge
                        between = now
                    }
                    // Once found, the step stays where it is as more is heard, instead of jumping around.
                    assertTrue(abs(now.first - between.first) < 5.0 && abs(now.second - between.second) < 5.0, "seed $seed at $edge: $now, was $between")
                }
                edge += 2.0
            }
            val found = assertNotNull(foundAt, "seed $seed: no step")
            assertTrue(found - 1_018.8 < 30.0, "seed $seed: step found only with words up to $found")
            // A line that starts before the step is wholly on the old offset, even where it runs past it.
            val oldLines = subtitle.filter { (a, _, _) -> a + 2_200L < 1_018_800L }
            val lastOldEnd = (oldLines.last().second + 2_200L) / 1_000.0
            val firstNewStart = (subtitle[oldLines.size].first + 3_900L) / 1_000.0
            val (lastOld, firstNew) = between!!
            assertTrue(
                lastOld <= lastOldEnd + 0.25 && firstNew >= firstNewStart - 0.25 && firstNew - lastOld < 15.0,
                "seed $seed: step between $between, lines end $lastOldEnd and start $firstNewStart",
            )
        }
    }

    @Test
    fun aFewStrayLinesAreNoStep() {
        // Two lines heard where the subtitle has them 25 s later (said twice, subtitled once).
        val random = Random(37)
        val subtitle = script(random, 200)
        val heard = hear(subtitle, 2.2, random, upToLine = 200).toMutableList()
        val strays = listOf(100, 101).flatMap { line ->
            val (start, end, text) = subtitle[line + 6]
            val words = text.split(' ')
            words.mapIndexed { k, word -> HeardWord((start + (end - start) * k / words.size) / 1_000.0 - 25.0 + 2.2, word, 10_000 + line) }
        }
        heard += strays
        val fits = WordAnchorMatcher(subtitle).localFits(heard.sortedBy { it.timeSec }, 1.0)
        assertTrue(fits.isNotEmpty() && fits.all { abs(it.shiftSec - 2.2) < 0.3 }, "fits $fits")
    }

    @Test
    fun aStepNotHeardYetWaitsJustBeforeTheWordsAfterIt() {
        // Z Nation 2x15 at 7-10 min: words heard up to the playhead's look-ahead, and from a spot
        // sampled at 12:15, which is on the offset after a step at 9:51; nothing analysed between.
        // The step's place jumped between the end of what was heard and that spot with every word.
        val english = script(Random(41), 250)
        val media = english.map { (a, b, t) ->
            val shift = if (a + 400L < 591_000L) 400L else 2_200L
            Triple(a + shift, b + shift, t)
        }
        val target = SubtitleSpeechTrack.fromCues(english.map { (a, b, _) -> Triple(a, b, "linie") })
        val bridge = assertNotNull(SubtitleBridge.align(target, SubtitleSpeechTrack.fromCues(english)))
        fun lockHeardUpTo(edgeSec: Int) = lockFromHeardWords(
            media, target, english, bridge, listOf(30..edgeSec, 735..800),
            timeline = speech(media, knownSec = listOf(0..edgeSec + 5, 730..805)),
        )
        val spotStartMs = media.first { it.first >= 735_000L }.first
        for (edge in listOf(470, 520, 560)) {
            val lock = lockHeardUpTo(edge)
            assertEquals(2, lock.segments.size, "edge $edge: ${lock.segments}")
            val (before, after) = lock.segments
            assertTrue(abs(before.shiftMs - 400.0) < 150.0, "edge $edge: before $before")
            assertTrue(abs(after.shiftMs - 2_200.0) < 150.0, "edge $edge: after $after")
            assertTrue(abs(after.fromMediaMs - spotStartMs) < 3_000L, "edge $edge: step at ${after.fromMediaMs}, spot at $spotStartMs")
        }
        // Heard past it, the step is found where it is.
        val lock = lockHeardUpTo(650)
        assertEquals(2, lock.segments.size, "${lock.segments}")
        assertTrue(abs(lock.segments[1].fromMediaMs - 591_000L) < 5_000L, "step at ${lock.segments[1].fromMediaMs}")
    }

    /**
     * SAMplay 2.0.4: Z Nation 3x3 as the TV logged it, (media s, target shift s, speech segments) of
     * each region: the offset went from about +0.15 s to -0.25 s and -0.5 s a little at a time, never
     * by the 0.6 s of a step, and the subtitle stayed half a second late.
     */
    private val zNation3x3 = listOf(
        Triple(28, 0.0, 7), Triple(111, 0.1, 3), Triple(130, 0.3, 7), Triple(156, -0.0, 5), Triple(208, 0.2, 9),
        Triple(228, 0.2, 12), Triple(279, 0.0, 5), Triple(304, 0.3, 9), Triple(361, 0.1, 5), Triple(421, 0.1, 12),
        Triple(463, 0.1, 9), Triple(561, -0.1, 6), Triple(574, -0.1, 4), Triple(603, -0.1, 22), Triple(660, -0.4, 6),
        Triple(665, -0.1, 16), Triple(687, -0.2, 17), Triple(714, -0.3, 10), Triple(722, -0.2, 15), Triple(762, -0.3, 11),
        Triple(841, -0.2, 9), Triple(882, -0.3, 11), Triple(960, -0.3, 6), Triple(996, -0.2, 9), Triple(1_088, -0.3, 8),
        Triple(1_156, -0.5, 8), Triple(1_175, -0.5, 6), Triple(1_300, -0.5, 2), Triple(1_498, -0.5, 2),
    )

    @Test
    fun anOffsetThatMovesALittleAtATimeIsFollowed() {
        val shifts = zNation3x3.map { it.second * 1_000.0 }
        val segments = zNation3x3.map { it.third }
        val runs = AsrSyncEngine.driftLevels(shifts, segments, zNation3x3.map { it.first.toDouble() })
        fun levelAt(sec: Int): Double {
            val run = runs.first { run -> zNation3x3.indexOfFirst { it.first >= sec }.let { it in run } }
            return run.sumOf { shifts[it] * segments[it] } / run.sumOf { segments[it] }
        }
        assertTrue(runs.size >= 3, "runs $runs")
        assertTrue(levelAt(200) > 50.0, "start ${levelAt(200)}: $runs")
        assertTrue(abs(levelAt(900) + 220.0) < 60.0, "at 15 min ${levelAt(900)}: $runs")
        assertTrue(abs(levelAt(1_160) + 500.0) < 60.0, "at 19 min ${levelAt(1_160)}: $runs")
    }

    @Test
    fun aSteadyOffsetHasOneLevel() {
        for (seed in 1..30) {
            val random = Random(seed)
            val count = 10 + random.nextInt(40)
            val shifts = List(count) { 300.0 + random.nextDouble(-120.0, 120.0) }
            val segments = List(count) { 3 + random.nextInt(20) }
            val withOutlier = shifts.toMutableList().also { it[count / 2] += if (seed % 2 == 0) 450.0 else -450.0 }
            val atSec = List(count) { it * 60.0 }
            assertEquals(1, AsrSyncEngine.driftLevels(shifts, segments, atSec).size, "seed $seed")
            assertEquals(1, AsrSyncEngine.driftLevels(withOutlier, segments, atSec).size, "seed $seed with an outlier")
        }
    }

    @Test
    fun levelsArePlacedByTheirWordsWhereTheSpeechWanders() {
        // Three levels of one part: the speech places two 80 ms later than their words, and the
        // third 400 ms off (a short stretch alone): that one goes where its words put it, +80 ms.
        val placed = AsrSyncEngine.calibratedLevels(
            words = listOf(150.0, -100.0, -350.0, 2_000.0),
            fine = listOf(230.0, -20.0, 50.0, null),
            groups = listOf(0, 0, 0, 1),
        )
        assertEquals(listOf(230.0, -20.0, -270.0, 2_000.0), placed)
    }

    @Test
    fun aSlowDriftIsFollowedByTheMapping() {
        // The release runs away from the subtitle by 0.6 ms a second: +0.2 s at the start, -0.5 s
        // after 20 minutes. Each place must be within 160 ms of the truth, not 0.5 s late.
        val english = script(Random(51), 300)
        fun truthMs(subtitleMs: Long): Double = 200.0 - 0.6 * subtitleMs / 1_000.0
        val media = english.map { (a, b, t) -> Triple(a + truthMs(a).toLong(), b + truthMs(a).toLong(), t) }
        val target = SubtitleSpeechTrack.fromCues(english.map { (a, b, _) -> Triple(a, b, "linie") })
        val bridge = assertNotNull(SubtitleBridge.align(target, SubtitleSpeechTrack.fromCues(english)))
        val lastSec = (media.last().second / 1_000).toInt()
        val lock = lockFromHeardWords(media, target, english, bridge, listOf(0..lastSec))
        val model = SubtitleSyncModel(lock.segments)
        for (line in english.indices step 20) {
            val (a, _, _) = english[line]
            val atMs = media[line].first
            val errorMs = model.delayUsAt(atMs * 1_000L) / 1_000.0 - truthMs(a)
            assertTrue(abs(errorMs) <= 160.0, "at ${atMs / 1_000}s off by ${errorMs.toInt()} ms: ${lock.segments}")
        }
    }

    @Test
    fun aRegionCloserToTheNewOffsetGoesAfterTheStep() {
        // Z Nation 3x8 at 11-13 min: a region 0.4 s off was cut with the old level, then with the
        // new one, and the step jumped between 11:47 and 12:55 with every few words.
        assertEquals(
            2,
            AsrSyncEngine.stepBoundary(listOf(100.0, 0.0, -400.0), listOf(14, 6, 10), listOf(-600.0, -650.0), listOf(3, 11)),
        )
        // A clean cut stays where it is.
        assertEquals(2, AsrSyncEngine.stepBoundary(listOf(0.0, 20.0), listOf(10, 8), listOf(-600.0, -620.0), listOf(10, 5)))
        // A first region of the new level closer to the old offset goes before the step.
        assertEquals(3, AsrSyncEngine.stepBoundary(listOf(0.0, 10.0), listOf(10, 10), listOf(-150.0, -600.0, -620.0), listOf(5, 10, 10)))
        // Each level keeps a region.
        assertEquals(1, AsrSyncEngine.stepBoundary(listOf(-600.0), listOf(4), listOf(-600.0), listOf(4)))
    }

    @Test
    fun aStepTheSpeechPutsUnderAStepsSizeIsKept() {
        // Z Nation 3x7 at 34 min: the words put a step at about 0.65 s, the speech at a little
        // under 0.6 s, and it came and went with every few words. Here the words say 1.05 s (two
        // word offsets need 1 s between them to be told apart) and the speech 0.55 s: two parts,
        // and the step between them stays, at the size the speech gives.
        val english = script(Random(61), 300)
        fun shifted(afterMs: Long) = english.map { (a, b, t) ->
            val shift = if (a < 600_000L) 400L else afterMs
            Triple(a + shift, b + shift, t)
        }
        val heardMedia = shifted(1_450L)
        val speechMedia = shifted(950L)
        val target = SubtitleSpeechTrack.fromCues(english.map { (a, b, _) -> Triple(a, b, "linie") })
        val bridge = assertNotNull(SubtitleBridge.align(target, SubtitleSpeechTrack.fromCues(english)))
        val lock = lockFromHeardWords(
            heardMedia, target, english, bridge, listOf(450..720),
            timeline = speech(speechMedia, knownSec = listOf(440..730)),
        )
        assertEquals(2, lock.segments.size, "${lock.segments}")
        val model = SubtitleSyncModel(lock.segments)
        fun delayAtMs(mediaMs: Long): Double = model.delayUsAt(mediaMs * 1_000L) / 1_000.0
        assertTrue(abs(delayAtMs(520_000L) - 400.0) < 150.0, "before: ${delayAtMs(520_000L)} ${lock.segments}")
        assertTrue(abs(delayAtMs(680_000L) - 950.0) < 150.0, "after: ${delayAtMs(680_000L)} ${lock.segments}")
        assertTrue(abs(lock.segments[1].fromMediaMs - 600_400L) < 10_000L, "step at ${lock.segments[1].fromMediaMs}")
    }

    @Test
    fun aStepUnderASecondIsSeenByTheWords() {
        // SAMplay 2.0.5: two word offsets under 1 s apart were one level, and the words more than
        // 0.5 s from it were dropped: after a step of 0.65 s nothing was seen for up to a minute and
        // a half, or the new offset came and went with every few words (Z Nation 3x7 at 34 min).
        for (seed in 31..36) {
            val random = Random(seed)
            val subtitle = script(random, 420)
            val media = subtitle.map { (a, b, t) ->
                val shift = if (a + 2_200L < 1_018_800L) 2_200L else 2_850L
                Triple(a + shift, b + shift, t)
            }
            val heard = media.flatMapIndexed { line, (start, end, text) ->
                val words = text.split(' ')
                words.mapIndexedNotNull { k, word ->
                    if (random.nextFloat() < 0.33f) return@mapIndexedNotNull null
                    HeardWord((start + (end - start) * k / words.size) / 1_000.0 + random.nextDouble(-0.2, 0.2), word, line)
                }
            }
            val matcher = WordAnchorMatcher(subtitle)
            var foundAt: Double? = null
            var edge = 1_000.0
            while (edge < 1_150.0) {
                val fits = matcher.localFits(heard.filter { it.timeSec <= edge }, 1.0)
                // Each region is at the offset where it is.
                for (fit in fits) {
                    val truth = if (fit.lastSec < 1_019.2) 2.2 else if (fit.firstSec > 1_019.2) 2.85 else continue
                    assertTrue(abs(fit.shiftSec - truth) < 0.25, "seed $seed at $edge: $fit")
                }
                val after = fits.filter { it.firstSec > 1_019.2 }
                if (after.isNotEmpty() && foundAt == null) foundAt = edge
                // Once seen, the words after the step stay seen.
                if (foundAt != null) assertTrue(after.isNotEmpty(), "seed $seed at $edge: lost, $fits")
                edge += 2.0
            }
            val found = assertNotNull(foundAt, "seed $seed: nothing after the step")
            assertTrue(found - 1_018.8 < 30.0, "seed $seed: seen only with words up to $found")
        }
    }

    @Test
    fun aStepUnderASecondIsTakenFromTheWords() {
        // SAMplay 2.0.5: Z Nation 3x7 at 34 min as it was: the words put the step at 0.65 s and the
        // speech at 0.6 s. The words after it were dropped, so the step came and went; now two
        // parts, and the step between them stays.
        val english = script(Random(61), 300)
        fun shifted(afterMs: Long) = english.map { (a, b, t) ->
            val shift = if (a < 600_000L) 400L else afterMs
            Triple(a + shift, b + shift, t)
        }
        val heardMedia = shifted(1_050L)
        val speechMedia = shifted(1_000L)
        val target = SubtitleSpeechTrack.fromCues(english.map { (a, b, _) -> Triple(a, b, "linie") })
        val bridge = assertNotNull(SubtitleBridge.align(target, SubtitleSpeechTrack.fromCues(english)))
        val lock = lockFromHeardWords(
            heardMedia, target, english, bridge, listOf(450..720),
            timeline = speech(speechMedia, knownSec = listOf(440..730)),
        )
        assertEquals(2, lock.segments.size, "${lock.segments}")
        val model = SubtitleSyncModel(lock.segments)
        fun delayAtMs(mediaMs: Long): Double = model.delayUsAt(mediaMs * 1_000L) / 1_000.0
        assertTrue(abs(delayAtMs(520_000L) - 400.0) < 150.0, "before: ${delayAtMs(520_000L)} ${lock.segments}")
        assertTrue(abs(delayAtMs(680_000L) - 1_000.0) < 150.0, "after: ${delayAtMs(680_000L)} ${lock.segments}")
        assertTrue(abs(lock.segments[1].fromMediaMs - 600_400L) < 10_000L, "step at ${lock.segments[1].fromMediaMs}")
    }

    @Test
    fun aStepIsNotTakenForAFrameRate() {
        // SAMplay 2.0.5: words from 7:30 to 12:00 with a step at 10:00. At x1.001 (0.1 s a minute)
        // both sides of a step of 0.65 or 1.05 s fit one cluster, and the phone took the release as
        // stretched: each side then drifted against its own words (Z Nation 3x7).
        for (afterMs in listOf(1_050L, 1_450L)) for (seed in 61..66) {
            val english = script(Random(seed), 300)
            val media = english.map { (a, b, t) ->
                val shift = if (a < 600_000L) 400L else afterMs
                Triple(a + shift, b + shift, t)
            }
            val random = Random(13)
            val heard = media.withIndex().filter { it.value.first / 1_000 in 450..720 }.flatMap { (line, m) ->
                val (start, end, text) = m
                val words = text.split(' ')
                words.mapIndexed { k, word -> HeardWord((start + (end - start) * k / words.size) / 1_000.0 + random.nextDouble(-0.1, 0.1), word, line) }
            }
            val fit = assertNotNull(WordAnchorMatcher(english).fit(heard))
            assertEquals(1.0, fit.scale, "step ${afterMs - 400} ms, seed $seed: $fit")
        }
    }

    @Test
    fun bridgeRejectsUnrelatedFiles() {
        val a = script(Random(4), 80)
        val b = script(Random(5), 80)
        assertNull(SubtitleBridge.align(SubtitleSpeechTrack.fromCues(a), SubtitleSpeechTrack.fromCues(b)))
    }
}
