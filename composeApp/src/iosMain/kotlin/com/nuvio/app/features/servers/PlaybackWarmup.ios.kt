package com.nuvio.app.features.servers

internal actual suspend fun warmPlaybackConnections(url: String, headers: Map<String, String>) = Unit

internal actual suspend fun warmFirstBytes(url: String, headers: Map<String, String>) = Unit
