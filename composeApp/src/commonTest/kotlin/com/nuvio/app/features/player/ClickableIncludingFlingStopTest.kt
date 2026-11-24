package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClickableIncludingFlingStopTest {
    @Test
    fun unconsumedDownLeavesTheClickToClickable() {
        assertFalse(
            shouldDeliverFlingStopClick(
                downConsumed = false,
                movement = 0f,
                touchSlop = 18f,
                pointerUp = true,
            ),
        )
    }

    @Test
    fun consumedDownAndUpWithinSlopSelectsTheRow() {
        assertTrue(
            shouldDeliverFlingStopClick(
                downConsumed = true,
                movement = 4f,
                touchSlop = 18f,
                pointerUp = true,
            ),
        )
    }

    @Test
    fun consumedDownPastSlopIsADragNotAClick() {
        assertFalse(
            shouldDeliverFlingStopClick(
                downConsumed = true,
                movement = 24f,
                touchSlop = 18f,
                pointerUp = true,
            ),
        )
    }

    // SAMplay 2.0.3: the row follows the finger while the list scrolls, so the finger barely
    // moves over it; the list having scrolled is what says it was a flick.
    @Test
    fun aFlickThatScrolledTheListIsNotAClick() {
        assertFalse(
            shouldDeliverFlingStopClick(
                downConsumed = true,
                movement = 2f,
                touchSlop = 18f,
                pointerUp = true,
                scrolled = true,
            ),
        )
    }

    @Test
    fun consumedDownWithoutUpDoesNotClick() {
        assertFalse(
            shouldDeliverFlingStopClick(
                downConsumed = true,
                movement = 0f,
                touchSlop = 18f,
                pointerUp = false,
            ),
        )
    }
}
