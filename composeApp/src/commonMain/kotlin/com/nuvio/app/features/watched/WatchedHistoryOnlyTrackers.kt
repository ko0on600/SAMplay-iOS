package com.nuvio.app.features.watched

import co.touchlab.kermit.Logger
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingHistoryItem
import com.nuvio.app.features.tracking.TrackingHistoryWriter
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import kotlinx.coroutines.CancellationException

private val log = Logger.withTag("WatchedHistoryOnly")

/**
 * Nuvio RS: trackers that take writes but are no watched source (Bingers) get watched marks made
 * by hand as history entries. Trackers that are also watched sources (Trakt, Simkl, MDBList) are
 * reached through their watched adapters, as before; playback reaches all of them as scrobbles.
 */
internal fun historyOnlyTrackers(): List<TrackingHistoryWriter> =
    TrackingProviderRegistry.connectedHistoryWriters().filter { writer ->
        val capabilities = TrackingProviderRegistry.authProvider(writer.providerId)?.descriptor?.capabilities
        capabilities != null && TrackingCapability.WATCHED_READ !in capabilities
    }

internal suspend fun pushWatchedToHistoryOnlyTrackers(profileId: Int, items: Collection<WatchedItem>) {
    if (items.isEmpty()) return
    val writers = historyOnlyTrackers()
    if (writers.isEmpty()) return
    val history = items.map { item -> TrackingHistoryItem(item.trackingReference(), item.markedAtEpochMs) }
    writers.forEach { writer ->
        try {
            val result = writer.addToHistory(profileId, history)
            if (!result.isComplete) {
                log.w { "${writer.providerId.storageId} could not match ${result.notFoundCount} of ${history.size} watched items" }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.e(error) { "Failed to add watched items to ${writer.providerId.storageId}" }
        }
    }
}

internal suspend fun deleteWatchedFromHistoryOnlyTrackers(profileId: Int, items: Collection<WatchedItem>) {
    if (items.isEmpty()) return
    val writers = historyOnlyTrackers()
    if (writers.isEmpty()) return
    val references = items.map { item -> item.trackingReference() }
    writers.forEach { writer ->
        try {
            writer.removeFromHistory(profileId, references)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.e(error) { "Failed to remove watched items from ${writer.providerId.storageId}" }
        }
    }
}

private fun WatchedItem.trackingReference(): TrackingMediaReference = buildTrackingMediaReference(
    contentType = type,
    parentMetaId = id,
    videoId = videoId,
    title = name,
    releaseInfo = releaseInfo,
    seasonNumber = season,
    episodeNumber = episode,
)
