package com.nuvio.app.features.player.audiosync.asr

import kotlin.math.abs

/** A recognised word at media time [timeSec]; [segment] groups words from one speech segment. */
internal data class HeardWord(val timeSec: Double, val text: String, val segment: Int)

/** Media = subtitle time * [scale] + [shiftSec], plus how strongly the heard words support it. */
internal data class AnchorFit(
    val scale: Double,
    val shiftSec: Double,
    val score: Double,
    val segments: Int,
    val runnerUp: Double,
    val spanSec: Double,
    /** Media time (median) of the agreeing words: where [shiftSec] holds exactly. */
    val anchorSec: Double = 0.0,
    /** Media time of the first and last agreeing word. */
    val firstSec: Double = anchorSec,
    val lastSec: Double = anchorSec,
) {
    val isConfident: Boolean
        get() = segments >= MIN_SEGMENTS && score >= MIN_SCORE && score >= RATIO * maxOf(runnerUp, 0.5)

    companion object {
        private const val MIN_SEGMENTS = 3
        private const val MIN_SCORE = 3.0
        private const val RATIO = 2.5
    }
}

/**
 * Matches recognised words against the words of one same-language subtitle file and finds the
 * mapping most of them agree on. Every recognised word that also occurs in the subtitle (a few
 * times at most) votes for "media time − subtitle time"; neighbouring words that match in order
 * vote much more strongly. A tight cluster of votes from several separate speech segments is an
 * unambiguous anchor, usually after the first handful of lines.
 */
internal class WordAnchorMatcher(cues: List<Triple<Long, Long, String>>) {
    private class SubWord(val timeSec: Double, val word: String, val firstInCue: Boolean)

    private val words = ArrayList<SubWord>()
    private val index = HashMap<String, MutableList<Int>>()

    init {
        for ((startMs, endMs, text) in cues.sortedBy { it.first }) {
            val tokens = tokenize(text)
            if (tokens.isEmpty()) continue
            val durationMs = (endMs - startMs).coerceAtLeast(1L)
            tokens.forEachIndexed { k, word ->
                // Word times inside a line are interpolated; the first word is the cue start exactly.
                val timeSec = (startMs + durationMs * k.toDouble() / tokens.size) / 1_000.0
                index.getOrPut(word) { ArrayList(2) }.add(words.size)
                words += SubWord(timeSec, word, k == 0)
            }
        }
    }

    val isEmpty: Boolean get() = words.isEmpty()

    private class Anchor(val mediaSec: Double, val subSec: Double, val weight: Double, val segment: Int, val first: Boolean)

    fun fit(heard: List<HeardWord>, maxShiftSec: Double = MAX_SHIFT_SEC): AnchorFit? {
        val anchors = anchors(heard)
        if (anchors.isEmpty()) return null
        val unit = cluster(anchors, 1.0, maxShiftSec)
        val span = anchors.maxOf { it.mediaSec } - anchors.minOf { it.mediaSec }
        // Over a short stretch every frame rate fits equally well; judge the rate only on a long span.
        if (span < MIN_RATE_SPAN_SEC) return unit
        // Other rates are tried even when no word fits at 1x: late in a film a frame-rate mismatch
        // can put every word further than [maxShiftSec] off at 1x, exactly when a rate is needed.
        val unitScore = unit?.score ?: 0.0
        var best = unit
        for (scale in RATE_CANDIDATES) {
            val fit = cluster(anchors, scale, maxShiftSec) ?: continue
            // A rate change drifts further every minute: it needs words from several places.
            if (fit.segments < MIN_RATE_SEGMENTS) continue
            if (fit.score > (best?.score ?: 0.0) * RATE_MARGIN && fit.score > unitScore * RATE_MARGIN) best = fit
        }
        if (best !== unit && best != null && !aStepFitsBetter(anchors, best)) return best
        // Counted word by word, the stretch heard most (around the playhead, often the opening)
        // decides: there every rate fits, so its words agree at 1x however wrong 1x is elsewhere.
        // Counted place by place instead (each part of the film a capped vote), a rate that words
        // from several far-apart places agree on can still be seen.
        val unitByPlace = cluster(anchors, 1.0, maxShiftSec, PLACE_CAP)
        var byPlace: AnchorFit? = null
        for (scale in RATE_CANDIDATES) {
            val fit = cluster(anchors, scale, maxShiftSec, PLACE_CAP) ?: continue
            if (fit.segments < MIN_RATE_SEGMENTS || fit.spanSec < MIN_RATE_SPAN_SEC || !fit.isConfident) continue
            if (aStepFitsBetter(anchors, fit)) continue
            if (fit.score > (byPlace?.score ?: 0.0) * RATE_MARGIN && fit.score > (unitByPlace?.score ?: 0.0) * RATE_MARGIN) {
                byPlace = fit
            }
        }
        return byPlace ?: unit
    }

