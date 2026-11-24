package com.nuvio.app.features.servers.webdav

internal actual object WebDavPosterFiles {
    actual fun read(uri: String): ByteArray? = null

    actual fun existing(name: String): String? = null

    actual fun write(name: String, bytes: ByteArray): String? = null
}
