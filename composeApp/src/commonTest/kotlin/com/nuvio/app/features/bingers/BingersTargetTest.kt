package com.nuvio.app.features.bingers

import com.nuvio.app.features.tracking.TrackingEpisode
import com.nuvio.app.features.tracking.TrackingExternalIds
import com.nuvio.app.features.tracking.TrackingMediaKind
import com.nuvio.app.features.tracking.TrackingMediaReference
import com.nuvio.app.features.tracking.buildTrackingMediaReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BingersTargetTest {
    @Test
    fun `imdb episode keeps the show id with season and number`() {
        val media = buildTrackingMediaReference(
            contentType = "series",
            parentMetaId = "tt0903747",
            videoId = "tt0903747:2:5",
            seasonNumber = 2,
            episodeNumber = 5,
        )

        val target = bingersTarget(media)

        assertEquals(BingersTarget("imdb:tt0903747", season = 2, episode = 5), target)
        assertEquals(
            """{"title":"imdb:tt0903747","season":2,"episode":5}""",
            target?.toJson().toString(),
        )
    }

    @Test
    fun `movie never sends season or episode`() {
        val media = buildTrackingMediaReference(contentType = "movie", parentMetaId = "tt1375666")

        val target = bingersTarget(media)

        assertEquals(BingersTarget("imdb:tt1375666"), target)
        assertFalse(target!!.isEpisode)
    }

    @Test
    fun `tmdb ids say whether they are a movie or a show`() {
        val movie = buildTrackingMediaReference(contentType = "movie", parentMetaId = "tmdb:27205")
        val show = buildTrackingMediaReference(
            contentType = "series",
            parentMetaId = "tmdb:1396",
            seasonNumber = 1,
            episodeNumber = 1,
        )

        assertEquals("tmdb:movie:27205", bingersTarget(movie)?.title)
        assertEquals(BingersTarget("tmdb:show:1396", season = 1, episode = 1), bingersTarget(show))
    }

    @Test
    fun `imdb is preferred over tmdb and tvdb`() {
        val media = TrackingMediaReference(
            kind = TrackingMediaKind.SHOW,
            ids = TrackingExternalIds(imdb = "tt0944947", tmdb = 1399, tvdb = "121361"),
        )

        assertEquals("imdb:tt0944947", bingersTarget(media)?.title)
    }

    @Test
    fun `tvdb season mapping uses the tvdb episode id`() {
        val media = TrackingMediaReference(
            kind = TrackingMediaKind.ANIME,
            ids = TrackingExternalIds(tvdb = "81797"),
            episode = TrackingEpisode(season = 1, number = 1050, tvdbId = 9_000_001, usesTvdbSeasonMapping = true),
        )

        val target = bingersTarget(media)

        assertEquals(BingersTarget("tvdb:81797", tvdbEpisode = "9000001"), target)
        assertEquals("""{"title":"tvdb:81797","tvdb_episode":"9000001"}""", target?.toJson().toString())
    }

    @Test
    fun `media Bingers cannot address is skipped`() {
        val kitsuOnly = buildTrackingMediaReference(contentType = "anime", parentMetaId = "kitsu:1", episodeNumber = 3)
        val absoluteEpisode = TrackingMediaReference(
            kind = TrackingMediaKind.ANIME,
            ids = TrackingExternalIds(imdb = "tt0388629"),
            episode = TrackingEpisode(season = null, number = 1000),
        )
        val badImdb = TrackingMediaReference(kind = TrackingMediaKind.MOVIE, ids = TrackingExternalIds(imdb = "nm0000123"))

        assertNull(bingersTarget(kitsuOnly))
        assertNull(bingersTarget(absoluteEpisode))
        assertNull(bingersTarget(badImdb))
    }

    @Test
    fun `specials in season zero are kept`() {
        val media = buildTrackingMediaReference(
            contentType = "series",
            parentMetaId = "tt0098904",
            seasonNumber = 0,
            episodeNumber = 1,
        )

        assertEquals(BingersTarget("imdb:tt0098904", season = 0, episode = 1), bingersTarget(media))
    }

    @Test
    fun `account name and write scope are read from me`() {
        val account = parseBingersAccount("""{"name":"Sam","handle":"sam","country":"SA","scopes":["library:read","library:write"]}""")
        val readOnly = parseBingersAccount("""{"user":{"handle":"sam"},"scopes":"read"}""")
        val bare = parseBingersAccount("""{"id":"x"}""")

        assertEquals(BingersAccount("Sam", canWrite = true), account)
        assertEquals(BingersAccount("sam", canWrite = false), readOnly)
        assertEquals(BingersAccount(null, canWrite = null), bare)
    }

    @Test
    fun `results count unresolved items`() {
        val results = parseBingersResults(
            """{"results":[{"index":0,"status":"ok","outcome":"watched"},""" +
                """{"index":1,"status":"already","outcome":"unchanged"},""" +
                """{"index":2,"status":"unresolved","message":"no such title"}],"dry_run":false}""",
        )

        assertEquals(3, results.size)
        assertTrue(results[0].isResolved)
        assertTrue(results[1].isResolved)
        assertFalse(results[2].isResolved)
        assertEquals("no such title", results[2].message)
    }
}
