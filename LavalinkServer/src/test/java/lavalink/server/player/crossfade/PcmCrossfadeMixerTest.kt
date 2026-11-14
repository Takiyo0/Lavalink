package lavalink.server.player.crossfade

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class PcmCrossfadeMixerTest {
    @Test fun `fade traverses stereo frames and bounds correlated full scale`() {
        val a = ShortArray(10) { Short.MAX_VALUE }
        val b = ShortArray(10) { Short.MAX_VALUE }
        val out = ShortArray(10)
        PcmCrossfadeMixer.mix(a, b, out, 2, 0, 4)
        assertEquals(out[0], out[1])
        assertEquals(out[8], out[9])
        assertTrue(out.all { it > 23000 })
        assertTrue(out.all { it <= Short.MAX_VALUE })
        assertTrue(abs(out[4].toInt() - Short.MAX_VALUE.toInt()) <= 1)
    }

    @Test fun `a fades out and b fades in across blocks`() {
        val out = ShortArray(6)
        PcmCrossfadeMixer.mix(ShortArray(6) { 20000 }, null, out, 1, 0, 5)
        assertTrue((0 until out.lastIndex).all { out[it] >= out[it + 1] })
        assertEquals(0, out.last().toInt())
        PcmCrossfadeMixer.mix(null, ShortArray(6) { 20000 }, out, 1, 0, 5)
        assertTrue((0 until out.lastIndex).all { out[it] <= out[it + 1] })
        assertEquals(0, out.first().toInt())
    }

    @Test fun `entry gain scales the incoming track and ramps to unity without a step`() {
        val frames = 960
        val a = ShortArray(frames * 2)
        val b = ShortArray(frames * 2) { 10000 }
        fun mixAt(offset: Long, gain: Double): Int {
            val out = ShortArray(frames * 2)
            PcmCrossfadeMixer.mix(a, b, out, 2, offset, 48000, gain)
            return out[out.size - 2].toInt()
        }
        // the fade has just begun: b is attenuated by the entry gain
        val plain = mixAt(24000, 1.0)
        val attenuated = mixAt(24000, 0.5)
        assertTrue(attenuated < plain, "half gain should be quieter mid-fade: $attenuated vs $plain")
        // by the end of the fade the entry gain has fully ramped away
        val endPlain = mixAt(48000 - frames.toLong(), 1.0)
        val endAttenuated = mixAt(48000 - frames.toLong(), 0.5)
        assertTrue(kotlin.math.abs(endPlain - endAttenuated) < 150, "no level step at the end of the fade: $endPlain vs $endAttenuated")
    }
}
