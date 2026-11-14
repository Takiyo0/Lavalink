package lavalink.server.player.crossfade

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.format.transcoder.AudioChunkDecoder
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The effects of an automix transition, already validated against both tracks.
 *
 * @property muffleExit the outgoing track's low-pass closes during the crash (35% to 85% of the overlap) while it fades away
 * @property muffleEntry the incoming track comes in muffled and its low-pass opens during the same crash, fully open when the outgoing track is gone
 * @property muffleHz the cutoff the muffled sound reaches
 * @property leadMs how long before the overlap the outgoing track is processed on its own
 * @property tempoRatio next tempo over current tempo: the outgoing track is stretched to it over the lead-in
 * @property beatSync measure where the beats fall in both tracks and line them up
 * @property outgoingBeatSeconds the beat period of the outgoing audio as it is heard (after any stretch)
 * @property incomingBeatSeconds the beat period of the incoming track
 * @property marginMs how far before its entry point the incoming track starts, so a beat just before it can be found
 */
internal data class TransitionEffects(
    val muffleExit: Boolean,
    val muffleEntry: Boolean,
    val muffleHz: Double,
    val leadMs: Long,
    val tempoRatio: Double?,
    val beatSync: Boolean,
    val outgoingBeatSeconds: Double?,
    val incomingBeatSeconds: Double?,
    val marginMs: Long,
)

/**
 * Renders a transition that needs more than a plain overlap: a lead-in where only the outgoing track is processed (low-pass
 * sweep, tempo ramp), the overlap itself, and a post-roll that returns the frames of the incoming track that were read ahead.
 *
 * The incoming track is read ahead into a queue so its beats can be measured before the overlap starts. Frames not used in the
 * overlap are returned unchanged afterwards, in order, and then the standby player carries on from exactly there.
 *
 * Alignment is done at the resolution of one frame (20 ms): the overlap begins on one of the outgoing track's measured beats and
 * the incoming track's queue is trimmed to start on one of its own.
 */
