package com.nuvio.app.features.collection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CollectionRepositoryTest {
    @Test
    fun `import input is treated as url only for a single http link`() {
        val url = "https://raw.githubusercontent.com/user/repo/main/collections.json"

        assertEquals(url, collectionImportUrl("  $url\n"))
        assertEquals("HTTP://example.com/a.json", collectionImportUrl("HTTP://example.com/a.json"))
        assertNull(collectionImportUrl("""[{"id":"a","title":"https://example.com"}]"""))
        assertNull(collectionImportUrl("https://example.com/a.json trailing text"))
        assertNull(collectionImportUrl("ftp://example.com/a.json"))
    }

    @Test
    fun `deduplication keeps stable order and latest collection value`() {
        val collections = listOf(
            Collection(id = "duplicate", title = "Old value"),
            Collection(id = "other", title = "Other"),
            Collection(id = "duplicate", title = "Latest value"),
        )

        val result = collections.deduplicatedById()

        assertEquals(listOf("duplicate", "other"), result.map(Collection::id))
        assertEquals("Latest value", result.first().title)
    }

    @Test
    fun `upsert replaces duplicate collection without changing its position`() {
        val collections = listOf(
            Collection(id = "duplicate", title = "Old value"),
            Collection(id = "other", title = "Other"),
            Collection(id = "duplicate", title = "Stale duplicate"),
        )

        val result = collections.upsertCollectionById(
            Collection(id = "duplicate", title = "Replacement"),
        )

        assertEquals(listOf("duplicate", "other"), result.map(Collection::id))
        assertEquals("Replacement", result.first().title)
    }
}
