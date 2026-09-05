package io.github.thgillwtnorizoh.modesty.core.dsp

/**
 * Processes interleaved floating-point PCM. Implementations must not know about UI,
 * projects, files, or Android framework types.
 */
fun interface AudioProcessor {
    fun process(
        input: FloatArray,
        output: FloatArray,
        frameCount: Int,
        channelCount: Int,
    )
}