    /**
     * SAMplay 2.0.5: whether the words a fit at another frame rate gathers ([rateFit]) lie at 1x on
     * two flat offsets with a step between them rather than on the slope that rate draws. A step
     * of half a second or so inside the heard words put both sides within one cluster at x1.001
     * (0.1 s a minute), so a release that matches the subtitle's rate was taken as stretched: each
     * part of it then drifted against its own words, by more the further from them (Z Nation 3x7).
     * Judged on each part of the film's ([LOCAL_WINDOW_SEC]) median offset at 1x, from 3 parts on.
     */
    private fun aStepFitsBetter(anchors: List<Anchor>, rateFit: AnchorFit): Boolean {
        if (rateFit.scale == 1.0) return false
        val places = anchors
            .filter { abs(it.mediaSec - rateFit.scale * it.subSec - rateFit.shiftSec) <= WINDOW_SEC }
            .groupBy { (it.mediaSec / LOCAL_WINDOW_SEC).toInt() }
            .filterValues { list -> list.sumOf { it.weight } >= 1.0 }
            .toSortedMap().values.toList()
        if (places.size < 3) return false
        val x = places.map { list -> median(list.map { it.subSec }) }
        val y = places.map { list -> median(list.map { it.mediaSec - it.subSec }) }
        fun spread(values: List<Double>): Double {
            val mean = values.average()
            return values.sumOf { (it - mean) * (it - mean) }
        }
        val rateSpread = spread(y.indices.map { y[it] - (rateFit.scale - 1.0) * x[it] })
        val stepSpread = (1 until y.size).minOf { k -> spread(y.subList(0, k)) + spread(y.subList(k, y.size)) }
        return stepSpread + RATE_STEP_PENALTY < rateSpread
    }

    /**
     * Where in the film the heard words agree on one offset at [scale]: where a release differs
     * from the subtitle by an inserted or removed scene, the offset differs between stretches,
     * which one fit over all words cannot show. Each confident stretch comes back in pieces of at
     * most [PIECE_SEC], sorted by [AnchorFit.anchorSec]; [AnchorFit.firstSec] and
     * [AnchorFit.lastSec] are its first and last agreeing word, so a step lies between the last
     * word of one stretch and the first of the next.
     *
     * SAMplay 2.0.4: the film used to be judged in fixed 2-minute windows. A step inside a window
     * split its words between two offsets, so neither was sure, and the step was only seen once
     * the next window had filled: Z Nation 2x15's step at 16:58 was taken at 18:23, the subtitle
     * 1.7 s late until then; and the gap between two sure windows left the step's place to a guess
     * that jumped back and forth. Now each heard speech segment votes for the offsets its words
     * fit, and the film is split into stretches of one offset where changing it pays for itself
     * ([offsetPath]): a few lines after a step agree on the new offset and start a stretch right
     * there, wherever it falls. Sparse dialogue needs no window either: a stretch counts its
     * segments wherever they are.
     */
    fun localFits(heard: List<HeardWord>, scale: Double): List<AnchorFit> {
        val entries = anchors(heard).mapNotNull { anchor ->
            val offset = anchor.mediaSec - scale * anchor.subSec
            if (abs(offset) <= MAX_SHIFT_SEC) Placed(anchor, offset) else null
        }
        if (entries.isEmpty()) return emptyList()
        val levels = offsetLevels(entries)
        if (levels.isEmpty()) return emptyList()
        val segments = entries.groupBy { it.anchor.segment }.values.sortedBy { list -> list.minOf { it.anchor.mediaSec } }
        val votes = Array(segments.size) { s ->
            DoubleArray(levels.size) { l ->
                minOf(SEGMENT_VOTE_CAP, segments[s].sumOf { if (abs(it.offset - levels[l]) <= LEVEL_TOLERANCE_SEC) it.anchor.weight else 0.0 })
            }
        }
        val path = offsetPath(votes)
        val stretches = ArrayList<Stretch>()
        var start = 0
        for (end in 1..segments.size) {
            if (end < segments.size && path[end] == path[start]) continue
            stretch(segments, votes, levels, path[start], start until end)?.let { stretches += it }
            start = end
        }
        // A short stretch between two that agree with each other is not a scene the release added
        // and removed again a minute later: a line said twice, or a misheard stretch.
        val excursions = stretches.indices.filter { i ->
            val before = stretches.getOrNull(i - 1) ?: return@filter false
            val after = stretches.getOrNull(i + 1) ?: return@filter false
            val here = stretches[i]
            abs(before.shiftSec - after.shiftSec) <= LEVEL_SPACING_SEC &&
                abs(here.shiftSec - before.shiftSec) > LEVEL_SPACING_SEC &&
                here.points.last().anchor.mediaSec - here.points.first().anchor.mediaSec < EXCURSION_MIN_SEC
        }.toSet()
        return stretches.filterIndexed { i, _ -> i !in excursions }.flatMap { pieces(it, scale) }.sortedBy { it.anchorSec }
    }

