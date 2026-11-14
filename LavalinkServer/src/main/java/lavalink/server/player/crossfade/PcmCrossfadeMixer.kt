package lavalink.server.player.crossfade

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Stateless, sample-accurate equal-power mixing for interleaved signed PCM. */
internal object PcmCrossfadeMixer {
    /**
     * [offsetFrames] and [durationFrames] refer to sample frames, not individual
     * interleaved samples. A null input is silence. A linked per-frame peak
     * limiter prevents overflow while preserving stereo balance and endpoints.
     *
     * [entryGain] scales the incoming track at the start of the fade and ramps linearly to
     * unity by its end, so a louder incoming track can be matched to the outgoing one without a
     * level step when the fade completes.
     */
    fun mix(
        a: ShortArray?, b: ShortArray?, output: ShortArray, channels: Int, offsetFrames: Long, durationFrames: Long,
        entryGain: Double = 1.0
    ) {
        require(channels > 0 && output.size % channels == 0 && durationFrames > 0 && offsetFrames >= 0)
        require(a == null || a.size == output.size)
        require(b == null || b.size == output.size)
        val frame = DoubleArray(channels)
        for (frameIndex in 0 until output.size / channels) {
            val t = ((offsetFrames + frameIndex).toDouble() / durationFrames).coerceIn(0.0, 1.0)
            val gainA = cos(t * PI / 2)
            val gainB = sin(t * PI / 2) * (entryGain + (1.0 - entryGain) * t)
            var peak = 32767.0
            for (channel in 0 until channels) {
                val i = frameIndex * channels + channel
                frame[channel] = (a?.get(i)?.toDouble() ?: 0.0) * gainA + (b?.get(i)?.toDouble() ?: 0.0) * gainB
                peak = maxOf(peak, kotlin.math.abs(frame[channel]))
            }
            val gain = 32767.0 / peak
            for (channel in 0 until channels) {
                output[frameIndex * channels + channel] = (frame[channel] * gain).roundToInt().coerceIn(-32768, 32767).toShort()
            }
        }
    }
}
