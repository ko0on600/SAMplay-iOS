package com.nuvio.app.features.servers.webdav

import com.nuvio.app.samplay.IosFiles
import platform.Foundation.NSURL

/** SAMplay iOS: posters kept in the app's own files (Application Support), as on Android. */
internal actual object WebDavPosterFiles {
    private const val MAX_BYTES = 15 * 1024 * 1024

    private fun root(): String = IosFiles.supportDir

    private fun folder(): String = root() + "/webdav_drive_posters"

    private fun pathOf(uri: String): String? =
        if (uri.startsWith("file:", ignoreCase = true)) NSURL.URLWithString(uri)?.path else uri.takeIf { it.startsWith("/") }

    actual fun read(uri: String): ByteArray? = runCatching {
        val path = pathOf(uri) ?: return null
        // Only pictures this app keeps itself.
        val standard = (NSURL.fileURLWithPath(path).URLByStandardizingPath?.path ?: path)
        if (!standard.startsWith(root()) || standard.contains("/../")) return null
        IosFiles.readBytes(standard)?.takeIf { it.size in 1..MAX_BYTES }
    }.getOrNull()

    actual fun existing(name: String): String? =
        (folder() + "/" + name).takeIf { IosFiles.exists(it) }?.let { NSURL.fileURLWithPath(it).absoluteString }

    actual fun write(name: String, bytes: ByteArray): String? = runCatching {
        val path = folder() + "/" + name.replace('/', '_')
        if (!IosFiles.writeBytes(path, bytes)) return null
        NSURL.fileURLWithPath(path).absoluteString
    }.getOrNull()
}