    private class Placed(val anchor: Anchor, val offset: Double)

    /** A sure stretch of [localFits]: its agreeing words in media order, its votes and its competition's. */
    private class Stretch(val shiftSec: Double, val points: List<Placed>, val score: Double, val runnerUp: Double)

    /**
     * Offsets the words could follow: the strongest clusters of votes (as in [cluster]), each from
     * at least two speech segments and at least [LEVEL_SPACING_SEC] apart, at most [MAX_LEVELS].
     */
    private fun offsetLevels(entries: List<Placed>): DoubleArray {
        val sorted = entries.sortedBy { it.offset }
        val peaks = ArrayList<Pair<Double, Double>>() // score to centre
        var from = 0
        val segmentsIn = HashMap<Int, Int>()
        for (to in sorted.indices) {
            segmentsIn.merge(sorted[to].anchor.segment, 1, Int::plus)
            while (sorted[to].offset - sorted[from].offset > WINDOW_SEC) {
                val segment = sorted[from].anchor.segment
                if (segmentsIn.merge(segment, -1, Int::plus) == 0) segmentsIn.remove(segment)
                from++
            }
            if (segmentsIn.size < 2) continue
            val window = sorted.subList(from, to + 1)
            val firsts = window.filter { it.anchor.first }.map { it.offset }
            val centre = if (firsts.size >= 2) median(firsts) else median(window.map { it.offset })
            peaks += window.sumOf { it.anchor.weight } * minOf(segmentsIn.size, 6) / 6.0 to centre
        }
        peaks.sortByDescending { it.first }
        val levels = ArrayList<Double>()
        for ((_, centre) in peaks) {
            if (levels.size >= MAX_LEVELS) break
            if (levels.all { abs(it - centre) > LEVEL_SPACING_SEC }) levels += centre
        }
        return levels.toDoubleArray()
    }

    /**
     * The offset (index into the levels) each speech segment, in media order, most likely follows:
     * the path that collects the most votes ([votes]: segment x level) when every change of offset
     * costs [STEP_COST]. Staying is free, so a stray segment does not move it; a few segments that
     * agree on another offset do, at the first of them.
     */
    private fun offsetPath(votes: Array<DoubleArray>): IntArray {
        val levelCount = votes[0].size
        var score = votes[0].copyOf()
        val from = Array(votes.size) { IntArray(levelCount) }
        for (s in 1 until votes.size) {
            var best = 0
            for (l in 1 until levelCount) if (score[l] > score[best]) best = l
            val next = DoubleArray(levelCount)
            for (l in 0 until levelCount) {
                val switched = score[best] - STEP_COST
                if (score[l] >= switched) {
                    next[l] = score[l] + votes[s][l]
                    from[s][l] = l
                } else {
                    next[l] = switched + votes[s][l]
                    from[s][l] = best
                }
            }
            score = next
        }
        val path = IntArray(votes.size)
        var level = 0
        for (l in 1 until levelCount) if (score[l] > score[level]) level = l
        for (s in votes.indices.reversed()) {
            path[s] = level
            level = from[s][level]
        }
        return path
    }

