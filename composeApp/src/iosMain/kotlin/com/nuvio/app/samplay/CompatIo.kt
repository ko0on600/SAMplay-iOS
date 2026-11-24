package com.nuvio.app.samplay

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleLanguageCode
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.availableLocaleIdentifiers
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.Foundation.localizedStringForLanguageCode
import platform.Foundation.preferredLanguages
import platform.Foundation.timeIntervalSince1970

/** Stand-in for kotlin.text.Charsets on iOS (only UTF-8 is used). */
object Charsets { val UTF_8: String = "UTF-8" }

/** A java.io.File stand-in over NSFileManager, for SAMplay code written against java.io. */
@OptIn(ExperimentalForeignApi::class)
class File(val path: String) {
    // Like java.io.File: a null parent means [child] alone.
    constructor(parent: File?, child: String) : this(if (parent == null) child else parent.path.trimEnd('/') + "/" + child)
    constructor(parent: String?, child: String) : this(if (parent == null) child else parent.trimEnd('/') + "/" + child)

    private val fm get() = NSFileManager.defaultManager
    val name: String get() = path.trimEnd('/').substringAfterLast('/')
    val absolutePath: String get() = path
    val parent: String? get() = path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { null }
    val parentFile: File? get() = parent?.let(::File)
    val nameWithoutExtension: String get() = name.substringBeforeLast('.')
    val extension: String get() = name.substringAfterLast('.', "")
    val isFile: Boolean get() = exists() && !isDirectory
    val isDirectory: Boolean get() {
        val attrs = fm.attributesOfItemAtPath(path, null) ?: return false
        return attrs[NSFileType] == NSFileTypeDirectory
    }

    fun exists(): Boolean = fm.fileExistsAtPath(path)
    fun mkdirs(): Boolean = fm.createDirectoryAtPath(path, true, null, null)
    fun delete(): Boolean = fm.removeItemAtPath(path, null)
    fun deleteRecursively(): Boolean = delete()
    fun canWrite(): Boolean = fm.isWritableFileAtPath(path)
    fun canRead(): Boolean = fm.isReadableFileAtPath(path)
    fun length(): Long = ((fm.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? NSNumber)?.longLongValue) ?: 0L
    fun lastModified(): Long =
        ((fm.attributesOfItemAtPath(path, null)?.get(NSFileModificationDate) as? NSDate)?.timeIntervalSince1970?.times(1000))?.toLong() ?: 0L
    fun setLastModified(ms: Long): Boolean = true
    fun renameTo(dest: File): Boolean {
        if (dest.exists()) dest.delete()
        return fm.moveItemAtPath(path, dest.path, null)
    }
    fun readText(charset: String = Charsets.UTF_8): String = IosFiles.readBytes(path)?.decodeToString() ?: throw IOException("Cannot read $path")
    fun readBytes(): ByteArray = IosFiles.readBytes(path) ?: throw IOException("Cannot read $path")
    fun writeText(text: String, charset: String = Charsets.UTF_8) { if (!IosFiles.writeText(path, text)) throw IOException("Cannot write $path") }
    fun writeBytes(bytes: ByteArray) { if (!IosFiles.writeBytes(path, bytes)) throw IOException("Cannot write $path") }
    fun list(): Array<String>? = if (isDirectory) IosFiles.list(path).toTypedArray() else null
    fun listFiles(): Array<File>? = list()?.map { File(this, it) }?.toTypedArray()
    fun listFiles(filter: (File) -> Boolean): Array<File>? = listFiles()?.filter(filter)?.toTypedArray()
    fun toURI(): String = NSURL.fileURLWithPath(path).absoluteString ?: "file://$path"
    override fun toString(): String = path
    override fun equals(other: Any?): Boolean = other is File && other.path == path
    override fun hashCode(): Int = path.hashCode()
}

/** java.util.Locale stand-in: language names come from NSLocale. */
class Locale private constructor(val tag: String) {
    val language: String get() = tag.substringBefore('-').substringBefore('_').lowercase()

    fun getDisplayLanguage(inLocale: Locale = getDefault()): String {
        val ns = NSLocale(localeIdentifier = inLocale.tag)
        return ns.localizedStringForLanguageCode(language) ?: ""
    }
    fun getDisplayName(inLocale: Locale = getDefault()): String = getDisplayLanguage(inLocale)
    override fun toString(): String = tag

    companion object {
        val ROOT = Locale("en")
        val ENGLISH = Locale("en")
        val US = Locale("en-US")
        fun forLanguageTag(tag: String): Locale = Locale(tag.ifBlank { "und" })
        fun getDefault(): Locale {
            val preferred = NSLocale.preferredLanguages.firstOrNull() as? String
            return Locale(preferred ?: NSLocale.currentLocale.localeIdentifier)
        }
        fun getAvailableLocales(): Array<Locale> =
            NSLocale.availableLocaleIdentifiers.mapNotNull { (it as? String)?.let(::Locale) }.toTypedArray()
    }
}

/** Two-digit style zero padding (String.format("%02d") is JVM-only). */
internal fun Long.pad(width: Int): String = toString().padStart(width, '0')
internal fun Int.pad(width: Int): String = toString().padStart(width, '0')
