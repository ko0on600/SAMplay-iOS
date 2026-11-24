package com.nuvio.app.features.player

/**
 * SAMplay 2.0.3: "Save subtitle" can write the subtitle as the user synced it (the user asked for
 * it, 19 Nov 2026): the subtitle delay is added to every line's start and end, so another player,
 * or the next watch, shows it in sync without moving it again. A positive delay shows the lines
 * later, as in the player (Exo's sidecar reads position - delay; MPV's sub-delay).
 *
 * Only the times change: the file keeps its format, styles, positions and line endings. Lines
 * pushed before 0:00 start at 0:00. SRT and WebVTT cue times and ASS/SSA Dialogue times are
 * moved; a file with none of them (MicroDVD frame numbers, for one) gives null, so it is never
 * saved as if it had been synced.
 */
internal object SubtitleTimeShift {
    // SRT 00:01:02,345 / WebVTT 00:01:02.345 or 01:02.345 (the hours are optional in WebVTT).
    private val cueTime = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})([,.])(\d{1,3})""")

    // ASS/SSA: "Dialogue: 0,0:01:02.34,0:01:04.56,Default,..." (Comment lines too).
    private val assEvent = Regex("""^((?:Dialogue|Comment):[^,]*,)(\d+:\d{1,2}:\d{1,2}[.:]\d{1,3}),(\d+:\d{1,2}:\d{1,2}[.:]\d{1,3})(,.*)$""")
    private val assTime = Regex("""(\d+):(\d{1,2}):(\d{1,2})([.:])(\d{1,3})""")

    fun shift(text: String, offsetMs: Long): String? {
        var moved = 0
        val lines = text.split('\n').map { raw ->
            val line = raw.removeSuffix("\r")
            val ending = if (raw.endsWith('\r')) "\r" else ""
            val shifted = shiftAssEvent(line, offsetMs) ?: shiftCueTimes(line, offsetMs)
            if (shifted == null) raw else {
                moved++
                shifted + ending
            }
        }
        return if (moved == 0) null else lines.joinToString("\n")
    }

    private fun shiftCueTimes(line: String, offsetMs: Long): String? {
        val arrow = line.indexOf("-->")
        if (arrow < 0) return null
        val start = cueTime.find(line.substring(0, arrow)) ?: return null
        val end = cueTime.find(line, arrow + 3) ?: return null
        return buildString {
            append(line, 0, start.range.first)
            append(cueTimeText(start, offsetMs))
            append(line, start.range.last + 1, end.range.first)
            append(cueTimeText(end, offsetMs))
            append(line, end.range.last + 1, line.length)
        }
    }

    private fun cueTimeText(match: MatchResult, offsetMs: Long): String {
        val (hours, minutes, seconds, separator, fraction) = match.destructured
        val ms = (hours.toLongOrNull() ?: 0L) * 3_600_000L + minutes.toLong() * 60_000L +
            seconds.toLong() * 1_000L + fraction.padEnd(3, '0').toLong()
        val total = (ms + offsetMs).coerceAtLeast(0L)
        val h = total / 3_600_000L
        val m = total / 60_000L % 60L
        val s = total / 1_000L % 60L
        val f = total % 1_000L
        val withHours = hours.isNotEmpty() || h > 0L
        return buildString {
            if (withHours) append(two(h)).append(':')
            append(two(m)).append(':').append(two(s)).append(separator).append(f.toString().padStart(3, '0'))
        }
    }

    private fun shiftAssEvent(line: String, offsetMs: Long): String? {
        val match = assEvent.matchEntire(line) ?: return null
        val (head, start, end, tail) = match.destructured
        return head + assTimeText(start, offsetMs) + "," + assTimeText(end, offsetMs) + tail
    }

    // ASS keeps hundredths of a second: H:MM:SS.cc.
    private fun assTimeText(time: String, offsetMs: Long): String {
        val (hours, minutes, seconds, separator, fraction) = assTime.matchEntire(time)!!.destructured
        val ms = hours.toLong() * 3_600_000L + minutes.toLong() * 60_000L + seconds.toLong() * 1_000L +
            fraction.padEnd(3, '0').take(3).toLong()
        val cs = ((ms + offsetMs).coerceAtLeast(0L) + 5L) / 10L
        return "${cs / 360_000L}:${two(cs / 6_000L % 60L)}:${two(cs / 100L % 60L)}$separator${two(cs % 100L)}"
    }

    private fun two(value: Long): String = value.toString().padStart(2, '0')
}
