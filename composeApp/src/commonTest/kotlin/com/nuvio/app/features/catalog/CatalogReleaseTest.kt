package com.nuvio.app.features.catalog

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogReleaseTest {
    private val parent = CatalogTarget.Server("pikpak", libraryId = null, contentType = "movie", collectionId = "/عربي/")
    private val child = parent.copy(collectionId = "/عربي/قديم/")

    @Test
    fun closingASubFolderKeepsTheFolderPageThatIsLoadingUnderIt() {
        // Back from the sub-folder: the folder page loads first, then the sub-folder page is disposed.
        assertFalse(shouldClearOnRelease(active = parent, released = child))
    }

    @Test
    fun closingThePageThatIsLoadedClearsIt() {
        assertTrue(shouldClearOnRelease(active = child, released = child))
        assertTrue(shouldClearOnRelease(active = null, released = child))
    }
}
