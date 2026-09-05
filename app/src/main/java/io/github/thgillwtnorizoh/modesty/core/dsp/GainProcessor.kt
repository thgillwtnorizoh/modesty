package io.github.thgillwtnorizoh.modesty.core.dsp

class GainProcessor(private val gain: Float) : AudioProcessor {
    init {
        require(gain >= 0f && gain.isFinite()) { "Gain must be finite and non-negative" }
    }

    override fun process(
        input: FloatArray,
        output: FloatArray,
        frameCount: Int,
        channelCount: Int,
    ) {
        require(frameCount >= 0)
        require(channelCount > 0)
        val sampleCount = frameCount * channelCount
        require(input.size >= sampleCount) { "Input buffer is too small" }
        require(output.size >= sampleCount) { "Output buffer is too small" }

        for (sample in 0 until sampleCount) {
            output[sample] = input[sample] * gain
        }
    }
}
