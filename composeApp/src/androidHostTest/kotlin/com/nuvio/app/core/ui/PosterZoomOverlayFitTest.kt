package com.nuvio.app.core.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SAMplay 2.0.3: long-pressing a card in landscape stacked the poster, the title and the
 * options in one column taller than the screen, so "Mark as watched" and "Remove" were cut
 * off and could not be reached. The user's phone runs a 1.7 font scale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w832dp-h384dp-land")
class PosterZoomOverlayFitTest {
    @get:Rule
    val compose = createComposeRule()

    private val picked = mutableListOf<String>()

    @Test
    fun landscapeReachesAndTapsTheLastOption() {
        showMenu()
        assertLastOptionReachable()
    }

    @Test
    fun landscapePutsTheOptionsBesideThePoster() {
        showMenu()
        val rootWidth = compose.onRoot().fetchSemanticsNode().size.width
        val title = compose.onNodeWithText(SUBTITLE).fetchSemanticsNode().boundsInRoot
        assertTrue(title.center.x > rootWidth * 0.55f, "title at ${title.center.x} of $rootWidth")
    }

    @Test
    @Config(qualifiers = "w384dp-h832dp-port")
    fun portraitStillStacksAndReachesTheLastOption() {
        showMenu()
        val rootWidth = compose.onRoot().fetchSemanticsNode().size.width
        val title = compose.onNodeWithText(SUBTITLE).fetchSemanticsNode().boundsInRoot
        assertEquals(rootWidth / 2f, title.center.x, rootWidth * 0.05f)
        assertLastOptionReachable()
    }

    private fun assertLastOptionReachable() {
        val last = compose.onNodeWithText(LAST).performScrollTo()
        val rootHeight = compose.onRoot().fetchSemanticsNode().size.height
        val bounds = last.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.top >= 0f && bounds.bottom <= rootHeight + 0.5f, "last option at $bounds, screen $rootHeight")
        last.performClick()
        compose.waitForIdle()
        assertEquals(listOf(LAST), picked)
    }

    private fun showMenu() {
        compose.setContent {
            NuvioTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.7f)) {
                    Box(Modifier.fillMaxSize()) {
                        NuvioPosterZoomActionOverlay(
                            imageUrl = null,
                            title = TITLE,
                            subtitle = SUBTITLE,
                            anchor = PosterZoomAnchor(
                                boundsInRoot = Rect(0f, 0f, 480f, 270f),
                                imageUrl = null,
                                cornerRadius = 12.dp,
                            ),
                            actions = OPTIONS.map { label ->
                                PosterZoomOverlayAction(
                                    icon = Icons.Default.Info,
                                    label = label,
                                    onSelected = { picked += label },
                                )
                            },
                            hazeState = remember { HazeState() },
                            onDismissed = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        const val TITLE = "Blade of the 47 Ronin"
        const val SUBTITLE = "Movie"
        const val LAST = "Last option"
        val OPTIONS = listOf("Go to details", "Play manually", "Start from beginning", "Mark as watched", LAST)
    }
}
