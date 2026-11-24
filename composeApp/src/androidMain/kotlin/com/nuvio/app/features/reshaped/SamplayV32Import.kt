package com.nuvio.app.features.reshaped

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.nuvio.app.features.servers.ServerProviders
import com.nuvio.app.features.servers.ServerRepository
import com.nuvio.app.features.servers.webdav.WebDavProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SAMplay 2.0 installs over SAMplay v32 (same package and key), but 2.0 keeps its data under
 * Nuvio's names, so without this the viewer would lose v32's add-ons, Pro AI keys, Google sync
 * sign-in, TMDB key, "My library" and drives. Runs once:
 * - [importLocal], before anything reads storage: copies the local settings; values 2.0
 *   already has are never replaced.
 * - [connectDrivesLater], once the server storage is ready: signs in to each v32 drive in the
 *   background (it needs the network), trying again on later launches if it could not.
 * v32's own files stay as they were.
 */
internal object SamplayV32Import {
    private const val TAG = "SamplayV32Import"
    private const val STATE = "samplay2_v32_import"
    private const val KEY_LOCAL_DONE = "local_done"
    private const val KEY_DRIVES_DONE = "drives_done"
    private const val PROFILE = 1
    private const val DRIVE_START_DELAY_MS = 6_000L
    private const val DRIVE_TIMEOUT_MS = 45_000L
    private const val MAX_DRIVE_TRIES = 5
    private const val CINEMETA_URL = "https://v3-cinemeta.strem.io/manifest.json"

    private val drivesStarted = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun importLocal(context: Context) {
        runCatching {
            val state = context.prefs(STATE)
            if (state.getBoolean(KEY_LOCAL_DONE, false)) return
            val copied = mutableListOf<String>()
            if (importAddons(context)) copied += "add-ons" else if (seedCinemeta(context)) copied += "Cinemeta"
            if (importAi(context)) copied += "AI"
            if (importGoogleAccount(context)) copied += "Google"
            if (importTmdbKey(context)) copied += "TMDB"
            if (importLibrary(context)) copied += "library"
            state.edit().putBoolean(KEY_LOCAL_DONE, true).commit()
            if (copied.isNotEmpty()) Log.i(TAG, "Imported from SAMplay v32: ${copied.joinToString()}")
        }.onFailure { Log.w(TAG, "SAMplay v32 import failed", it) }
    }

    private fun importAddons(context: Context): Boolean {
        val v32 = context.prefs("sam_addons")
        val urls = SamplayV32Data.parseAddonUrls(v32.getString("urls", null))
        if (urls.isEmpty()) return false
        val enabled = SamplayV32Data.parseAddonEnabled(v32.getString("enabled", null))
        val target = context.prefs("nuvio_addons")
        val urlsKey = "installed_manifest_urls_$PROFILE"
        val enabledKey = "installed_manifest_enabled_states_$PROFILE"
        val mergedUrls = SamplayV32Data.mergeAddonUrls(target.getString(urlsKey, null), urls)
        val mergedEnabled = SamplayV32Data.mergeAddonEnabled(target.getString(enabledKey, null), urls, enabled)
        if (mergedUrls == null && mergedEnabled == null) return false
        target.edit().apply {
            mergedUrls?.let { putString(urlsKey, it) }
            mergedEnabled?.let { putString(enabledKey, it) }
        }.commit()
        return true
    }

    /**
     * A first install (no v32 add-ons, nothing in 2.0's list yet) starts with Cinemeta, as SAMplay
     * always did, so Home has catalogs and search works before the viewer adds anything.
     */
    private fun seedCinemeta(context: Context): Boolean {
        if (context.prefs("sam_addons").contains("urls")) return false
        val target = context.prefs("nuvio_addons")
        val urlsKey = "installed_manifest_urls_$PROFILE"
        if (target.contains(urlsKey)) return false
        target.edit().putString(urlsKey, CINEMETA_URL).commit()
        return true
    }

