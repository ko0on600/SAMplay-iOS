package com.nuvio.app.features.servers

import com.nuvio.app.features.player.PlayerPlaybackNetworking

internal actual suspend fun warmPlaybackConnections(url: String, headers: Map<String, String>) {
    PlayerPlaybackNetworking.warmConnections(url, headers)
}

internal actual suspend fun warmFirstBytes(url: String, headers: Map<String, String>) {
    PlayerPlaybackNetworking.warmFirstBytes(url, headers)
}
