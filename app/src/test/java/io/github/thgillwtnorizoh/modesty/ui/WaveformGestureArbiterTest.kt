package io.github.thgillwtnorizoh.modesty.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WaveformGestureArbiterTest {
    @Test
    fun staysUndecidedInsideTouchSlop() {
        assertEquals(
            WaveformGestureIntent.UNDECIDED,
            WaveformGestureArbiter.classify(deltaX = 5f, deltaY = 4f, touchSlop = 8),
        )
    }

    @Test
    fun horizontalDragWinsDespiteOrdinaryVerticalJitter() {
        assertEquals(
            WaveformGestureIntent.SELECT,
            WaveformGestureArbiter.classify(deltaX = 24f, deltaY = 9f, touchSlop = 8),
        )
    }

    @Test
    fun deliberateVerticalDragHandsGestureToScroll() {
        assertEquals(
            WaveformGestureIntent.SCROLL,
            WaveformGestureArbiter.classify(deltaX = 6f, deltaY = 22f, touchSlop = 8),
        )
    }

    @Test
    fun diagonalMovementWaitsForClearIntent() {
        assertEquals(
            WaveformGestureIntent.UNDECIDED,
            WaveformGestureArbiter.classify(deltaX = 15f, deltaY = 14f, touchSlop = 8),
        )
    }
}
