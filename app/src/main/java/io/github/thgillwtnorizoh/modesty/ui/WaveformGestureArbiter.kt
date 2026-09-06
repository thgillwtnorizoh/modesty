package io.github.thgillwtnorizoh.modesty.ui

import kotlin.math.abs

enum class WaveformGestureIntent {
    UNDECIDED,
    SELECT,
    SCROLL,
}

object WaveformGestureArbiter {
    private const val DOMINANCE_RATIO = 1.2f

    fun classify(deltaX: Float, deltaY: Float, touchSlop: Int): WaveformGestureIntent {
        val absX = abs(deltaX)
        val absY = abs(deltaY)
        if (maxOf(absX, absY) < touchSlop) return WaveformGestureIntent.UNDECIDED

        return when {
            absX >= absY * DOMINANCE_RATIO -> WaveformGestureIntent.SELECT
            absY >= absX * DOMINANCE_RATIO -> WaveformGestureIntent.SCROLL
            else -> WaveformGestureIntent.UNDECIDED
        }
    }
}