internal class TransitionEngine(
    configuration: AudioConfiguration,
    private val fadeMs: Long,
    private val entryGain: Double,
    private val exitMs: Long,
    private val leadStartMs: Long,
    private val fx: TransitionEffects,
    private val label: String = "player",
) : AutoCloseable {
    sealed interface Output {
        /** @property overlapEnded this was the last frame of the overlap: the incoming track has taken over, the read-ahead replay follows */
        class Frame(val opus: ByteArray, val timecode: Long, val overlapEnded: Boolean = false) : Output

        /** Nothing could be produced this tick (the outgoing track is momentarily starved). */
        data object Waiting : Output

        /** The overlap and the post-roll are over: promote the standby. */
        data object Done : Output

        /** The incoming track never provided audio: give up and restore the outgoing track. */
        data object Aborted : Output
    }

    /** Where the outgoing track's audio comes from: its player, possibly after frames that were read ahead of it and are still to be played. */
    interface FrameSource {
        fun provide(): AudioFrame?
        val playing: Boolean
    }

    private enum class Stage { LEAD, OVERLAP, POST }

    companion object {
        private val log = LoggerFactory.getLogger(TransitionEngine::class.java)
        private const val CHANNELS = 2
        private const val FRAME = 960
        private const val RATE = 48000
        private const val LOOKAHEAD_FRAMES = 300
        private const val MAX_PULL_PER_TICK = 64
        private const val DECISION_AHEAD_MS = 1500L
        private const val ANALYSIS_SECONDS = 3.0
        private const val HISTORY_SECONDS = 6
        private const val RAMP_SHARE = 0.4
        private const val WAIT_FOR_STANDBY_FRAMES = 100

        /**
         * The layering. The incoming track comes in at once, muffled, and reaches full level within [RISE_B] of the overlap. The sweeps
         * are one crash, between [CRASH_FROM] and [CRASH_TO]: the outgoing track's low-pass closes and it fades away while the incoming
         * track's low-pass opens. Both finish together, so when the outgoing track is gone the incoming one is already normal.
         */
        private const val CRASH_FROM = 0.35
        private const val CRASH_TO = 0.85
        private const val RISE_B = 0.12

        /**
         * The bass swap, on when both tracks are muffled: the incoming track comes in without its lows, and at one bar line in the
         * middle of the crash ([SWAP_AT] of the overlap, moved to the nearest bar) the outgoing track's lows go and the incoming
         * track's come in, over [SWAP_RAMP_MS]. Two bass lines at once is what makes a plain crossfade sound muddy.
         */
        private const val SWAP_AT = 0.6
        private const val SWAP_RAMP_MS = 40
        private const val BASS_HZ = 200.0
        private const val BEATS_PER_BAR = 4

        private const val OPEN_HZ = 20_000.0

        private fun smooth(p: Double) = p.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
    }

    private val format = StandardAudioDataFormats.DISCORD_OPUS
    private val decoderA = format.createDecoder()
    private val decoderB = format.createDecoder()
    private val encoder = format.createEncoder(configuration)
    private val shorts: ShortBuffer = ByteBuffer.allocateDirect(FRAME * CHANNELS * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
    private val encodeIn: ShortBuffer = ByteBuffer.allocateDirect(FRAME * CHANNELS * 2).order(ByteOrder.nativeOrder()).asShortBuffer()

    private val stretcher = if (fx.tempoRatio != null) TempoStretcher(CHANNELS, RATE) else null
    private val lowA = LowPassSweep(CHANNELS, RATE)
    private val lowB = LowPassSweep(CHANNELS, RATE)

    private val aFrame = Array(CHANNELS) { FloatArray(FRAME) }
    private val bFrame = Array(CHANNELS) { FloatArray(FRAME) }
    private val chunk = Array(CHANNELS) { FloatArray(FRAME) }
    private val history = FloatArray(HISTORY_SECONDS * RATE)
    private var historyWritten = 0L

    private val bQueue = ArrayDeque<AudioFrame>()
    private var stage = Stage.LEAD
    private var aInput = leadStartMs
    private var aExhausted = false
    private var leadFrames = 0
    private var overlapFrame = 0
    private val overlapFrames = ceil(fadeMs * RATE / 1000.0 / FRAME).toInt()

    private val bassSwap = fx.muffleExit && fx.muffleEntry
    private val highA = HighPass(CHANNELS, RATE, BASS_HZ)
    private val highB = HighPass(CHANNELS, RATE, BASS_HZ)
    private val withoutBass = Array(CHANNELS) { FloatArray(FRAME) }

    /** The sample of the overlap where the lows change hands: the bar line nearest [SWAP_AT], or [SWAP_AT] itself when the beat is not known. */
    private val swapSample: Double = run {
        val target = SWAP_AT * overlapFrames * FRAME
        val bar = (fx.outgoingBeatSeconds ?: 0.0) * BEATS_PER_BAR * RATE
        if (bar <= 0) target else (Math.round(target / bar) * bar).coerceIn(0.4 * overlapFrames * FRAME, 0.8 * overlapFrames * FRAME)
    }

    private var decided = false
    private var framesUntilOverlap = Int.MAX_VALUE
    private var trimFrames = (fx.marginMs * RATE / 1000.0 / FRAME).roundToInt()

    @Volatile
    var currentFinished = false
        private set

    /** The outgoing track reported its end; whatever audio it still has is played out and the transition completes. */
    fun currentEnded() {
        currentFinished = true
    }

    fun render(primary: FrameSource, standby: AudioPlayer?): Output {
        if (stage != Stage.POST) fillQueue(standby)
        return when (stage) {
            Stage.LEAD -> leadFrame(primary)
            Stage.OVERLAP -> overlapFrame(primary)
            Stage.POST -> bQueue.removeFirstOrNull()?.let { Output.Frame(it.data, it.timecode) } ?: Output.Done
        }
    }

    /** After the overlap: the frames of the incoming track that were read ahead, unchanged and in order, then [Output.Done]. */
    fun nextPostRollFrame(): Output = bQueue.removeFirstOrNull()?.let { Output.Frame(it.data, it.timecode) } ?: Output.Done

    /** The next read-ahead frame, unchanged, for a transition that begins before the replay is over and plays it as its own outgoing audio. */
    fun takeReplayFrame(): AudioFrame? = bQueue.removeFirstOrNull()

    // ---- lead-in: only the outgoing track, processed

    private fun leadFrame(primary: FrameSource): Output {
        if (bQueue.isEmpty() && leadFrames > WAIT_FOR_STANDBY_FRAMES) return Output.Aborted

        val progress = leadProgress()
        stretcher?.speed = 1 + (fx.tempoRatio!! - 1) * smooth(progress / RAMP_SHARE)
        val got = pullA(primary, aFrame)
        if (!got && !aExhausted) return Output.Waiting

        record(aFrame)
        // the lead-in is only for the tempo ramp and for measuring beats: the outgoing track is not muffled yet
        if (fx.muffleExit) lowA.process(aFrame, FRAME, OPEN_HZ, OPEN_HZ)
        leadFrames++

        val timecode = aInput
        val frame = encode(aFrame)

        if (!beatSyncAttempted && fx.beatSync && !aExhausted && exitMs - aInput <= DECISION_AHEAD_MS) decide()
        val due = when {
            aExhausted -> true
            decided -> --framesUntilOverlap <= 0
            else -> aInput >= exitMs
        }
        if (due) {
            if (bQueue.size > trimFrames) {
                repeat(trimFrames) { bQueue.removeFirst() }
                stage = Stage.OVERLAP
                log.debug("[{}] Overlap begins: outgoing track at {} ms, incoming queue trimmed by {} frames, {} frames buffered", label, aInput, trimFrames, bQueue.size)
            } else if (leadFrames > WAIT_FOR_STANDBY_FRAMES * 3) {
                return Output.Aborted
            }
        }
        return Output.Frame(frame, timecode)
    }

    private fun leadProgress(): Double =
        if (exitMs <= leadStartMs) 1.0 else ((aInput - leadStartMs).toDouble() / (exitMs - leadStartMs)).coerceIn(0.0, 1.0)

    /** The outgoing track's cutoff at a point (0 to 1) of the overlap: open until the crash, muffled by its end. */
    private fun closing(t: Double) = OPEN_HZ * (fx.muffleHz / OPEN_HZ).pow(smooth(crash(t)))

    /** The incoming track's cutoff: muffled until the crash, fully open by its end. */
    private fun opening(t: Double) = fx.muffleHz * (OPEN_HZ / fx.muffleHz).pow(smooth(crash(t)))

    /** How far through the crash (0 to 1) a point of the overlap is. */
    private fun crash(t: Double) = ((t - CRASH_FROM) / (CRASH_TO - CRASH_FROM)).coerceIn(0.0, 1.0)

    // ---- overlap

    private fun overlapFrame(primary: FrameSource): Output {
        val gotA = pullA(primary, aFrame)
        if (!gotA && !aExhausted) return Output.Waiting
        val queued = bQueue.removeFirstOrNull() ?: return Output.Aborted
        decode(decoderB, queued.data, bFrame)
        if (bassSwap) swapBass()

        val t0 = overlapFrame.toDouble() / overlapFrames
        val t1 = (overlapFrame + 1).toDouble() / overlapFrames
        if (fx.muffleExit) lowA.process(aFrame, FRAME, closing(t0), closing(t1))
        if (fx.muffleEntry) lowB.process(bFrame, FRAME, opening(t0), opening(t1))

        val fadeSamples = overlapFrames.toDouble() * FRAME
        var peak = 1.0f
        for (i in 0 until FRAME) {
            val t = ((overlapFrame * FRAME + i) / fadeSamples).coerceIn(0.0, 1.0)
            val gainA = cos(crash(t) * PI / 2)
            val gainB = sin((t / RISE_B).coerceIn(0.0, 1.0) * PI / 2) * (entryGain + (1 - entryGain) * t)
            // both tracks are loud together for a while: keep the combined power from rising above that of one track
            val norm = 1.0 / max(1.0, kotlin.math.sqrt(gainA * gainA + gainB * gainB))
            for (c in 0 until CHANNELS) {
                val mixed = ((aFrame[c][i] * gainA + bFrame[c][i] * gainB) * norm).toFloat()
                aFrame[c][i] = mixed
                peak = max(peak, kotlin.math.abs(mixed))
            }
        }
        if (peak > 1f) for (c in 0 until CHANNELS) for (i in 0 until FRAME) aFrame[c][i] /= peak

        overlapFrame++
        val ended = overlapFrame >= overlapFrames
        if (ended) stage = Stage.POST
        return Output.Frame(encode(aFrame), queued.timecode, overlapEnded = ended)
    }

    /** Takes the lows out of the outgoing track at the swap and puts them into the incoming one (which has none until then). */
    private fun swapBass() {
        val ramp = SWAP_RAMP_MS * RATE / 1000.0
        for ((frame, filter, outgoing) in listOf(Triple(aFrame, highA, true), Triple(bFrame, highB, false))) {
            for (c in 0 until CHANNELS) System.arraycopy(frame[c], 0, withoutBass[c], 0, FRAME)
            filter.process(withoutBass, FRAME)
            for (i in 0 until FRAME) {
                val gone = ((overlapFrame * FRAME + i - swapSample) / ramp + 0.5).coerceIn(0.0, 1.0)
                val removed = (if (outgoing) gone else 1 - gone).toFloat()
                for (c in 0 until CHANNELS) frame[c][i] += removed * (withoutBass[c][i] - frame[c][i])
            }
        }
    }

    // ---- the outgoing track

    /** Fills [target] with 960 samples of the outgoing track, stretched if tempo-matching. False if it could not. */
    private fun pullA(primary: FrameSource, target: Array<FloatArray>): Boolean {
        if (stretcher == null) {
            if (aExhausted) {
                target.forEach { it.fill(0f) }
                return false
            }
            val frame = primary.provide()
            if (frame == null) {
                if (currentFinished || !primary.playing) aExhausted = true
                if (aExhausted) target.forEach { it.fill(0f) }
                return false
            }
            aInput = frame.timecode
            decode(decoderA, frame.data, target)
            return true
        }

        while (stretcher.available < FRAME && !aExhausted) {
            val frame = primary.provide()
            if (frame == null) {
                if (currentFinished || !primary.playing) {
                    aExhausted = true
                    stretcher.flush()
                }
                break
            }
            aInput = frame.timecode
            decode(decoderA, frame.data, chunk)
            stretcher.push(chunk, FRAME)
        }
        val n = stretcher.read(target, FRAME)
        if (n < FRAME) {
            for (c in 0 until CHANNELS) java.util.Arrays.fill(target[c], n, FRAME, 0f)
            return false
        }
        return true
    }

    private fun record(samples: Array<FloatArray>) {
        for (i in 0 until FRAME) history[((historyWritten + i) % history.size).toInt()] = 0.5f * (samples[0][i] + samples[1][i])
        historyWritten += FRAME
    }

    // ---- the incoming track

    private fun fillQueue(standby: AudioPlayer?) {
        if (standby == null) return
        var pulled = 0
        while (bQueue.size < LOOKAHEAD_FRAMES && pulled < MAX_PULL_PER_TICK) {
            bQueue.addLast(standby.provide() ?: break)
            pulled++
        }
    }

    // ---- beat alignment

    /**
     * Measures the beats in what the outgoing track has just played and in the first seconds of the incoming queue, then picks
     * the outgoing beat nearest the exit point as the moment the overlap begins, and trims the incoming queue to start on a beat.
     * If either measurement is not clear, nothing changes: the overlap begins at the exit point with the incoming track at its entry point.
     */
    private fun decide() {
        beatSyncAttempted = true
        val outgoingPeriod = fx.outgoingBeatSeconds
        val incomingPeriod = fx.incomingBeatSeconds
        if (outgoingPeriod == null || incomingPeriod == null) {
            fallback("no tempo to measure against")
            return
        }
        val windowSamples = (ANALYSIS_SECONDS * RATE).toInt()
        if (historyWritten < windowSamples || bQueue.size < (ANALYSIS_SECONDS * RATE / FRAME).toInt()) {
            fallback("not enough audio to measure")
            return
        }

        val outgoing = FloatArray(windowSamples) { history[((historyWritten - windowSamples + it) % history.size).toInt()] }
        val aEstimate = BeatPhaseEstimator.estimate(outgoing, windowSamples, RATE, outgoingPeriod)

        val incoming = FloatArray(windowSamples)
        val analysisDecoder: AudioChunkDecoder = format.createDecoder()
        try {
            var offset = 0
            for (queued in bQueue) {
                if (offset >= windowSamples) break
                decode(analysisDecoder, queued.data, chunk)
                val n = min(FRAME, windowSamples - offset)
                for (i in 0 until n) incoming[offset + i] = 0.5f * (chunk[0][i] + chunk[1][i])
                offset += n
            }
        } finally {
            analysisDecoder.close()
        }
        val bEstimate = BeatPhaseEstimator.estimate(incoming, windowSamples, RATE, incomingPeriod)

        if (aEstimate == null || bEstimate == null ||
            aEstimate.confidence < BeatPhaseEstimator.MIN_CONFIDENCE || bEstimate.confidence < BeatPhaseEstimator.MIN_CONFIDENCE
        ) {
            fallback("beats not clear (outgoing ${aEstimate?.confidence?.format()}, incoming ${bEstimate?.confidence?.format()})")
            return
        }

        val window = windowSamples.toDouble() / RATE
        val aPeriod = aEstimate.periodSeconds
        val lastBeat = aEstimate.phaseSeconds + kotlin.math.floor((window - aEstimate.phaseSeconds) / aPeriod) * aPeriod
        val ago = window - lastBeat
        val untilExit = max(0.0, (exitMs - aInput) / 1000.0 / (stretcher?.speed ?: 1.0))
        val firstAhead = aPeriod - ago
        val k = max(0.0, Math.round((untilExit - firstAhead) / aPeriod).toDouble())
        val beatAhead = firstAhead + k * aPeriod
        framesUntilOverlap = max(1, (beatAhead * RATE / FRAME).roundToInt())

        val margin = fx.marginMs / 1000.0
        val m = Math.round((margin - bEstimate.phaseSeconds) / bEstimate.periodSeconds).toDouble()
        val beatB = bEstimate.phaseSeconds + m * bEstimate.periodSeconds
        trimFrames = max(0, (beatB * RATE / FRAME).roundToInt())
        decided = true

        log.debug(
            "[{}] Beats aligned: outgoing beat {} ms ahead (confidence {}), incoming queue trimmed {} frames (beat at {} ms, confidence {})",
            label, (beatAhead * 1000).roundToInt(), aEstimate.confidence.format(), trimFrames, (beatB * 1000).roundToInt(), bEstimate.confidence.format()
        )
    }

    private fun fallback(reason: String) {
        log.debug("[{}] Beat alignment skipped, using the analysis' grid: {}", label, reason)
    }

    private var beatSyncAttempted = false

    private fun Double.format() = "%.1f".format(this)

    // ---- audio plumbing

    private fun decode(decoder: AudioChunkDecoder, data: ByteArray, target: Array<FloatArray>) {
        shorts.clear()
        decoder.decode(data, shorts)
        for (i in 0 until FRAME) {
            target[0][i] = shorts.get(2 * i) / 32768f
            target[1][i] = shorts.get(2 * i + 1) / 32768f
        }
    }

    private fun encode(samples: Array<FloatArray>): ByteArray {
        encodeIn.clear()
        for (i in 0 until FRAME) {
            encodeIn.put((samples[0][i] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
            encodeIn.put((samples[1][i] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
        }
        encodeIn.flip()
        return encoder.encode(encodeIn)
    }

    override fun close() {
        decoderA.close()
        decoderB.close()
        encoder.close()
        stretcher?.close()
    }
}
