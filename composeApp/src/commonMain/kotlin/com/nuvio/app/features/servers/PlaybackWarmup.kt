package com.nuvio.app.features.servers

/** Opens connections the player can use for [url]; platforms without a shared pool do nothing. */
internal expect suspend fun warmPlaybackConnections(url: String, headers: Map<String, String>)

/**
 * Asks for the first bytes of [url] until the server sends them, so a file the server has to fetch
 * first is ready by the time it is picked; platforms that cannot do this do nothing.
 */
internal expect suspend fun warmFirstBytes(url: String, headers: Map<String, String>)
