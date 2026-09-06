package io.github.thgillwtnorizoh.modesty.core.waveform

/**
 * Scales one cached source-waveform amplitude for display using clip gain.
 *
 * The cache remains source-pure. Display clamps at full scale because playback currently clamps
 * float PCM to [-1, 1] after gain, so a clipped preview also looks clipped.
 */
fun scaleWaveformAmplitude(amplitude: Float, gain: Float): Float {
    require(amplitude.isFinite()) { "Waveform amplitude must be finite" }
    require(gain >= 0f && gain.isFinite()) { "Gain must be finite and non-negative" }

    if (amplitude == 0f || gain == 0f) return 0f

    val scaled = amplitude * gain
    return when {
        scaled.isFinite() -> scaled.coerceIn(-1f, 1f)
        amplitude < 0f -> -1f
        else -> 1f
    }
}
