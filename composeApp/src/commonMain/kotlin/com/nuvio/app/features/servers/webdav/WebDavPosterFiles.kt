package com.nuvio.app.features.servers.webdav

/** The phone's own copies of poster pictures (picked from the gallery or fetched from the drive). */
internal expect object WebDavPosterFiles {
    /** The bytes of a picture this app keeps ("file:…"), or null. */
    fun read(uri: String): ByteArray?

    /** The kept copy of a drive picture named [name], or null when there is none yet. */
    fun existing(name: String): String?

    /** Keeps [bytes] as the drive picture [name]; its "file:…" link, or null. */
    fun write(name: String, bytes: ByteArray): String?
}
