package com.nuvio.app.core.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.features.player.PlayerMediaInfo
import com.nuvio.app.features.player.audiosync.SyncLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * Nuvio RS: records why the app closed, on this device only, so a film that makes it crash can be
 * reported from Settings › Advanced › Share crash log. Java crashes are written as they happen;
 * native crashes, freezes (ANR) and the system closing the app while it was on screen are read
 * from Android's own exit records (Android 11+) at the next start. Every record carries the
 * formats that were playing; links are cut to their host.
 */
internal object CrashRecorder {
    private const val MAX_LOG_BYTES = 64 * 1024
    private const val MAX_TRACE_LINES = 120
    private const val MAX_TRACE_BYTES = 512 * 1024

    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NuvioCrashLog").apply { isDaemon = true }
    }

    @Volatile private var dir: File? = null
    @Volatile private var playing: String? = null
    @Volatile private var currentFile: String? = null

    private val logFile get() = dir?.let { File(it, "crash-log.txt") }
    private val playingFile get() = dir?.let { File(it, "playing.txt") }

    /** Call first thing in Application.onCreate. */
    fun install(context: Context) {
        if (dir != null) return
        val app = context.applicationContext
        dir = File(app.filesDir, "crash-log").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { append(javaCrashRecord(thread, error)) }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
        // Queued first, so the note of the last run is read before a new film replaces it.
        io.execute { runCatching { recordSystemExits(app) } }
        CrashReports.actions = object : CrashReportActions {
            override fun hasReports(): Boolean = (logFile?.length() ?: 0L) > 0L

            override fun share() {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Nuvio crash log")
                    putExtra(Intent.EXTRA_TEXT, text())
                }
                val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { app.startActivity(chooser) }
            }
        }
    }

    /** A new file is being opened; [source] gives only the file name. */
    fun noteOpening(engine: String, source: String?) {
        currentFile = source?.let(::fileName)
        notePlayback(engine, "opening a file")
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun noteExoPlayback(video: Format?, audio: Format?) {
        val parts = listOfNotNull(video?.let { describe(it, isVideo = true) }, audio?.let { describe(it, isVideo = false) })
        if (parts.isNotEmpty()) notePlayback("ExoPlayer", parts.joinToString(" · "))
    }

    fun noteMpvPlayback(info: PlayerMediaInfo) {
        val video = listOfNotNull(
            info.videoCodec,
            info.videoWidth?.let { "${it}x${info.videoHeight ?: 0}" },
            info.bitDepth?.let { "$it-bit" },
            info.hdr.takeIf { it.isNotEmpty() }?.joinToString("/"),
            info.videoFrameRate?.let { String.format(Locale.US, "%.3ffps", it) },
        ).joinToString(" ")
        val audio = listOfNotNull(info.audioCodec, info.audioChannels?.let { "${it}ch" }).joinToString(" ")
        val text = listOf(video, audio).filter { it.isNotBlank() }.joinToString(" · ")
        if (text.isNotBlank()) notePlayback("libmpv", text)
    }

    private fun notePlayback(engine: String, details: String) {
        val note = "${clock()} $engine · ${Diag.scrub(details)}" + (currentFile?.let { " · file $it" } ?: "")
        playing = note
        io.execute { runCatching { playingFile?.writeText(note) } }
    }

    private fun text(): String {
        val header = "Nuvio crash log · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.joinToString(",")} · " +
            "app ${AppVersionConfig.VERSION_NAME} (${AppVersionConfig.VERSION_CODE})"
        val body = runCatching { logFile?.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()
        return header + "\n\n" + body
    }

    private fun javaCrashRecord(thread: Thread, error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            .lineSequence().take(MAX_TRACE_LINES).joinToString("\n")
        val recent = runCatching { SyncLog.text().lines().drop(1).takeLast(25) }.getOrDefault(emptyList())
        return buildString {
            append("=== ").append(stamp(System.currentTimeMillis())).append(" · Java crash · thread \"")
                .append(thread.name).append("\" · app ").append(AppVersionConfig.VERSION_NAME).append('\n')
            playing?.let { append("Last playback: ").append(it).append('\n') }
            append(Diag.scrub(trace)).append('\n')
            if (recent.isNotEmpty()) append("Recent log:\n").append(recent.joinToString("\n")).append('\n')
        }
    }

    private fun recordSystemExits(context: Context) {
        val lastNote = runCatching { playingFile?.takeIf { it.exists() }?.readText() }.getOrNull()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) recordSystemExitsR(context, lastNote)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun recordSystemExitsR(context: Context, lastNote: String?) {
        val folder = dir ?: return
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val seenFile = File(folder, "seen.txt")
        val sinceFile = File(folder, "since.txt")
        val seen = runCatching { seenFile.readText().trim().toLong() }.getOrDefault(0L)
        val since = runCatching { sinceFile.readText().trim().toLong() }.getOrNull()
            ?: System.currentTimeMillis().also { sinceFile.writeText(it.toString()) }
        val exits = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 16)
            .filter { it.timestamp > seen }
            .sortedBy { it.timestamp }
        if (exits.isEmpty()) return
        val lastExit = exits.last()
        for (info in exits) {
            val kind = exitKind(info, since) ?: continue
            append(buildString {
                append("=== ").append(stamp(info.timestamp)).append(" · ").append(kind)
                    .append(" · process ").append(info.processName).append('\n')
                if (info === lastExit && !lastNote.isNullOrBlank()) append("Last playback: ").append(lastNote).append('\n')
                info.description?.takeIf { it.isNotBlank() }?.let { append("Reason: ").append(Diag.scrub(it)).append('\n') }
                append("Status ").append(info.status).append(" · memory pss=").append(info.pss / 1024)
                    .append("MB rss=").append(info.rss / 1024).append("MB\n")
                exitTrace(info)?.let { append(it).append('\n') }
            })
        }
        seenFile.writeText(lastExit.timestamp.toString())
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitKind(info: ApplicationExitInfo, since: Long): String? {
        val onScreen = info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        return when (info.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
            // Later Java crashes are written by the handler with their whole stack.
            ApplicationExitInfo.REASON_CRASH -> if (info.timestamp < since) "Java crash" else null
            ApplicationExitInfo.REASON_ANR -> "Not responding (ANR)"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Start failure"
            ApplicationExitInfo.REASON_LOW_MEMORY -> if (onScreen) "Closed by the system: low memory" else null
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> if (onScreen) "Closed by the system: excessive resource use" else null
            ApplicationExitInfo.REASON_SIGNALED -> if (onScreen) "Closed by signal ${info.status}" else null
            else -> null
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitTrace(info: ApplicationExitInfo): String? {
        val bytes = runCatching { info.traceInputStream?.use { it.readUpTo(MAX_TRACE_BYTES) } }.getOrNull() ?: return null
        return when (info.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> tombstoneSummary(bytes)
            ApplicationExitInfo.REASON_ANR -> anrMainThread(String(bytes, Charsets.UTF_8))
            else -> null
        }
    }

    /** The tombstone is a protobuf; its readable strings name the signal, libraries and functions. */
    internal fun tombstoneSummary(bytes: ByteArray): String? {
        val picked = LinkedHashSet<String>()
        val run = StringBuilder()
        fun flush() {
            // Links go first, so a signed drive link never reaches the shared log.
            val s = Diag.scrub(run.toString().trim())
            run.setLength(0)
            if (s.length < 5) return
            // Field tags around the strings can be printable too ("libmpv.so\""), so names are
            // taken out by their own pattern; a message (abort, assert) is kept whole.
            val found = TOMBSTONE_NAMES.findAll(s).map { it.value }.toList()
            if (found.isNotEmpty()) {
                found.forEach { if (picked.size < 40) picked += it }
            } else if (TOMBSTONE_MESSAGE.containsMatchIn(s) && picked.size < 40) {
                picked += s.trim { !it.isLetterOrDigit() }.take(200)
            }
        }
        for (b in bytes) {
            val c = b.toInt() and 0xff
            if (c in 0x20..0x7e) run.append(c.toChar()) else flush()
        }
        flush()
        return picked.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "Native trace:\n")
    }

    internal fun anrMainThread(text: String): String? {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.startsWith("\"main\"") }
        if (start < 0) return null
        val main = lines.drop(start).takeWhile { it.isNotBlank() }.take(40)
        return main.joinToString("\n", prefix = "Main thread:\n") { Diag.scrub(it) }
    }

    private fun append(record: String) {
        val file = logFile ?: return
        synchronized(this) {
            file.appendText(record + "\n")
            if (file.length() > MAX_LOG_BYTES) {
                val text = file.readText()
                val tail = text.takeLast(MAX_LOG_BYTES / 2)
                val cut = tail.indexOf("=== ").takeIf { it >= 0 } ?: 0
                file.writeText(tail.substring(cut))
            }
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun describe(format: Format, isVideo: Boolean): String = buildString {
        append(format.sampleMimeType ?: format.containerMimeType ?: "?")
        format.codecs?.takeIf { it.isNotBlank() }?.let { append(' ').append(it) }
        if (isVideo) {
            if (format.width > 0 && format.height > 0) append(' ').append(format.width).append('x').append(format.height)
            if (format.frameRate > 0f) append(' ').append(String.format(Locale.US, "%.3ffps", format.frameRate))
            when (format.colorInfo?.colorTransfer) {
                C.COLOR_TRANSFER_ST2084 -> append(" PQ")
                C.COLOR_TRANSFER_HLG -> append(" HLG")
            }
        } else {
            if (format.channelCount > 0) append(' ').append(format.channelCount).append("ch")
            if (format.sampleRate > 0) append(' ').append(format.sampleRate).append("Hz")
        }
        if (format.bitrate > 0) append(' ').append(format.bitrate / 1000).append("kbps")
    }

    private fun fileName(source: String): String? = runCatching {
        Uri.parse(source).lastPathSegment?.trim()?.takeIf { it.isNotBlank() }?.take(100)
    }.getOrNull()

    private fun clock(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun stamp(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    private fun InputStream.readUpTo(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** Signal names and codes, libraries, mangled C++ functions and JNI functions. */
    private val TOMBSTONE_NAMES = Regex(
        """SIG[A-Z]{3,}|(?:SEGV|BUS|ILL|FPE|TRAP)_[A-Z]+|lib[\w+-]*?\.so|_Z\w{4,}|Java_[A-Za-z0-9_]+""",
    )
    private val TOMBSTONE_MESSAGE = Regex("""[Aa]bort|assert|Check failed|FORTIFY""")
}
