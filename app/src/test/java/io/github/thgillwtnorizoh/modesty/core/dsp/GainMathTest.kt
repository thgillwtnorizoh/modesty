package io.github.thgillwtnorizoh.modesty.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

class GainMathTest {
    @Test
    fun convertsBetweenDecibelsAndLinearGain() {
        val doubled = decibelsToLinearGain(6.0206f)
        val halved = decibelsToLinearGain(-6.0206f)

        assertEquals(2f, doubled, 0.001f)
        assertEquals(0.5f, halved, 0.001f)
        assertEquals(6.0206f, linearGainToDecibels(2f), 0.001f)
    }
}
