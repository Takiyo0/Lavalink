package lavalink.server.player.crossfade

import com.github.natanbc.lavadsp.timescale.TimescalePcmAudioFilter
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Planar (channel-major) float FIFO. */
internal class FloatFifo(private val channels: Int) {
    private var data = Array(channels) { FloatArray(16384) }
    private var head = 0

    var size = 0
        private set

    fun write(source: Array<FloatArray>, offset: Int, length: Int) {
        if (length <= 0) return
        if (head + size + length > data[0].size) {
            val capacity = max(data[0].size, size + length) * 2
            val next = Array(channels) { FloatArray(capacity) }
            for (c in 0 until channels) System.arraycopy(data[c], head, next[c], 0, size)
            data = next
            head = 0
        }
        for (c in 0 until channels) System.arraycopy(source[c], offset, data[c], head + size, length)
        size += length
    }

    /** Reads up to [length] samples into [target] (planar) and returns how many were read. */
    fun read(target: Array<FloatArray>, length: Int): Int {
        val n = min(length, size)
        for (c in 0 until channels) System.arraycopy(data[c], head, target[c], 0, n)
        head += n
        size -= n
        if (size == 0) head = 0
        return n
    }

    fun clear() {
        head = 0
        size = 0
    }
}

/** A 4th-order (two cascaded biquads) Butterworth high-pass at a fixed cutoff: what is left of a track once its lows are taken out. */
internal class HighPass(private val channels: Int, private val sampleRate: Int, hz: Double) {
    private val state = Array(channels) { DoubleArray(8) } // per channel, per stage: x1, x2, y1, y2
    private val q = doubleArrayOf(0.5412, 1.3066)
    private val w0 = 2 * PI * hz / sampleRate

    /** Filters [length] samples of every channel in place. */
    fun process(buffer: Array<FloatArray>, length: Int) {
        for (stage in 0..1) {
            val alpha = sin(w0) / (2 * q[stage])
            val cosW = cos(w0)
            val a0 = 1 + alpha
            val b0 = (1 + cosW) / 2 / a0
            val b1 = -(1 + cosW) / a0
            val a1 = -2 * cosW / a0
            val a2 = (1 - alpha) / a0
            for (c in 0 until channels) {
                val s = state[c]
                val o = stage * 4
                var x1 = s[o]; var x2 = s[o + 1]; var y1 = s[o + 2]; var y2 = s[o + 3]
                val samples = buffer[c]
                for (k in 0 until length) {
                    val x = samples[k].toDouble()
                    val y = b0 * x + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2
                    x2 = x1; x1 = x; y2 = y1; y1 = y
                    samples[k] = y.toFloat()
                }
                s[o] = x1; s[o + 1] = x2; s[o + 2] = y1; s[o + 3] = y2
            }
        }
    }
}

/**
 * A 4th-order (two cascaded biquads) Butterworth low-pass whose cutoff glides from one frequency to another across each
 * block, so a closing or opening "muffle" is smooth. Bypassed while the cutoff is out of the audible range.
 */
internal class LowPassSweep(private val channels: Int, private val sampleRate: Int) {
    private val state = Array(channels) { DoubleArray(8) } // per channel, per stage: x1, x2, y1, y2
    private val q = doubleArrayOf(0.5412, 1.3066)

    fun reset() {
        state.forEach { it.fill(0.0) }
    }

    /** Filters [length] samples of every channel in place, moving the cutoff geometrically from [startHz] to [endHz]. */
    fun process(buffer: Array<FloatArray>, length: Int, startHz: Double, endHz: Double) {
        if (startHz >= BYPASS_HZ && endHz >= BYPASS_HZ) {
            // open: pass the audio through, but remember its last samples so the filter starts from the right state when it closes
            if (length >= 2) {
                for (c in 0 until channels) {
                    val last = buffer[c][length - 1].toDouble()
                    val before = buffer[c][length - 2].toDouble()
                    for (stage in 0..1) {
                        val o = stage * 4
                        state[c][o] = last; state[c][o + 1] = before; state[c][o + 2] = last; state[c][o + 3] = before
                    }
                }
            }
            return
        }
        val step = 32
        var i = 0
        while (i < length) {
            val n = min(step, length - i)
            val t = (i + n / 2.0) / length
            val hz = startHz * (endHz / startHz).pow(t)
            for (stage in 0..1) {
                val w0 = 2 * PI * hz.coerceIn(20.0, sampleRate * 0.45) / sampleRate
                val alpha = sin(w0) / (2 * q[stage])
                val cosW = cos(w0)
                val a0 = 1 + alpha
                val b0 = (1 - cosW) / 2 / a0
                val b1 = (1 - cosW) / a0
                val a1 = -2 * cosW / a0
                val a2 = (1 - alpha) / a0
                for (c in 0 until channels) {
                    val s = state[c]
                    val o = stage * 4
                    var x1 = s[o]; var x2 = s[o + 1]; var y1 = s[o + 2]; var y2 = s[o + 3]
                    val samples = buffer[c]
                    for (k in i until i + n) {
                        val x = samples[k].toDouble()
                        val y = b0 * x + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2
                        x2 = x1; x1 = x; y2 = y1; y1 = y
                        samples[k] = y.toFloat()
                    }
                    s[o] = x1; s[o + 1] = x2; s[o + 2] = y1; s[o + 3] = y2
                }
            }
            i += n
        }
    }

    companion object {
        const val BYPASS_HZ = 18_000.0
    }
}

