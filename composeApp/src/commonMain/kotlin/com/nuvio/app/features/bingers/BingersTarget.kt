package com.nuvio.app.features.bingers

import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What Bingers is told about one movie or episode: a title reference it understands
 * (`imdb:tt…`, `tmdb:movie:<id>` / `tmdb:show:<id>`, `tvdb:<id>`) plus, for an episode, its
 * season and number or its TVDB episode id. Bingers has no IMDb or TMDB episode ids.
 */
internal data class BingersTarget(
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val tvdbEpisode: String? = null,
) {
    val isEpisode: Boolean
        get() = tvdbEpisode != null || episode != null

    fun writeTo(builder: JsonObjectBuilder) {
        builder.put("title", title)
        if (tvdbEpisode != null) {
            builder.put("tvdb_episode", tvdbEpisode)
        } else if (season != null && episode != null) {
            builder.put("season", season)
            builder.put("episode", episode)
        }
    }

    fun toJson(extra: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
        writeTo(this)
        extra()
    }
}

/**
 * Picks how Bingers should find [media], or null when it can't be addressed (no IMDb, TMDB
 * or TVDB id, or an episode with neither a season nor a TVDB episode id, such as absolute
 * anime numbering).
 */
internal fun bingersTarget(media: TrackingMediaReference): BingersTarget? {
    val contentType = media.catalog?.contentType?.trim()?.lowercase()
    val isMovie = media.kind == TrackingMediaKind.MOVIE || contentType == "movie" || contentType == "film"
    val ids = media.ids
    val title = ids.imdb?.trim()?.takeIf { IMDB_ID.matches(it) }?.let { "imdb:${it.lowercase()}" }
        ?: ids.tmdb?.takeIf { it > 0 }?.let { "tmdb:${if (isMovie) "movie" else "show"}:$it" }
        ?: ids.tvdb?.trim()?.takeIf { TVDB_ID.matches(it) }?.let { "tvdb:$it" }
        ?: return null
    val episode = media.episode
    if (isMovie || episode == null) return BingersTarget(title)
    if (episode.number < 0) return null
    val tvdbEpisode = episode.tvdbId?.takeIf { it > 0 }?.toString()
    return when {
        episode.usesTvdbSeasonMapping && tvdbEpisode != null -> BingersTarget(title, tvdbEpisode = tvdbEpisode)
        episode.season != null && episode.season >= 0 ->
            BingersTarget(title, season = episode.season, episode = episode.number)
        tvdbEpisode != null -> BingersTarget(title, tvdbEpisode = tvdbEpisode)
        else -> null
    }
}

private val IMDB_ID = Regex("(?i)tt\\d{5,12}")
private val TVDB_ID = Regex("\\d{1,12}")