    /**
     * One stretch of the path (segments [range] at offset level [level]), or null when it is not
     * sure: at least [MIN_SEGMENTS] segments whose words vote for it, and far more votes for it
     * there than for any clearly different offset ([RUNNER_UP_MIN_SEC] away: word times inside a
     * line are only estimated, so votes spill over to the offsets next to the right one).
     */
    private fun stretch(
        segments: List<List<Placed>>,
        votes: Array<DoubleArray>,
        levels: DoubleArray,
        level: Int,
        range: IntRange,
    ): Stretch? {
        val score = range.sumOf { votes[it][level] }
        var runnerUp = 0.0
        for (other in levels.indices) {
            if (abs(levels[other] - levels[level]) <= RUNNER_UP_MIN_SEC) continue
            runnerUp = maxOf(runnerUp, range.sumOf { votes[it][other] })
        }
        val agreeing = range.filter { votes[it][level] >= SEGMENT_MIN_VOTE }
        if (agreeing.size < MIN_SEGMENTS || score < MIN_STRETCH_SCORE || score < RATIO * maxOf(runnerUp, 0.5)) return null
        // SAMplay 2.0.5: the words of every segment of the stretch that are nearer this offset than
        // any other, up to [LEVEL_SPACING_SEC] from it, not only those within [LEVEL_TOLERANCE_SEC]
        // of it. Two offsets under [LEVEL_SPACING_SEC] apart are one level, so after a step of half a
        // second the words on the new offset were dropped and the words went blind (Z Nation 3x7 at
        // 34 min); kept, they place the step themselves ([subStretches]).
        fun nearest(offset: Double): Int = levels.indices.minBy { abs(levels[it] - offset) }
        val points = range.map { s ->
            segments[s].filter { abs(it.offset - levels[level]) <= LEVEL_SPACING_SEC && nearest(it.offset) == level }
        }.filter { near -> near.sumOf { it.anchor.weight } >= SEGMENT_MIN_VOTE }.flatten()
            .sortedBy { it.anchor.mediaSec }
        if (points.isEmpty()) return null
        val firsts = points.filter { it.anchor.first }.map { it.offset }
        val shiftSec = if (firsts.size >= 2) median(firsts) else median(points.map { it.offset })
        return Stretch(shiftSec, points, score, runnerUp)
    }

    /**
     * SAMplay 2.0.5: where the offset inside one stretch moves by less than [LEVEL_SPACING_SEC]: its
     * points cut into runs of speech segments (least squares on each segment's offset, each extra
     * run paying [SUB_SPLIT_COST]; a run needs [SUB_MIN_SEGMENTS] segments and differs from the next
     * by [SUB_STEP_MIN_SEC]). A segment far from those around it counts only [SUB_CLIP_SEC] away.
     * Pieces never span two runs, so a step under a second is seen where it is.
     */
    private fun subStretches(points: List<Placed>): List<List<Placed>> {
        val bySegment = points.groupBy { it.anchor.segment }.values.sortedBy { list -> list.minOf { it.anchor.mediaSec } }
        val n = bySegment.size
        if (n < 2 * SUB_MIN_SEGMENTS) return listOf(points)
        val raw = bySegment.map { list ->
            val firsts = list.filter { it.anchor.first }.map { it.offset }
            if (firsts.isNotEmpty()) median(firsts) else median(list.map { it.offset })
        }
        val y = DoubleArray(n) { i ->
            val around = raw.subList(maxOf(0, i - 2), minOf(n, i + 3))
            val m = median(around)
            raw[i].coerceIn(m - SUB_CLIP_SEC, m + SUB_CLIP_SEC)
        }
        val sy = DoubleArray(n + 1)
        val syy = DoubleArray(n + 1)
        for (i in 0 until n) {
            sy[i + 1] = sy[i] + y[i]
            syy[i + 1] = syy[i] + y[i] * y[i]
        }
        fun spread(a: Int, b: Int): Double = (syy[b] - syy[a]) - (sy[b] - sy[a]) * (sy[b] - sy[a]) / (b - a)
        fun mean(a: Int, b: Int): Double = (sy[b] - sy[a]) / (b - a)
        val best = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
        val from = IntArray(n + 1) { -1 }
        best[0] = 0.0
        for (b in SUB_MIN_SEGMENTS..n) {
            for (a in 0..b - SUB_MIN_SEGMENTS) {
                if (best[a].isInfinite()) continue
                val cost = best[a] + spread(a, b) + if (a == 0) 0.0 else SUB_SPLIT_COST
                if (cost < best[b]) {
                    best[b] = cost
                    from[b] = a
                }
            }
        }
        if (best[n].isInfinite()) return listOf(points)
        val cuts = ArrayList<Int>()
        var end = n
        while (end > 0) {
            cuts.add(0, end)
            end = from[end]
        }
        // Neighbouring runs closer than [SUB_STEP_MIN_SEC] are one.
        val starts = mutableListOf(0)
        for (k in 0 until cuts.size - 1) {
            val a = starts.last()
            if (abs(mean(a, cuts[k]) - mean(cuts[k], cuts[k + 1])) >= SUB_STEP_MIN_SEC) starts += cuts[k]
        }
        starts += n
        return (0 until starts.size - 1).map { r -> bySegment.subList(starts[r], starts[r + 1]).flatten().sortedBy { it.anchor.mediaSec } }
    }

