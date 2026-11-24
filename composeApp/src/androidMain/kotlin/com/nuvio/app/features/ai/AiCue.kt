package com.nuvio.app.features.ai

import java.util.Locale

/** One subtitle line for the AI tools (from SAMplay); [translated] is filled by a translation. */
data class AiCue(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val translated: String? = null,
)

/** SubRip in and out for the AI tools' own files. */
object AiSrt {
    private val timing = Regex(
        """^\s*(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})\s*-->\s*(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})""",
    )

    /** [cues] as SubRip; [translated] writes each line's translation (its text when there is none). */
    fun toSrt(cues: List<AiCue>, translated: Boolean = false, both: Boolean = false): String {
        val out = StringBuilder()
        var index = 0
        cues.sortedBy { it.startMs }.forEach { cue ->
            val tr = cue.translated?.trim()?.takeIf { it.isNotEmpty() }
            val text = when {
                !translated -> cue.text
                both && tr != null && tr != cue.text.trim() -> cue.text.trim() + "\n" + tr
                else -> tr ?: cue.text
            }.trim()
            if (text.isEmpty()) return@forEach
            index++
            out.append(index).append('\n')
                .append(time(cue.startMs)).append(" --> ").append(time(cue.endMs.coerceAtLeast(cue.startMs + 1))).append('\n')
                .append(text).append("\n\n")
        }
        return out.toString()
    }

    /** SubRip or WebVTT text as cues (styling tags are kept as they are). */
    fun parse(text: String): List<AiCue> {
        val lines = text.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<AiCue>()
        var i = 0
        while (i < lines.size) {
            val match = timing.find(lines[i])
            if (match == null) {
                i++
                continue
            }
            val g = match.groupValues
            val start = ms(g[1], g[2], g[3], g[4])
            val end = ms(g[5], g[6], g[7], g[8])
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i])
                i++
            }
            if (body.isNotEmpty()) cues += AiCue(start, end, body.toString())
        }
        return cues
    }

    private fun ms(h: String, m: String, s: String, f: String): Long {
        val fraction = when (f.length) {
            1 -> f.toLong() * 100
            2 -> f.toLong() * 10
            else -> f.take(3).toLong()
        }
        return ((h.toLongOrNull() ?: 0L) * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + fraction
    }

    private fun time(ms: Long): String {
        val v = ms.coerceAtLeast(0L)
        return "%02d:%02d:%02d,%03d".format(Locale.ROOT, v / 3_600_000, v / 60_000 % 60, v / 1_000 % 60, v % 1_000)
    }
}
