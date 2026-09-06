package io.github.thgillwtnorizoh.modesty.core.dsp

import kotlin.math.log10
import kotlin.math.pow

/** Converts the editor's human-facing dB value into the linear gain used by DSP and clip metadata. */
fun decibelsToLinearGain(decibels: Float): Float {
    require(decibels.isFinite()) { "Decibels must be finite" }
    return 10.0.pow(decibels.toDouble() / 20.0).toFloat().also { gain ->
        require(gain.isFinite() && gain >= 0f) { "Decibel value produced an invalid gain" }
    }
}

/** Converts a positive linear gain back to dB for display. Zero gain is represented as -Infinity. */
fun linearGainToDecibels(gain: Float): Float {
    require(gain >= 0f && gain.isFinite()) { "Gain must be finite and non-negative" }
    if (gain == 0f) return Float.NEGATIVE_INFINITY
    return (20.0 * log10(gain.toDouble())).toFloat()
}
