package com.nuvio.app.features.reshaped

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.StoredLibraryPayload
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SamplayV32DataTest {
    private val cinemeta = "https://v3-cinemeta.strem.io/manifest.json"
    private val other = "https://example.test/addon/manifest.json"

    @Test
    fun addonUrlsComeInV32Order() {
        val urls = SamplayV32Data.parseAddonUrls("""["$cinemeta","$other","$cinemeta"," "]""")
        assertEquals(listOf(cinemeta, other), urls)
        assertEquals("$cinemeta\n$other", SamplayV32Data.mergeAddonUrls(null, urls))
    }

    @Test
    fun brokenOrMissingV32DataImportsNothing() {
        assertEquals(emptyList(), SamplayV32Data.parseAddonUrls(null))
        assertEquals(emptyList(), SamplayV32Data.parseAddonUrls("not json"))
        assertEquals(emptyMap(), SamplayV32Data.parseAddonEnabled("[1,2]"))
        assertEquals(emptyList(), SamplayV32Data.parseDrives("{}"))
        assertEquals(emptyList(), SamplayV32Data.parseLibrary("", nowEpochMs = 1L))
    }

    @Test
    fun addonsAlreadyInstalledStayAndAreNotRepeated() {
        assertNull(SamplayV32Data.mergeAddonUrls(cinemeta, listOf(cinemeta)))
        assertEquals("$cinemeta\n$other", SamplayV32Data.mergeAddonUrls("$cinemeta\n", listOf(other, cinemeta)))
    }

    @Test
    fun switchedOffAddonsStayOff() {
        val enabled = SamplayV32Data.parseAddonEnabled("""{"$other":false,"$cinemeta":true}""")
        assertEquals(
            "$cinemeta\ttrue\n$other\tfalse",
            SamplayV32Data.mergeAddonEnabled(null, listOf(cinemeta, other), enabled),
        )
        // 2.0's own choice wins; add-ons v32 never switched keep "on".
        assertEquals(
            "$cinemeta\tfalse\n$other\ttrue",
            SamplayV32Data.mergeAddonEnabled("$cinemeta\tfalse", listOf(cinemeta, other), emptyMap()),
        )
        assertNull(SamplayV32Data.mergeAddonEnabled("$cinemeta\ttrue", listOf(cinemeta), emptyMap()))
    }

    @Test
    fun drivesNeedAnAddressAndAreKeptOncePerUser() {
        val drives = SamplayV32Data.parseDrives(
            """[
              {"id":"a","label":"PikPak","baseUrl":"https://dav.mypikpak.com","username":"u","password":"p"},
              {"id":"b","label":"Copy","baseUrl":"https://dav.mypikpak.com/","username":"u","password":"p2"},
              {"id":"c","label":"No address","baseUrl":"","username":"x","password":"y"}
            ]""",
        )
        assertEquals(1, drives.size)
        assertEquals("https://dav.mypikpak.com", drives.single().baseUrl)
        assertEquals("p", drives.single().password)
        assertFalse(drives.single().toString().contains("password"), "the password is not logged")
    }

    @Test
    fun libraryKeepsV32TitlesAndDates() {
        val items = SamplayV32Data.parseLibrary(
            """[
              {"id":"tt0111161","type":"movie","name":"Film","poster":"https://img.test/p.jpg","addon":"Cinemeta","sourceId":"$cinemeta","addedAt":200},
              {"id":"kitsu:1","type":"series","name":"","addedAt":0},
              {"id":"tt0111161","type":"movie","name":"Dupe","addedAt":100},
              {"type":"movie","name":"No id"}
            ]""",
            nowEpochMs = 50L,
        )
        assertEquals(listOf("tt0111161", "kitsu:1"), items.map(LibraryItem::id))
        assertEquals("Film", items[0].name)
        assertEquals("tt0111161", items[0].imdbId)
        assertEquals(200L, items[0].savedAtEpochMs)
        assertEquals("kitsu:1", items[1].name, "a title without a name shows its id")
        assertEquals(50L, items[1].savedAtEpochMs)
        assertNull(items[1].imdbId)
    }

    @Test
    fun libraryMergeKeepsWhatTwoPointZeroHas() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val mine = LibraryItem(id = "tt1", type = "movie", name = "Mine", savedAtEpochMs = 300L)
        val current = json.encodeToString(StoredLibraryPayload.serializer(), StoredLibraryPayload(items = listOf(mine)))
        val v32 = listOf(
            LibraryItem(id = "tt1", type = "movie", name = "Old copy", savedAtEpochMs = 100L),
            LibraryItem(id = "tt2", type = "series", name = "New", savedAtEpochMs = 400L),
        )
        val merged = SamplayV32Data.mergeLibraryPayload(current, v32)!!
        val stored = json.decodeFromString(StoredLibraryPayload.serializer(), merged)
        assertEquals(listOf("tt2", "tt1"), stored.items.map(LibraryItem::id))
        assertEquals("Mine", stored.items.single { it.id == "tt1" }.name)

        assertNull(SamplayV32Data.mergeLibraryPayload(merged, v32), "nothing new the second time")
        assertNull(SamplayV32Data.mergeLibraryPayload("{broken", v32), "a damaged library is left alone")
        assertTrue(SamplayV32Data.mergeLibraryPayload(null, v32)!!.contains("\"tt2\""))
    }
}
