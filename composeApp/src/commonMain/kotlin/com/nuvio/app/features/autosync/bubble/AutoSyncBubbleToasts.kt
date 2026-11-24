package com.nuvio.app.features.autosync.bubble

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.EmptyCoroutineContext

/** What an AutoSync message means for the bubble: still working, or how it ended. */
internal enum class AutoSyncBubbleKind { Working, Success, Failure }

/**
 * One AutoSync message as the bubble shows it. Messages of one run share [session], so the
 * bubble that shows "Analyzing…" is the one that turns into the check mark or the red card.
 */
internal data class AutoSyncBubbleMessage(
    val id: Long,
    val session: Long,
    val kind: AutoSyncBubbleKind,
    val headline: String,
    val detail: String?,
)

/**
 * The optional glass bubble that replaces AutoSync's plain toasts. On by default; persistence
 * is installed by the platform, and without it (or without a player on screen to draw it)
 * [post] returns false so the caller shows its plain toast instead.
 */
internal object AutoSyncBubbleToasts {
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _current = MutableStateFlow<AutoSyncBubbleMessage?>(null)
    val current: StateFlow<AutoSyncBubbleMessage?> = _current.asStateFlow()

    private val hosts = MutableStateFlow(0)
    private var save: ((Boolean) -> Unit)? = null
    private var nextId = 0L
    /** The run whose bubble is fading away; a message arriving now starts a fresh bubble. */
    private var leavingSession = -1L

    val isAvailable: Boolean
        get() = save != null

    /**
     * SAMplay: a "working" message ("Analyzing…", "syncing to the audio…") leaves on its own after
     * this long. Results already left after a few seconds, but a working message only went when
     * the next message replaced it, so a run that never reported back (cancelled elsewhere, stuck
     * on the network, the speech model still loading) kept it on the video for good.
     */
    const val WORKING_TIMEOUT_MS = 6_000L

    /** Runs an action after a delay and returns a way to call it off (main thread by default). */
    internal fun interface Scheduler {
        fun schedule(delayMs: Long, action: () -> Unit): () -> Unit
    }

    // Nuvio RS: SAMplay used an Android Handler; shared code runs the wait on the main dispatcher.
    // Without one (plain JVM tests) the message simply stays until the next one replaces it.
    private val mainScheduler = Scheduler { delayMs, action ->
        if (!mainDispatcherAvailable()) return@Scheduler {}
        val job = runCatching {
            timeoutScope.launch {
                delay(delayMs)
                action()
            }
        }.getOrNull()
        ({ job?.cancel() })
    }

    /** Checked on every use: a test may install or remove its own main dispatcher in between. */
    private fun mainDispatcherAvailable(): Boolean =
        runCatching { Dispatchers.Main.isDispatchNeeded(EmptyCoroutineContext) }.isSuccess
    private val timeoutScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }

    @Volatile internal var scheduler: Scheduler = mainScheduler
    internal fun resetSchedulerForTest() { scheduler = mainScheduler }
    private var cancelWorkingTimeout: (() -> Unit)? = null

    // Nuvio RS: a lock that works on every platform (@Synchronized is JVM-only).
    private val timeoutLock = kotlinx.atomicfu.locks.SynchronizedObject()

    private fun scheduleWorkingTimeout(message: AutoSyncBubbleMessage?) {
        kotlinx.atomicfu.locks.synchronized(timeoutLock) {
            cancelWorkingTimeout?.invoke()
            cancelWorkingTimeout = null
            if (message == null || message.kind != AutoSyncBubbleKind.Working) return
            val id = message.id
            cancelWorkingTimeout = scheduler.schedule(WORKING_TIMEOUT_MS) {
                _current.update { if (it?.id == id && it.kind == AutoSyncBubbleKind.Working) null else it }
            }
        }
    }

    fun installPersistence(load: () -> Boolean, save: (Boolean) -> Unit) {
        _enabled.value = load()
        this.save = save
    }

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        save?.invoke(enabled)
        if (!enabled) clear()
    }

    /** Takes whatever the bubble shows away now (player closed, another video). */
    fun clear() {
        _current.value = null
        scheduleWorkingTimeout(null)
    }

    /** Shows [text] (an "Auto Sync • headline • detail" toast string) in the bubble, if it can. */
    fun post(kind: AutoSyncBubbleKind, text: String): Boolean {
        if (!_enabled.value || hosts.value == 0) return false
        val (headline, detail) = split(kind, text)
        _current.update { previous ->
            val id = ++nextId
            // A run continues while the bubble is still working; anything else (a result, or a
            // bubble already fading away) starts a new bubble.
            val session = if (
                previous != null && previous.kind == AutoSyncBubbleKind.Working &&
                previous.session != leavingSession
            ) previous.session else id
            AutoSyncBubbleMessage(id, session, kind, headline, detail)
        }
        scheduleWorkingTimeout(_current.value)
        return true
    }

    /** The run was cancelled: nothing will replace its "working" bubble, so take it away. */
    fun dismissWorking() {
        _current.update { if (it?.kind == AutoSyncBubbleKind.Working) null else it }
        if (_current.value == null) scheduleWorkingTimeout(null)
    }

    /** Called by the bubble as it starts fading [session] away, so later messages do not join it. */
    fun leaving(session: Long) {
        leavingSession = session
    }

    /** Called by the bubble once it has finished animating [id] away. */
    fun finished(id: Long) {
        _current.update { if (it?.id == id) null else it }
    }

    fun attachHost() {
        hosts.update { it + 1 }
    }

    fun detachHost() {
        hosts.update { it - 1 }
        if (hosts.value <= 0) clear()
    }

    /**
     * AutoSync's toast strings read "Auto Sync • headline • detail" in every language. The bubble
     * already says it is AutoSync, so it drops that part. While working it shows only what is
     * happening now ("syncing to the audio instead…"); a result keeps the rest as its explanation.
     */
    internal fun split(kind: AutoSyncBubbleKind, text: String): Pair<String, String?> {
        val parts = text.split(" • ").map { it.trim() }.filter { it.isNotEmpty() }
        val body = if (parts.size > 1) parts.drop(1) else parts.ifEmpty { listOf(text) }
        return when (kind) {
            AutoSyncBubbleKind.Working -> body.last().capitalized() to null
            else -> body.first().capitalized() to
                body.drop(1).joinToString(" · ").takeIf { it.isNotEmpty() }?.capitalized()
        }
    }

    private fun String.capitalized(): String = replaceFirstChar { it.uppercaseChar() }
}
