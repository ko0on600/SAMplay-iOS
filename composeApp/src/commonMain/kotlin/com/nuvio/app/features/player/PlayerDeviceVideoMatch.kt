package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.nuvio.app.features.servers.webdav.WebDavMetadata
import com.nuvio.app.features.servers.webdav.WebDavNames
import kotlinx.coroutines.CancellationException

/**
 * SAMplay 2.0.3: the catalogue title a video file on this device was matched to by its name.
 * Subtitle add-ons and OpenSubtitles look a video up by its IMDb id, and a device file had none, so
 * none of them was asked, and the online search started from the raw file name.
 */
internal data class DeviceVideoMatch(
    val imdbId: String,
    /** "movie" or "series". */
    val type: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
) {
    /** The id subtitle add-ons are asked for: "tt…" for a film, "tt…:season:episode" for an episode. */
    val subtitleVideoId: String
        get() = if (type == "series" && season != null && episode != null) "$imdbId:$season:$episode" else imdbId
}

internal object DeviceVideoMatcher {
    /** What a file name (and its folder's) says: the name to look up, and the episode when it is one. */
    data class Query(
        val name: String,
        val title: String,
        val series: Boolean,
        val year: Int?,
        val season: Int?,
        val episode: Int?,
    )

    fun query(fileName: String, folderName: String?): Query? {
        val file = fileName.trim()
        if (file.isEmpty()) return null
        val parsed = WebDavNames.parse(file)
        val folder = folderName?.trim()?.takeIf { it.isNotEmpty() && !isGenericFolder(it) }
        // "E01.mkv", "01.mkv", "Episode 5.mp4" in a show's folder: the folder names the show.
        val bareEpisode = isBareEpisodeName(WebDavNames.lookupTitle(file))
        val episode = parsed.episode ?: if (bareEpisode && folder != null) WebDavNames.episodeNumber(file) else null
        val name = when {
            !bareEpisode -> file
            folder != null && episode != null -> folder
            else -> return null
        }
        val title = WebDavNames.lookupTitle(name)
        if (title.length < 2) return null
        val season = if (episode == null) {
            null
        } else {
            parsed.season ?: folder?.let(WebDavNames::seasonOfFolder) ?: 1
        }
        return Query(
            name = name,
            title = title,
            series = episode != null,
            year = WebDavNames.parse(name).year,
            season = season,
            episode = episode,
        )
    }

    /** The catalogue title (with an IMDb id) [fileName] names, or null when none is found. */
    suspend fun match(fileName: String, folderName: String?): DeviceVideoMatch? {
        val query = query(fileName, folderName) ?: return null
        val meta = WebDavMetadata.find(query.name, series = query.series, yearHint = query.year) ?: return null
        val imdbId = meta.imdbId?.takeIf { it.startsWith("tt") } ?: return null
        return DeviceVideoMatch(
            imdbId = imdbId,
            type = if (query.series) "series" else "movie",
            title = meta.name.ifBlank { query.title },
            season = query.season,
            episode = query.episode,
        )
    }

    private fun isBareEpisodeName(title: String): Boolean {
        val words = WebDavNames.normalize(title)
            .split(' ')
            .filterNot { it.isEmpty() || it in EPISODE_WORDS || it.all(Char::isDigit) }
        // "e01" or "ep5" alone.
        return words.all { word -> EPISODE_TOKEN.matches(word) }
    }

    /** Folders that hold all kinds of videos, whose name is not a show's. */
    private fun isGenericFolder(name: String): Boolean = WebDavNames.normalize(name) in GENERIC_FOLDERS

    private val EPISODE_TOKEN = Regex("""(e|ep)\d{1,3}""")
    private val EPISODE_WORDS = setOf("e", "ep", "episode", "part", "pt", "الحلقه", "حلقه")
    private val GENERIC_FOLDERS = setOf(
        "download", "downloads", "movies", "movie", "films", "video", "videos", "camera", "dcim",
        "whatsapp video", "whatsapp videos", "telegram", "telegram video", "screen recordings",
        "screenrecorder", "samplay", "nuvio", "series", "tv", "shows", "0", "emulated",
    )
}

/** SAMplay 2.0.3: looks the playing device file up in the catalogue when it changes. */
@Composable
internal fun PlayerScreenRuntime.BindDeviceVideoMatchEffects() {
    if (!isDeviceVideo) return
    val fileName = activeSourceFilename ?: activeStreamFilename
    val folderName = activeStreamSubtitle
    LaunchedEffect(activeSourceUrl, fileName, folderName) {
        deviceVideoMatch = null
        val name = fileName?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        deviceVideoMatch = try {
            DeviceVideoMatcher.match(name, folderName)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
}