/**
 * Time-stretches audio without changing its pitch, with a speed that can change while it runs. Output is collected in a FIFO
 * because the number of samples produced for a given input varies with the speed.
 */
internal class TempoStretcher(private val channels: Int, sampleRate: Int) : AutoCloseable {
    private val output = FloatFifo(channels)
    private val filter = TimescalePcmAudioFilter(object : FloatPcmAudioFilter {
        override fun process(input: Array<FloatArray>, offset: Int, length: Int) = output.write(input, offset, length)
        override fun seekPerformed(requestedTime: Long, providedTime: Long) {}
        override fun flush() {}
        override fun close() {}
    }, channels, sampleRate)

    val available get() = output.size

    var speed: Double
        get() = filter.speed
        set(value) {
            filter.speed = value
        }

    fun push(samples: Array<FloatArray>, length: Int) = filter.process(samples, 0, length)

    fun read(target: Array<FloatArray>, length: Int) = output.read(target, length)

    fun flush() = filter.flush()

    override fun close() = filter.close()
}

/**
 * Finds where the beats fall in a stretch of audio, given the tempo. It builds an onset envelope (rises in low-band and
 * high-band energy, so kicks and hats both count) and slides a beat comb of the expected period across it. The comb that
 * lines up with the most onsets gives the phase; how much better it is than the average comb is the confidence.
 *
 * It measures only the offset of the beat grid within the audio, which is what is needed to line two songs up: whether
 * a beat is the first of a bar still comes from the analysis.
 */
internal object BeatPhaseEstimator {
    const val HOP = 240 // 5 ms at 48 kHz

    /**
     * Below this the pulse is not clear enough to trust (measured on synthetic audio: clean or noisy beats score about 7.8 to 8,
     * pure noise 2.6 to 4.1). It is deliberately strict: when unsure, the transition keeps the analysis' beat grid.
     */
    const val MIN_CONFIDENCE = 5.5

    /** @property phaseSeconds when a beat falls, from the start of the audio, 0 <= phase < period
     *  @property confidence how many standard deviations the best comb stands above the other phases */
    data class Result(val phaseSeconds: Double, val periodSeconds: Double, val confidence: Double)

    fun estimate(mono: FloatArray, count: Int, sampleRate: Int, periodSeconds: Double, searchFraction: Double = 0.015): Result? {
        val bins = count / HOP
        if (bins < 40 || periodSeconds <= 0) return null
        val envelope = onsetEnvelope(mono, count, sampleRate)
        val hopSeconds = HOP.toDouble() / sampleRate

        var best: Result? = null
        var bestScore = 0.0
        for (step in -3..3) {
            val period = periodSeconds * (1 + searchFraction * step / 3.0)
            val periodBins = period / hopSeconds
            if (periodBins < 8 || periodBins > bins / 3.0) continue
            val phases = periodBins.toInt()
            val scores = DoubleArray(phases) { combScore(envelope, it.toDouble(), periodBins) }
            val mean = scores.average()
            val deviation = sqrt(scores.sumOf { (it - mean) * (it - mean) } / phases)
            if (mean <= 1e-9 || deviation <= 1e-9) continue
            var topPhase = 0
            for (p in 1 until phases) if (scores[p] > scores[topPhase]) topPhase = p
            // how many standard deviations the best comb stands above the others: a real pulse stands far out, noise does not
            val confidence = (scores[topPhase] - mean) / deviation
            if (scores[topPhase] > bestScore) {
                bestScore = scores[topPhase]
                best = Result(topPhase * hopSeconds, period, confidence)
            }
        }
        return best
    }

    private fun combScore(envelope: DoubleArray, phase: Double, periodBins: Double): Double {
        var total = 0.0
        var n = 0
        var position = phase
        while (position < envelope.size - 1) {
            val i = position.toInt()
            val frac = position - i
            total += envelope[i] * (1 - frac) + envelope[i + 1] * frac
            n++
            position += periodBins
        }
        return if (n == 0) 0.0 else total / n
    }

    private fun onsetEnvelope(mono: FloatArray, count: Int, sampleRate: Int): DoubleArray {
        val bins = count / HOP
        val lowCoefficient = 1 - exp(-2 * PI * 200.0 / sampleRate)
        val highCoefficient = 1 - exp(-2 * PI * 2000.0 / sampleRate)
        var lowState = 0.0
        var highState = 0.0
        val low = DoubleArray(bins)
        val high = DoubleArray(bins)
        for (b in 0 until bins) {
            var lowEnergy = 0.0
            var highEnergy = 0.0
            for (i in b * HOP until (b + 1) * HOP) {
                val x = mono[i].toDouble()
                lowState += lowCoefficient * (x - lowState)
                highState += highCoefficient * (x - highState)
                val hp = x - highState
                lowEnergy += lowState * lowState
                highEnergy += hp * hp
            }
            low[b] = sqrt(lowEnergy / HOP)
            high[b] = sqrt(highEnergy / HOP)
        }
        val envelope = DoubleArray(bins)
        for (band in listOf(low, high)) {
            val onsets = DoubleArray(bins)
            for (b in 1 until bins) onsets[b] = max(0.0, band[b] - band[b - 1])
            val mean = onsets.average() + 1e-9
            for (b in 0 until bins) envelope[b] += onsets[b] / mean
        }
        // a light smoothing tolerates onsets that land between two bins
        val smooth = DoubleArray(bins)
        for (b in 0 until bins) {
            smooth[b] = 0.5 * envelope[b] + 0.25 * envelope[max(0, b - 1)] + 0.25 * envelope[min(bins - 1, b + 1)]
        }
        return smooth
    }
}
