package com.nuvio.app.samplay

/**
 * SAMplay iOS: tiny stand-ins for android.content.Context, SharedPreferences and android.util.Log,
 * so SAMplay's Android-written logic (AI subtitles, sync) runs on iOS with only its imports changed.
 */
open class Context {
    open val applicationContext: Context get() = this
    open val filesDir: File get() = File(IosFiles.supportDir)
    open val cacheDir: File get() = File(IosFiles.supportDir + "/cache").also { it.mkdirs() }
    fun getExternalFilesDir(type: String?): File? = File(IosFiles.documentsDir + (type?.let { "/$it" } ?: "")).also { it.mkdirs() }
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = SharedPreferences(name)

    companion object {
        const val MODE_PRIVATE = 0
    }
}

/** The app as a Context. */
object IosContext : Context()

class SharedPreferences(name: String) {
    private val store = IosPrefs(name)
    fun contains(key: String): Boolean = store.contains(key)
    fun getString(key: String, fallback: String?): String? = store.getString(key, fallback)
    fun getBoolean(key: String, fallback: Boolean): Boolean = store.getBoolean(key, fallback)
    fun getLong(key: String, fallback: Long): Long = store.getLong(key, fallback)
    fun getInt(key: String, fallback: Int): Int = store.getInt(key, fallback)
    fun getFloat(key: String, fallback: Float): Float = store.getString(key)?.toFloatOrNull() ?: fallback
    fun edit(): Editor = Editor(store)

    class Editor internal constructor(private val store: IosPrefs) {
        fun putString(key: String, value: String?): Editor { store.putString(key, value); return this }
        fun putBoolean(key: String, value: Boolean): Editor { store.putBoolean(key, value); return this }
        fun putLong(key: String, value: Long): Editor { store.putLong(key, value); return this }
        fun putInt(key: String, value: Int): Editor { store.putInt(key, value); return this }
        fun putFloat(key: String, value: Float): Editor { store.putString(key, value.toString()); return this }
        fun remove(key: String): Editor { store.remove(key); return this }
        fun apply() = Unit
        fun commit(): Boolean = true
    }
}

object Log {
    fun d(tag: String, msg: String, e: Throwable? = null): Int { samplayLog(tag, msg, e); return 0 }
    fun i(tag: String, msg: String, e: Throwable? = null): Int { samplayLog(tag, msg, e); return 0 }
    fun w(tag: String, msg: String, e: Throwable? = null): Int { samplayLog(tag, msg, e); return 0 }
    fun w(tag: String, e: Throwable): Int { samplayLog(tag, "", e); return 0 }
    fun e(tag: String, msg: String, e: Throwable? = null): Int { samplayLog(tag, msg, e); return 0 }
    fun v(tag: String, msg: String): Int = 0
}
