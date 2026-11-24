package com.nuvio.app.samplay

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithBytes
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.Foundation.temporaryDirectory
import platform.posix.memcpy

/** SAMplay iOS: small platform helpers for the parts of SAMplay that were Android-only. */
internal fun nowMs(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

/** Monotonic milliseconds (like Android's SystemClock.elapsedRealtime). */
internal fun elapsedMs(): Long = (NSProcessInfo.processInfo.systemUptime * 1000.0).toLong()

internal fun samplayLog(tag: String, message: String, error: Throwable? = null) {
    println("[$tag] $message" + (error?.let { " (${it::class.simpleName}: ${it.message})" } ?: ""))
}

/** A SharedPreferences-like store over NSUserDefaults; every key is prefixed with the store name. */
internal class IosPrefs(private val name: String) {
    private val defaults get() = NSUserDefaults.standardUserDefaults
    private fun k(key: String) = "samplay.$name.$key"
    fun contains(key: String): Boolean = defaults.objectForKey(k(key)) != null
    fun getString(key: String, fallback: String? = null): String? = defaults.stringForKey(k(key)) ?: fallback
    fun getBoolean(key: String, fallback: Boolean): Boolean = if (contains(key)) defaults.boolForKey(k(key)) else fallback
    fun getLong(key: String, fallback: Long): Long =
        if (contains(key)) (defaults.stringForKey(k(key))?.toLongOrNull() ?: defaults.integerForKey(k(key))) else fallback
    fun getInt(key: String, fallback: Int): Int = getLong(key, fallback.toLong()).toInt()
    fun putString(key: String, value: String?) { if (value == null) remove(key) else defaults.setObject(value, k(key)) }
    fun putBoolean(key: String, value: Boolean) = defaults.setBool(value, k(key))
    fun putLong(key: String, value: Long) = defaults.setObject(value.toString(), k(key))
    fun putInt(key: String, value: Int) = putLong(key, value.toLong())
    fun remove(key: String) = defaults.removeObjectForKey(k(key))
}

/** Files under the app's private Application Support folder (or Documents, which the Files app shows). */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosFiles {
    private fun dir(kind: ULong): String {
        val url = NSFileManager.defaultManager.URLsForDirectory(kind, NSUserDomainMask).firstOrNull() as? NSURL
        return url?.path ?: NSFileManager.defaultManager.temporaryDirectory.path!!
    }
    val supportDir: String get() = dir(NSApplicationSupportDirectory).also { ensureDir(it) }
    val documentsDir: String get() = dir(NSDocumentDirectory).also { ensureDir(it) }
    val cacheDir: String get() = dir(NSCachesDirectory).also { ensureDir(it) }

    fun ensureDir(path: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(path, true, null, null)
    }
    fun exists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)
    fun delete(path: String): Boolean = NSFileManager.defaultManager.removeItemAtPath(path, null)
    fun readText(path: String): String? {
        if (!exists(path)) return null
        return NSString.create(contentsOfFile = path, encoding = NSUTF8StringEncoding, error = null)?.toString()
    }
    fun readBytes(path: String): ByteArray? {
        val data = NSData.create(contentsOfFile = path) ?: return null
        return data.toByteArray()
    }
    /** Writes atomically (a temp file, then a rename). */
    fun writeText(path: String, text: String): Boolean = writeBytes(path, text.encodeToByteArray())
    fun writeBytes(path: String, bytes: ByteArray): Boolean {
        path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let(::ensureDir)
        return bytes.toNSData().writeToFile(path, atomically = true)
    }
    fun list(path: String): List<String> =
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(path, null)?.map { it.toString() }.orEmpty()
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return out
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { pinned -> NSData.dataWithBytes(pinned.addressOf(0), size.toULong()) }

/** One shared HTTP client for SAMplay's own calls (Google, Drive, translation, Pro AI). */
internal val samplayHttp: HttpClient by lazy {
    HttpClient(Darwin) {
        expectSuccess = false
        followRedirects = true
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 120_000
            requestTimeoutMillis = 300_000
        }
    }
}