    /** [stretch] as fits of at most [PIECE_SEC] each, none across a step inside it ([subStretches]). */
    private fun pieces(stretch: Stretch, scale: Double): List<AnchorFit> {
        val pieces = ArrayList<MutableList<Placed>>()
        for (run in subStretches(stretch.points)) for ((k, point) in run.withIndex()) {
            val piece = pieces.lastOrNull()
            if (k > 0 && piece != null && point.anchor.mediaSec - piece.first().anchor.mediaSec <= PIECE_SEC) {
                piece += point
            } else {
                pieces += mutableListOf(point)
            }
        }
        return pieces.map { piece ->
            val firsts = piece.filter { it.anchor.first }.map { it.offset }
            val times = piece.map { it.anchor.mediaSec }
            val weight = piece.sumOf { it.anchor.weight }
            AnchorFit(
                scale = scale,
                shiftSec = if (firsts.size >= 2) median(firsts) else median(piece.map { it.offset }),
                score = weight,
                segments = piece.map { it.anchor.segment }.toSet().size,
                // The stretch's competition, in proportion to this piece's share of its votes.
                runnerUp = stretch.runnerUp * weight / stretch.score,
                spanSec = times.last() - times.first(),
                anchorSec = median(times),
                firstSec = times.first(),
                lastSec = times.last(),
            )
        }
    }

    /**
     * The confident fit at [scale] with places counted, not words (see [cluster]): what words
     * from several parts of the film agree on, even when one part was heard far more than the rest.
     */
    fun fitByPlace(heard: List<HeardWord>, scale: Double): AnchorFit? {
        val anchors = anchors(heard)
        if (anchors.isEmpty()) return null
        return cluster(anchors, scale, MAX_SHIFT_SEC, PLACE_CAP)?.takeIf { it.isConfident }
    }

    private fun anchors(heard: List<HeardWord>): List<Anchor> {
        val normalized = heard.map { it.copy(text = normalize(it.text)) }
        val out = ArrayList<Anchor>()
        for (i in normalized.indices) {
            val h = normalized[i]
            if (h.text.length < 3 || h.text in STOP_WORDS) continue
            val occurrences = index[h.text] ?: continue
            if (occurrences.size > MAX_OCCURRENCES) continue
            for (j in occurrences) {
                var weight = 1.0 / occurrences.size
                val next = normalized.getOrNull(i + 1)
                if (next != null && next.segment == h.segment && words.getOrNull(j + 1)?.word == next.text) weight += 1.0
                val previous = normalized.getOrNull(i - 1)
                if (previous != null && previous.segment == h.segment && words.getOrNull(j - 1)?.word == previous.text) {
                    weight += 1.0
                }
                out += Anchor(h.timeSec, words[j].timeSec, weight, h.segment, words[j].firstInCue)
            }
        }
        return out
    }

    /**
     * The tightest, best-supported group of votes at [scale]. With [placeCap], the words of each
     * [LOCAL_WINDOW_SEC] of the film count for at most that much together, so agreement between
     * places outweighs many words from one place.
     */
    private fun cluster(
        anchors: List<Anchor>,
        scale: Double,
        maxShiftSec: Double,
        placeCap: Double = Double.POSITIVE_INFINITY,
    ): AnchorFit? {
        val perPlace = HashMap<Int, Double>()
        fun weight(window: List<Pair<Anchor, Double>>): Double {
            if (placeCap.isInfinite()) return window.sumOf { it.first.weight }
            perPlace.clear()
            for ((anchor, _) in window) perPlace.merge((anchor.mediaSec / LOCAL_WINDOW_SEC).toInt(), anchor.weight, Double::plus)
            return perPlace.values.sumOf { minOf(it, placeCap) }
        }
        val entries = anchors
            .map { it to (it.mediaSec - scale * it.subSec) }
            .filter { abs(it.second) <= maxShiftSec }
            .sortedBy { it.second }
        if (entries.isEmpty()) return null
        var bestScore = -1.0
        var bestFrom = 0
        var bestTo = 0
        var bestSegments = 0
        var from = 0
        for (to in entries.indices) {
            while (entries[to].second - entries[from].second > WINDOW_SEC) from++
            val window = entries.subList(from, to + 1)
            val segments = window.map { it.first.segment }.toSet().size
            val score = weight(window) * minOf(segments, 6) / 6.0
            if (score > bestScore) {
                bestScore = score
                bestFrom = from
                bestTo = to
                bestSegments = segments
            }
        }
        val inCluster = entries.subList(bestFrom, bestTo + 1)
        val firsts = inCluster.filter { it.first.first }.map { it.second }
        val center = if (firsts.size >= 2) median(firsts) else median(inCluster.map { it.second })
        // Strongest competing cluster more than 2 s away.
        val far = entries.filter { abs(it.second - center) > 2.0 }
        var runnerUp = 0.0
        var f = 0
        for (t in far.indices) {
            while (far[t].second - far[f].second > WINDOW_SEC) f++
            runnerUp = maxOf(runnerUp, weight(far.subList(f, t + 1)))
        }
        val times = inCluster.map { it.first.mediaSec }
        return AnchorFit(
            scale = scale,
            shiftSec = center,
            score = bestScore,
            segments = bestSegments,
            runnerUp = runnerUp,
            spanSec = times.max() - times.min(),
            anchorSec = median(times),
            firstSec = times.min(),
            lastSec = times.max(),
        )
    }

