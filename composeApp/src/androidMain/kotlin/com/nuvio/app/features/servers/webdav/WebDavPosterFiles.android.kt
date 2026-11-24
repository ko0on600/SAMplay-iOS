package com.nuvio.app.features.servers.webdav

import android.content.Context
import android.net.Uri
import java.io.File

internal actual object WebDavPosterFiles {
    private const val MAX_BYTES = 15 * 1024 * 1024

    @Volatile
    private var filesDir: File? = null

    fun initialize(context: Context) {
        filesDir = context.applicationContext.filesDir
    }

    actual fun read(uri: String): ByteArray? = runCatching {
        val path = Uri.parse(uri).path ?: return null
        val file = File(path)
        // Only pictures this app keeps itself.
        val root = filesDir?.canonicalPath ?: return null
        if (!file.canonicalPath.startsWith(root)) return null
        file.takeIf { it.isFile && it.length() in 1..MAX_BYTES }?.readBytes()
    }.getOrNull()

    actual fun existing(name: String): String? =
        folder()?.let { File(it, name) }?.takeIf { it.isFile }?.let { Uri.fromFile(it).toString() }

    actual fun write(name: String, bytes: ByteArray): String? = runCatching {
        val dir = folder() ?: return null
        dir.mkdirs()
        val file = File(dir, name)
        file.writeBytes(bytes)
        Uri.fromFile(file).toString()
    }.getOrNull()

    private fun folder(): File? = filesDir?.let { File(it, "webdav_drive_posters") }
}
