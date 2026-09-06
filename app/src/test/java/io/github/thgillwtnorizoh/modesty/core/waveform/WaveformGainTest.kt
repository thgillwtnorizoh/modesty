package io.github.thgillwtnorizoh.modesty.core.waveform

import org.junit.Assert.assertEquals
import org.junit.Test

class WaveformGainTest {
    @Test
    fun gainScalesCachedAmplitudeWithoutChangingSourceData() {
        assertEquals(0.5f, scaleWaveformAmplitude(0.25f, 2f), 0f)
        assertEquals(-0.5f, scaleWaveformAmplitude(-0.25f, 2f), 0f)
        assertEquals(0.125f, scaleWaveformAmplitude(0.25f, 0.5f), 0f)
    }

    @Test
    fun displayClampsWhenGainWouldClipPlayback() {
        assertEquals(1f, scaleWaveformAmplitude(0.75f, 2f), 0f)
        assertEquals(-1f, scaleWaveformAmplitude(-0.75f, 2f), 0f)
        assertEquals(1f, scaleWaveformAmplitude(1f, Float.MAX_VALUE), 0f)
    }
}
