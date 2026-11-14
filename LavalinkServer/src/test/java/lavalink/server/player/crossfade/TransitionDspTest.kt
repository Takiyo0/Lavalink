package lavalink.server.player.crossfade

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class TransitionDspTest {
    private val rate = 48000

    private fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Double {
        var sum = 0.0
        for (i in from until to) sum += samples[i] * samples[i]
        return sqrt(sum / (to - from))
    }

    /** Dominant frequency by counting rising zero crossings. */
    private fun frequency(samples: FloatArray, from: Int, to: Int): Double {
        var crossings = 0
        for (i in from + 1 until to) if (samples[i - 1] < 0 && samples[i] >= 0) crossings++
        return crossings / ((to - from).toDouble() / rate)
    }

    private fun tone(hz: Double, seconds: Double) = FloatArray((rate * seconds).toInt()) { (0.5 * sin(2 * PI * hz * it / rate)).toFloat() }

    // ---- low-pass sweep

    @Test fun `low pass passes lows and removes highs`() {
        val filter = LowPassSweep(1, rate)
        val low = arrayOf(tone(100.0, 0.5))
        val high = arrayOf(tone(8000.0, 0.5))
        filter.process(low, low[0].size, 600.0, 600.0)
        filter.reset()
        filter.process(high, high[0].size, 600.0, 600.0)

        assertTrue(rms(low[0], 4000) > 0.30, "100 Hz should pass a 600 Hz low-pass")
        assertTrue(20 * log10(rms(high[0], 4000) / 0.3536) < -40, "8 kHz should be removed by well over 40 dB")
    }

    @Test fun `low pass is bypassed above the audible range and sweeps smoothly`() {
        val filter = LowPassSweep(1, rate)
        val signal = arrayOf(tone(5000.0, 0.5))
        val copy = signal[0].copyOf()
        filter.process(signal, signal[0].size, 20000.0, 20000.0)
        assertArrayEquals(copy, signal[0], "no change while open")

        // closing from 20 kHz to 400 Hz across the block: loud at the start, nearly silent at the end
        val closing = arrayOf(tone(5000.0, 2.0))
        LowPassSweep(1, rate).process(closing, closing[0].size, 20000.0, 400.0)
        val start = rms(closing[0], 2000, 12000)
        val end = rms(closing[0], closing[0].size - 12000, closing[0].size - 2000)
        assertTrue(start > 0.3, "start=$start")
        assertTrue(end < start * 0.02, "end=$end start=$start")
        // no click: sample-to-sample jumps stay in line with a 5 kHz tone at this level
        var biggest = 0f
        for (i in 1 until closing[0].size) biggest = maxOf(biggest, abs(closing[0][i] - closing[0][i - 1]))
        assertTrue(biggest < 0.7f, "jump $biggest")
    }

    // ---- tempo stretch

    @Test fun `stretcher changes duration but not pitch and follows speed changes while running`() {
        val stretcher = TempoStretcher(1, rate)
        val input = tone(440.0, 12.0)
        val block = Array(1) { FloatArray(960) }
        val collected = ArrayList<Float>()
        val read = Array(1) { FloatArray(960) }
        var position = 0
        var speedChanged = -1
        while (position + 960 <= input.size) {
            // 1.0 for the first 4 s, then 1.1 (as if tempo-matching to a faster song)
            if (position >= rate * 4 && speedChanged < 0) { stretcher.speed = 1.1; speedChanged = collected.size }
            System.arraycopy(input, position, block[0], 0, 960)
            stretcher.push(block, 960)
            position += 960
            while (stretcher.available >= 960) {
                stretcher.read(read, 960)
                for (x in read[0]) collected.add(x)
            }
        }
        val output = collected.toFloatArray()
        // 4 s at speed 1, then 8 s at speed 1.1 -> about 4 + 8/1.1 = 11.27 s out
        val expected = (4 + 8 / 1.1) * rate
        assertEquals(expected, output.size.toDouble(), rate * 0.25, "output length ${output.size}")
        assertEquals(440.0, frequency(output, rate, rate * 3), 15.0, "pitch before the change")
        assertEquals(440.0, frequency(output, output.size - rate * 3, output.size - 1000), 15.0, "pitch after the change")
    }

    // ---- beat phase

    /** Beats every [bpm] with a kick (decaying 55 Hz burst) and, if wanted, a hat (3 kHz tick) on the off-beats. */
    private fun beats(bpm: Double, offsetSeconds: Double, seconds: Double, hz: Double = 55.0, decay: Double = 40.0): FloatArray {
        val out = FloatArray((rate * seconds).toInt())
        val period = 60.0 / bpm
        var t = offsetSeconds
        while (t < seconds) {
            val start = (t * rate).toInt()
            for (i in 0 until (0.12 * rate).toInt()) {
                val index = start + i
                if (index >= out.size) break
                val time = i.toDouble() / rate
                out[index] += (0.8 * exp(-decay * time) * sin(2 * PI * hz * time)).toFloat()
            }
            t += period
        }
        return out
    }

    @Test fun `beat phase is found within a few milliseconds`() {
        for (offset in listOf(0.0, 0.13, 0.31, 0.44)) {
            val audio = beats(120.0, offset, 4.0)
            val result = BeatPhaseEstimator.estimate(audio, audio.size, rate, 0.5)!!
            val error = abs(((result.phaseSeconds - offset) + 0.25).mod(0.5) - 0.25)
            assertTrue(error < 0.012, "offset $offset: found ${result.phaseSeconds} (error $error)")
            assertTrue(result.confidence > BeatPhaseEstimator.MIN_CONFIDENCE, "offset $offset: confidence ${result.confidence}")
        }
    }

    @Test fun `beat phase tolerates a slightly wrong tempo and finds hats as well as kicks`() {
        val audio = beats(120.0, 0.2, 4.0)
        val wrong = BeatPhaseEstimator.estimate(audio, audio.size, rate, 60.0 / 121.0)!! // 120 BPM claimed as 121
        assertEquals(0.2, wrong.phaseSeconds, 0.03)

        val hats = beats(100.0, 0.17, 4.0, hz = 3000.0, decay = 120.0)
        val result = BeatPhaseEstimator.estimate(hats, hats.size, rate, 0.6)!!
        assertEquals(0.17, result.phaseSeconds, 0.015)
    }

    @Test fun `noise is not mistaken for a beat but beats under noise still are`() {
        val random = Random(7)
        repeat(12) { run ->
            val noise = FloatArray(rate * 4) { (random.nextGaussian() * 0.1).toFloat() }
            val result = BeatPhaseEstimator.estimate(noise, noise.size, rate, 0.5)!!
            assertTrue(result.confidence < BeatPhaseEstimator.MIN_CONFIDENCE, "run $run: noise scored ${result.confidence}")
        }
        for (level in listOf(0.1, 0.3, 0.6)) {
            val mixed = beats(120.0, 0.13, 4.0)
            for (i in mixed.indices) mixed[i] += (random.nextGaussian() * level).toFloat()
            val result = BeatPhaseEstimator.estimate(mixed, mixed.size, rate, 0.5)!!
            assertTrue(result.confidence > BeatPhaseEstimator.MIN_CONFIDENCE, "noise $level: beats scored ${result.confidence}")
            assertEquals(0.13, result.phaseSeconds, 0.02)
        }
    }

    @Test fun `too short audio gives no answer`() {
        assertNull(BeatPhaseEstimator.estimate(FloatArray(rate / 10), rate / 10, rate, 0.5))
    }
}
