package com.nuvio.app.features.servers

/**
 * Nuvio RS (from SAMplay 1.8.9): how long to wait before asking a drive (PikPak) for a new link
 * after the old one answered an HTTP error, and when to stop asking.
 *
 * PikPak answers some files 404 for a while and then serves them: the user had to open such a file
 * 4–5 times. Now the player asks again with growing waits, about a minute in all, before it shows
 * the error. One budget per video ([next]'s key); a link that worked for [calmMs] and then stopped
 * (it expired after a long while of playing) starts a new one.
 */
internal class LinkRetry(
    private val waitsMs: LongArray = WAITS_MS,
    private val calmMs: Long = CALM_MS,
) {
    private var key: String? = null
    private var used = 0
    private var lastMs = 0L

    /** The wait before the next new link of [key], or null when its budget is spent. */
    fun next(key: String, nowMs: Long): Long? {
        if (key != this.key || nowMs - lastMs > calmMs) {
            this.key = key
            used = 0
        }
        lastMs = nowMs
        if (used >= waitsMs.size) return null
        return waitsMs[used++]
    }

    /** The try [next] just handed out, from 1. */
    val attempt: Int get() = used

    /** How many tries a budget has. */
    val attempts: Int get() = waitsMs.size

    /** A new budget for the next error (a video opened by hand). */
    fun reset() {
        key = null
        used = 0
    }

    companion object {
        /** 59 s in all, ten new links: about what 4–5 opens by hand gave. */
        val WAITS_MS = longArrayOf(1_000, 2_000, 3_000, 4_000, 5_000, 6_000, 8_000, 10_000, 10_000, 10_000)
        const val CALM_MS = 120_000L

        /** The player's budget. Used on the main thread only. */
        val shared = LinkRetry()

        private val httpStatus = Regex("""^\s*HTTP (\d{3})\b""")

        /** The HTTP status at the start of a player error ("HTTP 404 Not Found [...]"), if any. */
        fun httpStatusOf(message: String?): Int? =
            message?.let { httpStatus.find(it)?.groupValues?.get(1)?.toIntOrNull() }

        /**
         * Nuvio RS: a player error from the video or audio decoder ("... [ERROR_CODE_DECODING_FAILED]",
         * such as a Dolby Vision or 8K file the device cannot decode). It belongs to the device, not
         * to the link: a new link gets the same error, and a drive that signs every link anew
         * (PikPak) kept the player asking for new links without end instead of switching engine.
         */
        fun isDecoderError(message: String?): Boolean =
            message != null && DECODER_ERROR_CODES.any { it in message }

        private val DECODER_ERROR_CODES = listOf("[ERROR_CODE_DECODER_", "[ERROR_CODE_DECODING_")
    }
}