    /** Pro AI service, keys and models (same key names), and the translation choices. */
    private fun importAi(context: Context): Boolean {
        val v32 = context.prefs("samplay")
        val target = context.prefs("nuvio_ai_subtitles")
        val edit = target.edit()
        var changed = false
        fun copyString(from: String, to: String = from) {
            val value = runCatching { v32.getString(from, null) }.getOrNull()?.trim()
            if (!value.isNullOrEmpty() && !target.contains(to)) {
                edit.putString(to, value)
                changed = true
            }
        }
        fun copyBoolean(key: String) {
            if (v32.contains(key) && !target.contains(key)) {
                runCatching { v32.getBoolean(key, false) }.getOrNull()?.let {
                    edit.putBoolean(key, it)
                    changed = true
                }
            }
        }
        v32.all.keys.filter { it.startsWith("pro_ai_") }.forEach { copyString(it) }
        copyString("whisper_model")
        copyString("target_lang", "target_language")
        copyBoolean("translate_show_both")
        copyBoolean("save_translations")
        if (changed) edit.commit()
        return changed
    }

    /** v32 and 2.0 sign in with the same Google client, so v32's sign-in keeps working. */
    private fun importGoogleAccount(context: Context): Boolean {
        val v32 = context.prefs("samplay_reshaped_sync_account")
        val refresh = v32.getString("refresh_token", null)?.takeIf(String::isNotBlank) ?: return false
        val target = context.prefs("nuvio_reshaped_sync_account")
        if (!target.getString("refresh_token", null).isNullOrBlank()) return false
        target.edit()
            .putString("refresh_token", refresh)
            .apply { v32.getString("email", null)?.let { putString("email", it) } }
            .commit()
        return true
    }

    private fun importTmdbKey(context: Context): Boolean {
        val key = context.prefs("nuvio_plugins").getString("tmdb_api_key", null)?.trim()
        if (key.isNullOrEmpty()) return false
        val target = context.prefs("nuvio_tmdb_settings")
        val targetKey = "tmdb_api_key_$PROFILE"
        if (!target.getString(targetKey, null).isNullOrBlank()) return false
        target.edit().putString(targetKey, key).commit()
        return true
    }

    private fun importLibrary(context: Context): Boolean {
        val items = SamplayV32Data.parseLibrary(
            context.prefs("my_library").getString("items", null),
            nowEpochMs = System.currentTimeMillis(),
        )
        if (items.isEmpty()) return false
        val target = context.prefs("nuvio_library")
        val key = "library_payload_$PROFILE"
        val merged = SamplayV32Data.mergeLibraryPayload(target.getString(key, null), items) ?: return false
        target.edit().putString(key, merged).commit()
        return true
    }

    /** Call once the server storage is initialized. Returns at once; the work runs later. */
    fun connectDrivesLater(context: Context) {
        val app = context.applicationContext
        val state = app.prefs(STATE)
        if (state.getBoolean(KEY_DRIVES_DONE, false)) return
        val drives = runCatching {
            SamplayV32Data.parseDrives(app.prefs("sam_webdav").getString("accounts", null))
        }.getOrDefault(emptyList())
        if (drives.isEmpty()) {
            state.edit().putBoolean(KEY_DRIVES_DONE, true).apply()
            return
        }
        if (!drivesStarted.compareAndSet(false, true)) return
        scope.launch {
            delay(DRIVE_START_DELAY_MS)
            val provider = ServerProviders.forId(WebDavProvider.ID) ?: return@launch
            var waiting = 0
            for (drive in drives) {
                val doneKey = "drive_done:${drive.key}"
                val triesKey = "drive_tries:${drive.key}"
                if (state.getBoolean(doneKey, false)) continue
                val tries = state.getInt(triesKey, 0)
                if (tries >= MAX_DRIVE_TRIES) continue
                try {
                    withTimeout(DRIVE_TIMEOUT_MS) {
                        ServerRepository.connect(provider, drive.baseUrl, drive.username, drive.password)
                    }
                    state.edit().putBoolean(doneKey, true).apply()
                    Log.i(TAG, "Connected the v32 drive ${drive.label.ifBlank { "(no name)" }}")
                } catch (error: Throwable) {
                    if (!isActive) throw error
                    state.edit().putInt(triesKey, tries + 1).apply()
                    if (tries + 1 < MAX_DRIVE_TRIES) waiting++
                    Log.w(TAG, "Could not connect a v32 drive yet (try ${tries + 1}): ${error.javaClass.simpleName}")
                }
            }
            if (waiting == 0) state.edit().putBoolean(KEY_DRIVES_DONE, true).apply()
        }
    }

    private fun Context.prefs(name: String): SharedPreferences = getSharedPreferences(name, Context.MODE_PRIVATE)
}