    companion object {
        const val MAX_SHIFT_SEC = 60.0
        private const val WINDOW_SEC = 0.7
        private const val MAX_OCCURRENCES = 6
        private const val MIN_RATE_SPAN_SEC = 180.0
        private const val MIN_RATE_SEGMENTS = 5
        private const val LOCAL_WINDOW_SEC = 120.0

        /**
         * Offsets [localFits] tells apart, and how far a word may be from the one it votes for.
         * Tuned on Z Nation 2x15 (words from a full transcript, a third to half of them dropped and
         * their times moved by up to 0.4 s): both of its steps found, none made up.
         */
        private const val LEVEL_SPACING_SEC = 1.0
        private const val LEVEL_TOLERANCE_SEC = 0.5
        private const val RUNNER_UP_MIN_SEC = 1.5
        private const val MAX_LEVELS = 40

        /** Shortest stretch at another offset between two that agree (see [localFits]). */
        private const val EXCURSION_MIN_SEC = 120.0

        /** One speech segment's vote for an offset is capped: one long matched line is one line. */
        private const val SEGMENT_VOTE_CAP = 8.0

        /** Votes a change of offset must win by (see [offsetPath]): about two well-heard lines. */
        private const val STEP_COST = 6.0

        /** A segment agrees with an offset when its words give it at least this (one matched word). */
        private const val SEGMENT_MIN_VOTE = 1.0
        private const val MIN_SEGMENTS = 3
        private const val MIN_STRETCH_SCORE = 4.0
        private const val RATIO = 2.5

        /** See [subStretches]: about 3 segments either side of a 0.6 s step pay for it. */
        private const val SUB_SPLIT_COST = 0.5
        private const val SUB_MIN_SEGMENTS = 3
        private const val SUB_STEP_MIN_SEC = 0.25
        private const val SUB_CLIP_SEC = 0.3

        /** Longest piece of a stretch [localFits] returns. */
        private const val PIECE_SEC = 60.0
        private const val RATE_MARGIN = 1.3

        /** What a step must explain better than a frame rate by (s² over the parts; see [aStepFitsBetter]). */
        private const val RATE_STEP_PENALTY = 0.02

        /** Most one place (see [LOCAL_WINDOW_SEC]) adds when places are counted: a few matched words. */
        private const val PLACE_CAP = 1.5
        private val RATE_CANDIDATES = doubleArrayOf(
            24_000.0 / 23_976.0,
            23_976.0 / 24_000.0,
            25.0 / 23.976,
            23.976 / 25.0,
            25.0 / 24.0,
            24.0 / 25.0,
        )

        private val STOP_WORDS = (
            "the a an and or but to of in on at for is it i you he she we they me my your our his her its " +
                "be are was were am do did not no yes so that this with as what who how why oh uh um"
            ).split(' ').toHashSet()

        private val tagPattern = Regex("<[^>]+>|\\{[^}]*\\}")
        private val nonWord = Regex("[^\\p{L}\\p{Nd}']")

        fun normalize(word: String): String = word.lowercase().replace(nonWord, "").trim('\'')

        fun tokenize(text: String): List<String> =
            text.replace(tagPattern, " ").split(Regex("\\s+")).map(::normalize).filter { it.isNotEmpty() }

        private fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        }
    }
}
