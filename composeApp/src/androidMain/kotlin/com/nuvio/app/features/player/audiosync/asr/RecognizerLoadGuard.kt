package com.nuvio.app.features.player.audiosync.asr

import android.content.Context
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat

/**
 * Nuvio RS: the recogniser's native library takes the whole app down if it fails while loading, as
 * it does on an x86 device translating ARM libraries, and it would again at every play. Such a
 * device never loads it (nor downloads the model for it), and a device where the app dies during
 * the load stops trying after [MAX_STRIKES], until the next update. Sync by speech detection goes on.
 */
internal object RecognizerLoadGuard {
    private const val PREFS = "nuvio_asr_load"
    private const val KEY_BUILD = "build"
    private const val KEY_STRIKES = "strikes"

    // Two, since the system or the user can also end the app during a load.
    private const val MAX_STRIKES = 2

    fun canLoad(context: Context): Boolean {
        if (!runsArmNatively(Build.SUPPORTED_ABIS.firstOrNull())) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_BUILD, -1L) != build(context) || prefs.getInt(KEY_STRIKES, 0) < MAX_STRIKES
    }

    /** Runs [load], counting it against this build unless it returns. */
    fun <T> loading(context: Context, load: () -> T): T {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val build = build(context)
        val strikes = if (prefs.getLong(KEY_BUILD, -1L) == build) prefs.getInt(KEY_STRIKES, 0) else 0
        // commit: a crash in the load leaves no time for a later write.
        prefs.edit().putLong(KEY_BUILD, build).putInt(KEY_STRIKES, strikes + 1).commit()
        return load().also { prefs.edit().remove(KEY_STRIKES).apply() }
    }

    internal fun runsArmNatively(primaryAbi: String?): Boolean = primaryAbi?.startsWith("arm") == true

    private fun build(context: Context): Long = PackageInfoCompat.getLongVersionCode(
        context.packageManager.getPackageInfo(context.packageName, 0),
    )
}
