package com.nuvio.app.features.servers.webdav

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.createApiHttpClient
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.servers.ServerSession
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.Clock

/**
 * Nuvio RS: poster/details edits shared through the drive itself, so the phone and TV apps see
 * each other's edits with no account in between. Both read and write `.nuvio-rs/info.json` at the
 * drive's root (path → entry; the newer edit wins). A picture picked from the gallery is uploaded
 * to `.nuvio-rs/posters/` and named "webdav-file:<path>"; each app downloads it once.
 */
internal object WebDavDriveSync {
    const val FOLDER_NAME = ".nuvio-rs"
    const val DRIVE_POSTER_SCHEME = "webdav-file:"
    private const val PUSH_DELAY_MS = 1_500L
    private const val MIN_PULL_INTERVAL_MS = 20_000L

    private val log = Logger.withTag("NuvioDriveSync")
    private val json = Json { ignoreUnknownKeys = true }
    private val client by lazy { WebDavClient(createApiHttpClient()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    /** Picture downloads have their own lock, so a slow one never holds up a folder opening. */
    private val pictureMutex = Mutex()
    private val lock = SynchronizedObject()
    private val pushJobs = mutableMapOf<String, Job>()
    private val lastPullAtMs = mutableMapOf<String, Long>()

    /** The shared folder, kept out of the drive listing. */
    fun isSyncFolder(entry: WebDavEntry): Boolean = entry.isFolder && entry.name.trimEnd('/') == FOLDER_NAME

    /** Takes the newer edits from the drive, at most every few seconds unless [force]d. */
    suspend fun pull(session: ServerSession, force: Boolean = false) {
        val connectionId = session.connection.id
        val now = nowMs()
        val due = synchronized(lock) {
            val last = lastPullAtMs[connectionId] ?: 0L
            (force || now - last >= MIN_PULL_INTERVAL_MS).also { if (it) lastPullAtMs[connectionId] = now }
        }
        if (!due) return
        mutex.withLock {
            try {
                readShared(session)?.let { WebDavInfoOverrides.mergeDrive(connectionId, it) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                log.w { "Reading the shared info failed: ${error.message}" }
            }
        }
        // Pictures come on their own, so a slow download never holds the listing up (or is cut by it).
        scope.launch { pictureMutex.withLock { cachePictures(session) } }
    }

    /** Sends this connection's edits to the drive shortly after the last one. */
    fun schedulePush(connectionId: String) {
        val job = scope.launch {
            delay(PUSH_DELAY_MS)
            val session = ServerRepository.session(connectionId) ?: return@launch
            if (session.connection.providerId != WebDavProvider.ID) return@launch
            try {
                mutex.withLock { push(session) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                log.w { "Saving the shared info failed: ${error.message}" }
            }
        }
        synchronized(lock) { pushJobs.put(connectionId, job) }?.cancel()
    }

    private suspend fun push(session: ServerSession) {
        val connectionId = session.connection.id
        uploadPictures(session)
        val remote = readShared(session) ?: JsonObject(emptyMap())
        // Take what the other app saved meanwhile, then add whatever is newer here.
        WebDavInfoOverrides.mergeDrive(connectionId, remote)
        val merged = remote.toMutableMap()
        WebDavInfoOverrides.entriesFor(connectionId).forEach { (path, entry) ->
            val there = (remote[path] as? JsonObject)?.get("updatedAt")?.jsonPrimitive?.longOrNull ?: -1L
            if (entry.updatedAt > there) merged[path] = WebDavInfoOverrides.encodeShared(entry)
        }
        val body = buildJsonObject {
            put("version", 1)
            put("entries", JsonObject(merged))
        }
        client.mkcol(session.connection.address, folder(session), session.token)
        client.put(session.connection.address, infoPath(session), session.token, body.toString().encodeToByteArray())
    }

    /** The shared entries (path → entry); null when the drive has none yet. */
    private suspend fun readShared(session: ServerSession): JsonObject? {
        val bytes = client.download(session.connection.address, infoPath(session), session.token) ?: return null
        val root = runCatching { json.parseToJsonElement(bytes.decodeToString()).jsonObject }.getOrNull() ?: return null
        return root["entries"] as? JsonObject
    }

    /** Uploads gallery pictures that have no drive copy yet, so the TV can show them too. */
    private suspend fun uploadPictures(session: ServerSession) {
        val connectionId = session.connection.id
        WebDavInfoOverrides.entriesFor(connectionId).forEach { (path, entry) ->
            val local = entry.poster?.takeIf { it.startsWith("file:") && entry.drivePoster == null } ?: return@forEach
            val bytes = WebDavPosterFiles.read(local) ?: return@forEach
            val name = "p_${nowMs()}_${bytes.contentHashCode().toUInt().toString(16)}.img"
            val drivePath = folder(session) + "posters/" + name
            client.mkcol(session.connection.address, folder(session), session.token)
            client.mkcol(session.connection.address, folder(session) + "posters/", session.token)
            client.put(session.connection.address, drivePath, session.token, bytes)
            // Recorded quietly: the entry's time stays, so the upload alone doesn't win over newer edits.
            WebDavInfoOverrides.replaceQuietly(connectionId, path, entry.copy(drivePoster = DRIVE_POSTER_SCHEME + drivePath))
        }
    }

    /** Downloads drive pictures named in the entries, so the tiles show them from this phone. */
    private suspend fun cachePictures(session: ServerSession) {
        val connectionId = session.connection.id
        WebDavInfoOverrides.entriesFor(connectionId).forEach { (path, entry) ->
            val drivePoster = entry.poster?.takeIf { it.startsWith(DRIVE_POSTER_SCHEME) } ?: return@forEach
            val drivePath = drivePoster.removePrefix(DRIVE_POSTER_SCHEME)
            try {
                val name = "d_" + drivePath.substringAfterLast('/').filter { it.isLetterOrDigit() || it in "._-" }
                val local = WebDavPosterFiles.existing(name)
                    ?: client.download(session.connection.address, drivePath, session.token)?.let { WebDavPosterFiles.write(name, it) }
                    ?: return@forEach
                WebDavInfoOverrides.replaceQuietly(connectionId, path, entry.copy(poster = local, drivePoster = drivePoster))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                log.w { "Fetching a drive picture failed: ${error.message}" }
            }
        }
    }

    private fun folder(session: ServerSession): String =
        WebDavClient.rootPath(session.connection.address).trimEnd('/') + "/" + FOLDER_NAME + "/"

    private fun infoPath(session: ServerSession): String = folder(session) + "info.json"

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
}
