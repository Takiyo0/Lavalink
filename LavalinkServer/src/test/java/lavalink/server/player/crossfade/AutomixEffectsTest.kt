package lavalink.server.player.crossfade

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Automix effects through the real controller, with synthetic songs whose beats and tones are known exactly. */
class AutomixEffectsTest {
    private val rate = 48000
    private val files = mutableListOf<Path>()
    private val manager: AudioPlayerManager = DefaultAudioPlayerManager().also { it.registerSourceManager(LocalAudioSourceManager()) }
    private val slots = SwitchableAudioPlayer(manager.createPlayer())
    private val events = CopyOnWriteArrayList<String>()
    private val started = CopyOnWriteArrayList<String>()
    private lateinit var controller: CrossfadeController

    private val buffer = ByteBuffer.allocate(StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize())
    private val frame = MutableAudioFrame().apply { setBuffer(buffer) }
    private val decoder = StandardAudioDataFormats.DISCORD_OPUS.createDecoder()

    private class Out(val mono: FloatArray, val timecode: Long)

    private fun setUp(fadeMs: Long = 2500, retryDelayMs: Long = 3000) {
        controller = CrossfadeController(manager, slots, CrossfadeController.Settings(true, fadeMs), object : CrossfadeController.Host {
            override fun configureStandby(player: AudioPlayer) {}
            override fun promoted(player: AudioPlayer) {}
            override fun startReserved(track: AudioTrack) {
                started += track.identifier
                slots.playTrack(track)
            }

            override fun endTimeMs(): Long? = null
        }, standbyRetryDelayMs = retryDelayMs)
        slots.addListener(object : AudioEventAdapter() {
            override fun onTrackStart(player: AudioPlayer, track: AudioTrack) { events += "start:${name(track)}" }
            override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
                events += "end:${name(track)}:$endReason"
                controller.onTrackEnded(endReason)
            }
        })
    }

    @AfterEach fun cleanup() {
        if (this::controller.isInitialized) controller.close()
        slots.destroy()
        decoder.close()
        manager.shutdown()
        files.forEach { Files.deleteIfExists(it) }
    }

    private fun name(track: AudioTrack) = Path.of(track.identifier).fileName.toString().substringBefore('-')

    // ---- synthetic songs

    private fun write(prefix: String, samples: FloatArray): AudioTrack {
        val file = Files.createTempFile("$prefix-", ".wav")
        files.add(file)
        val pcm = ByteArray(samples.size * 4)
        for (i in samples.indices) {
            val v = (samples[i].coerceIn(-1f, 1f) * 30000).toInt()
            for (channel in 0..1) {
                pcm[i * 4 + channel * 2] = v.toByte()
                pcm[i * 4 + channel * 2 + 1] = (v shr 8).toByte()
            }
        }
        AudioSystem.write(
            AudioInputStream(ByteArrayInputStream(pcm), AudioFormat(48000f, 16, 2, true, false), samples.size.toLong()),
            AudioFileFormat.Type.WAVE, file.toFile()
        )
        return manager.loadItemSync(file.toString()) as AudioTrack
    }

    /** A song of [seconds] with a burst of [hz] on every beat at [bpm], the first one at [offset]. */
    private fun beats(bpm: Double, offset: Double, seconds: Int, hz: Double, decay: Double, level: Double = 0.7): FloatArray {
        val out = FloatArray(rate * seconds)
        val period = 60.0 / bpm
        var t = offset
        while (t < seconds) {
            val start = (t * rate).toInt()
            for (i in 0 until (0.1 * rate).toInt()) {
                if (start + i >= out.size) break
                val time = i.toDouble() / rate
                out[start + i] += (level * exp(-decay * time) * sin(2 * PI * hz * time)).toFloat()
            }
            t += period
        }
        return out
    }

    private fun tone(hz: Double, seconds: Int, level: Double = 0.4) = FloatArray(rate * seconds) { (level * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun mix(vararg parts: FloatArray) = FloatArray(parts.maxOf { it.size }) { i -> parts.sumOf { if (i < it.size) it[i].toDouble() else 0.0 }.toFloat() }

    // ---- driving it

    private fun pull(): Out? {
        buffer.clear()
        if (!controller.provide(frame, buffer)) return null
        buffer.flip()
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val out = ByteBuffer.allocateDirect(960 * 2 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        decoder.decode(bytes, out)
        return Out(FloatArray(960) { (out.get(it * 2) + out.get(it * 2 + 1)) / 65536f }, controller.lastTimecode)
    }

    private fun pump(timeoutMs: Long = 30_000, until: (List<Out>) -> Boolean): List<Out> {
        val frames = ArrayList<Out>()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!until(frames)) {
            check(System.nanoTime() < deadline) { "timed out; events=$events frames=${frames.size}" }
            pull()?.let { frames += it } ?: Thread.sleep(1)
        }
        return frames
    }

    /** Plays until the transition is complete and the next track has played on for two more seconds. */
    private fun runTransition(): List<Out> {
        pump { controller.info().next != null && slots.standby != null }
        Thread.sleep(600) // the test pulls frames far faster than real time; let the standby buffer
        var committedAt = -1
        return pump { frames ->
            if (committedAt < 0 && events.contains("start:b")) {
                committedAt = frames.size
                startEventAt = frames.size
            }
            committedAt >= 0 && frames.size - committedAt >= 100
        }
    }

    /** How many frames had been played when the start event for the incoming track was seen. */
    private var startEventAt = -1

    private fun reserve(next: AudioTrack, automix: CrossfadeController.Automix): CrossfadeController.Mode {
        val token = (controller.begin() as CrossfadeController.Begin.Token).value
        assertEquals(CrossfadeController.Completion.READY, controller.complete(token, next, automix))
        return controller.info().next!!.mode
    }

    private fun concat(frames: List<Out>) = FloatArray(frames.size * 960).also { all -> frames.forEachIndexed { i, f -> System.arraycopy(f.mono, 0, all, i * 960, 960) } }

    private fun lowpass(x: FloatArray, hz: Double): FloatArray {
        val a = 1 - exp(-2 * PI * hz / rate)
        var y = 0.0
        return FloatArray(x.size) { y += a * (x[it] - y); y.toFloat() }
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return sqrt(s / (to - from))
    }

    /** Magnitude of one frequency in a stretch of audio. */
    private fun magnitude(x: FloatArray, from: Int, to: Int, hz: Double): Double {
        var re = 0.0
        var im = 0.0
        for (i in from until to) {
            val angle = 2 * PI * hz * (i - from) / rate
            re += x[i] * kotlin.math.cos(angle)
            im -= x[i] * sin(angle)
        }
        return sqrt(re * re + im * im) / (to - from)
    }

    /** Index of the first frame of the overlap: the output's timecode switches from the outgoing track to the incoming one. */
    private fun overlapStart(frames: List<Out>): Int = (1 until frames.size).first { frames[it].timecode < frames[it - 1].timecode - 1000 }

    // ---- tests

    @Test fun `exit muffle leaves the lead-in alone and closes the outgoing tracks low pass during the overlap`() {
        setUp()
        slots.playTrack(write("a", tone(6000.0, 30)))
        val mode = reserve(write("b", tone(300.0, 30)), CrossfadeController.Automix(exitMs = 14000, effects = CrossfadeController.Effects(muffleExit = true, leadMs = 6000)))
        assertEquals(CrossfadeController.Mode.AUTOMIX, mode)
        assertTrue(controller.info().next!!.plan!!.fx!!.muffleExit)

        val frames = runTransition()
        val all = concat(frames)
        val start = overlapStart(frames)
        val open = magnitude(all, 960 * 20, 960 * 50, 6000.0)
        val endOfLead = magnitude(all, (start - 25) * 960, (start - 5) * 960, 6000.0)
        assertTrue(open > 0.1, "the high tone plays at the start: $open")
        assertTrue(endOfLead > open * 0.9, "the lead-in is not muffled: $endOfLead vs $open")

        // 125 frames of overlap: the low-pass closes during the crash, which ends at 85% of it (frame 106), so 6 kHz is gone from there on
        val closed = magnitude(all, (start + 106) * 960, (start + 120) * 960, 6000.0)
        assertTrue(closed < open * 0.03, "the muffle should have removed the 6 kHz tone: $closed vs $open")
    }

    @Test fun `entry muffle keeps the incoming track dull at first and opens it over the last part of the overlap`() {
        setUp(3000)
        slots.playTrack(write("a", tone(300.0, 30)))
        reserve(write("b", tone(6000.0, 30)), CrossfadeController.Automix(exitMs = 14000, effects = CrossfadeController.Effects(muffleEntry = true, leadMs = 3000)))

        val frames = runTransition()
        val all = concat(frames)
        val start = overlapStart(frames)
        val total = 150 // 3000 ms
        // by 30% of the overlap the incoming track is already audible (its gain is 0.45) but still muffled to 700 Hz
        val early = magnitude(all, (start + total * 25 / 100) * 960, (start + total * 35 / 100) * 960, 6000.0)
        val end = magnitude(all, (start + total - 12) * 960, (start + total - 2) * 960, 6000.0)
        assertTrue(end > 0.15, "fully open by the end: $end")
        assertTrue(early < end * 0.03, "still dull early in the overlap: early=$early end=$end")
    }

    @Test fun `both sweeps are one crash and when the outgoing track is gone the incoming one is already open`() {
        setUp(4000) // 200 frames of overlap; the crash runs from 35% to 85% of it, frames 70 to 170
        slots.playTrack(write("a", tone(440.0, 30)))
        reserve(write("b", tone(6000.0, 30)), CrossfadeController.Automix(exitMs = 14000, effects = CrossfadeController.Effects(muffleExit = true, muffleEntry = true, leadMs = 3000)))

        val frames = runTransition()
        val all = concat(frames)
        val start = overlapStart(frames)
        fun level(from: Int, to: Int, hz: Double) = magnitude(all, (start + from) * 960, (start + to) * 960, hz)

        val aFull = level(-30, -5, 440.0)       // the outgoing track on its own, before the overlap
        val bFull = level(215, 240, 6000.0)     // the incoming track on its own, after it
        assertTrue(aFull > 0.1 && bFull > 0.1, "reference levels: a=$aFull b=$bFull")

        // before the crash: the outgoing track is untouched and the incoming one is dull (6 kHz is far above its 700 Hz cutoff)
        assertTrue(level(40, 60, 440.0) > aFull * 0.6, "the outgoing track is still there until the crash (only the power normalisation lowers it)")
        assertTrue(level(40, 60, 6000.0) < bFull * 0.05, "the incoming track is dull before the crash")

        // at the end of the crash the outgoing track is gone and the incoming one is already fully open
        assertTrue(level(172, 195, 440.0) < aFull * 0.02, "the outgoing track is gone when the crash ends")
        assertTrue(level(172, 195, 6000.0) > bFull * 0.85, "the incoming track is open when the crash ends")

        // and it stays gone: nothing of the outgoing track is heard once the overlap is over
        assertTrue(level(205, 295, 440.0) < aFull * 0.005, "the outgoing track is still audible after the overlap: ${level(205, 295, 440.0)}")
        assertTrue(level(205, 295, 6000.0) > bFull * 0.9, "the incoming track plays on at full level")
    }

    @Test fun `the lows change hands at one point in the middle of the overlap`() {
        setUp(4000) // 200 frames; the swap is at 60%, frame 120
        slots.playTrack(write("a", tone(80.0, 30)))
        reserve(write("b", tone(100.0, 30)), CrossfadeController.Automix(exitMs = 14000, effects = CrossfadeController.Effects(muffleExit = true, muffleEntry = true, leadMs = 3000)))

        val frames = runTransition()
        val all = concat(frames)
        val start = overlapStart(frames)
        fun level(from: Int, to: Int, hz: Double) = magnitude(all, (start + from) * 960, (start + to) * 960, hz)
        val aFull = level(-30, -5, 80.0)
        val bFull = level(215, 240, 100.0)
        assertTrue(aFull > 0.1 && bFull > 0.1, "reference levels: a=$aFull b=$bFull")

        // before the swap only the outgoing track has bass
        assertTrue(level(40, 60, 80.0) > aFull * 0.3, "the outgoing bass plays before the swap")
        assertTrue(level(40, 60, 100.0) < bFull * 0.15, "the incoming bass is held back before the swap: ${level(40, 60, 100.0)} vs $bFull")
        // after it only the incoming track has
        assertTrue(level(135, 150, 80.0) < aFull * 0.1, "the outgoing bass is gone after the swap: ${level(135, 150, 80.0)} vs $aFull")
        assertTrue(level(135, 150, 100.0) > bFull * 0.6, "the incoming bass has come in after the swap")
    }

    @Test fun `a transition that begins while the last one is still being replayed plays the replay first and leaves nothing behind`() {
        setUp(2000)
        slots.playTrack(write("a", tone(440.0, 30)))
        reserve(write("b", tone(300.0, 40)), CrossfadeController.Automix(exitMs = 14000, effects = CrossfadeController.Effects(muffleExit = true, muffleEntry = true, leadMs = 3000)))
        val first = runTransition()

        // the incoming track's own exit is a second or so away: its lead-in begins inside the replay of the frames read ahead of it
        reserve(write("c", tone(600.0, 40)), CrossfadeController.Automix(exitMs = 8000, effects = CrossfadeController.Effects(muffleExit = true, muffleEntry = true, leadMs = 3000)))
        val gap = pump { slots.standby != null }
        Thread.sleep(600)
        var at = -1
        val second = pump { f ->
            if (at < 0 && events.contains("start:c")) at = f.size
            at >= 0 && f.size - at >= 100
        }

        // b's audio is played in order up to the point where c's overlap starts: no frames are skipped
        val b = first.drop(startEventAt) + gap + second
        val cStart = (1 until b.size).first { b[it].timecode < b[it - 1].timecode - 1000 }
        for (i in 1 until cStart) assertTrue(b[i].timecode - b[i - 1].timecode < 400, "b jumped from ${b[i - 1].timecode} to ${b[i].timecode} ms")

        // and nothing of b is left to play over c once c has taken over
        val all = concat(second)
        val end = all.size - 60 * 960
        val c600 = magnitude(all, end, all.size, 600.0)
        val b300 = magnitude(all, end, all.size, 300.0)
        assertTrue(c600 > 0.1, "c plays: $c600")
        assertTrue(b300 < c600 * 0.05, "b is still audible over c: $b300 vs $c600")
    }

    @Test fun `tempo match stretches the outgoing track toward the next tempo without changing its pitch`() {
        setUp()
        // 120 BPM song going into a 126 BPM song (ratio 1.05); a steady 440 Hz tone rides along to check pitch
        slots.playTrack(write("a", mix(beats(120.0, 0.0, 30, 55.0, 40.0), tone(440.0, 30, 0.15))))
        reserve(
            write("b", beats(126.0, 0.0, 30, 55.0, 40.0)),
            CrossfadeController.Automix(exitMs = 14000, effects = CrossfadeController.Effects(currentBpm = 120.0, nextBpm = 126.0, tempoMatch = true, leadMs = 8000))
        )
        assertEquals(1.05, controller.info().next!!.plan!!.fx!!.tempoRatio!!, 1e-9)

        val frames = runTransition()
        val start = overlapStart(frames)
        // the lead-in covers 8 s of the song; stretched toward 1.05x it takes fewer output frames than the 400 natural ones
        val leadFrames = frames.take(start).count { it.timecode >= 6000 }
        assertTrue(leadFrames in 370..395, "lead-in frames $leadFrames (natural would be 400)")

        // pitch is preserved: keep only the 200 to 700 Hz band, where the 440 Hz tone is, and count its cycles near the end of the lead-in
        val all = concat(frames)
        val low200 = lowpass(all, 200.0)
        val band = lowpass(FloatArray(all.size) { all[it] - low200[it] }, 700.0)
        val from = (start - 40) * 960
        val to = (start - 2) * 960
        var crossings = 0
        for (i in from + 1 until to) if (band[i - 1] < 0 && band[i] >= 0) crossings++
        assertEquals(440.0, crossings / ((to - from).toDouble() / rate), 25.0, "pitch near the end of the lead-in")
    }

    /** How far apart (in seconds, within one beat) the outgoing song's kicks and the incoming song's ticks fall during the overlap. */
    private fun beatOffsetInOverlap(beatSync: Boolean): Double {
        // the analysis grid says both songs have beats on the cue points, but the real beats are 130 ms and 310 ms after them
        setUp()
        slots.playTrack(write("a", beats(120.0, 0.13, 30, 55.0, 40.0)))   // kicks
        reserve(
            write("b", beats(120.0, 0.31, 30, 3000.0, 150.0)),            // ticks
            CrossfadeController.Automix(
                exitMs = 14000, entryMs = 4000,
                // a muffle at 8 kHz leaves the kicks and ticks alone, but puts both runs through the same engine: only the alignment differs
                effects = CrossfadeController.Effects(muffleExit = true, muffleHz = 8000, currentBpm = 120.0, nextBpm = 120.0, beatSync = beatSync, leadMs = 8000)
            )
        )
        val frames = runTransition()
        val all = concat(frames)
        val start = overlapStart(frames)
        val from = start * 960
        val to = from + 125 * 960 // 2.5 s of overlap
        assertTrue(to <= all.size, "overlap at frame $start of ${frames.size}; events=$events; timecodes=${frames.map { it.timecode }.filterIndexed { i, _ -> i % 40 == 0 }}")
        val kicks = lowpass(all, 200.0)
        val lowTicks = lowpass(all, 2000.0)
        val ticks = FloatArray(all.size) { all[it] - lowTicks[it] }
        val a = BeatPhaseEstimator.estimate(kicks.copyOfRange(from, to), to - from, rate, 0.5)!!
        val b = BeatPhaseEstimator.estimate(ticks.copyOfRange(from, to), to - from, rate, 0.5)!!
        return abs(((a.phaseSeconds - b.phaseSeconds) + 0.25).mod(0.5) - 0.25)
    }

    @Test fun `without beat sync the two songs beats are as far apart as the analysis grid is wrong`() {
        val difference = beatOffsetInOverlap(beatSync = false)
        assertTrue(difference > 0.1, "the beats should be well apart (about 180 ms), was $difference s")
    }

    @Test fun `beat sync lines the beats of both songs up in the overlap even when the cue grid is off`() {
        val difference = beatOffsetInOverlap(beatSync = true)
        assertTrue(difference < 0.04, "the beats should line up within 40 ms, were $difference s apart")
    }

    @Test fun `the incoming track continues without a gap or repeat after the overlap and events are sent once`() {
        setUp()
        slots.playTrack(write("a", beats(120.0, 0.13, 30, 55.0, 40.0)))
        reserve(
            write("b", beats(120.0, 0.31, 30, 3000.0, 150.0)),
            CrossfadeController.Automix(exitMs = 14000, entryMs = 4000, effects = CrossfadeController.Effects(currentBpm = 120.0, nextBpm = 120.0, beatSync = true, muffleExit = true, muffleEntry = true, leadMs = 8000))
        )
        val frames = runTransition()

        assertEquals(listOf("start:a", "end:a:FINISHED", "start:b"), events.toList())
        assertEquals(0, started.size, "an automix transition does not use the normal start path")
        val start = overlapStart(frames)
        // every frame from the overlap on follows the previous one by exactly 20 ms: the read-ahead frames come back in order
        // and the standby takes over from the last of them
        for (i in start + 1 until frames.size) {
            val step = frames[i].timecode - frames[i - 1].timecode
            assertEquals(20L, step, "frame $i (${frames[i].timecode} ms) follows ${frames[i - 1].timecode} ms")
        }
        assertEquals("b", name(slots.playingTrack!!))
        assertNull(slots.standby)
    }

    @Test fun `effects that do not fit are shortened or dropped but the cues still apply`() {
        setUp()
        slots.playTrack(write("a", tone(440.0, 30)))
        // the exit is 5 s away: the 6 s lead-in asked for cannot fit with the 2 s margin, so it is shortened to the 3 s that do
        assertEquals(CrossfadeController.Mode.AUTOMIX, reserve(write("b", tone(880.0, 30)), CrossfadeController.Automix(exitMs = 5000, effects = CrossfadeController.Effects(muffleExit = true, leadMs = 6000))))
        assertEquals(3000L, controller.info().next!!.plan!!.fx!!.leadMs)

        // with the exit only 2.5 s away there is less than a second of lead-in: the effects are dropped, the cues stay
        assertEquals(CrossfadeController.Mode.AUTOMIX, reserve(write("c", tone(880.0, 30)), CrossfadeController.Automix(exitMs = 2500, effects = CrossfadeController.Effects(muffleExit = true))))
        assertNull(controller.info().next!!.plan!!.fx, "the effects were dropped")
        assertEquals(2500L, controller.info().next!!.plan!!.exitMs)

        // 100 into 118 BPM is 18% apart and not near any simple relation: not matched, but a muffle that was also asked for still applies
        assertEquals(CrossfadeController.Mode.AUTOMIX, reserve(write("d", tone(880.0, 30)), CrossfadeController.Automix(exitMs = 20000, effects = CrossfadeController.Effects(muffleExit = true, currentBpm = 100.0, nextBpm = 118.0, tempoMatch = true))))
        val fx = controller.info().next!!.plan!!.fx!!
        assertNull(fx.tempoRatio)
        assertTrue(fx.muffleExit)

        // beat sync needs a long enough lead-in to measure in; without it the transition still muffles
        assertEquals(CrossfadeController.Mode.AUTOMIX, reserve(write("e", tone(880.0, 30)), CrossfadeController.Automix(exitMs = 9000, effects = CrossfadeController.Effects(muffleExit = true, beatSync = true, currentBpm = 120.0, nextBpm = 120.0))))
        assertTrue(controller.info().next!!.plan!!.fx!!.beatSync, "7 s of lead-in is plenty without a tempo ramp")
        assertEquals(CrossfadeController.Mode.AUTOMIX, reserve(write("f", tone(880.0, 30)), CrossfadeController.Automix(exitMs = 6000, effects = CrossfadeController.Effects(muffleExit = true, beatSync = true, currentBpm = 120.0, nextBpm = 120.0))))
        val short = controller.info().next!!.plan!!.fx!!
        assertFalse(short.beatSync, "4 s is too little to measure beats in")
        assertEquals(4000L, short.leadMs)
    }

    @Test fun `pausing during the lead-in restores the outgoing track and keeps the next one for a normal start`() {
        setUp()
        slots.playTrack(write("a", tone(440.0, 30)))
        reserve(write("b", tone(880.0, 30)), CrossfadeController.Automix(exitMs = 14000, effects = CrossfadeController.Effects(muffleExit = true, leadMs = 6000)))
        pump { slots.standby != null }
        Thread.sleep(600)
        pump { controller.info().fading }
        controller.onPause()
        slots.isPaused = true
        assertFalse(controller.info().fading)
        assertNull(slots.standby)
        assertFalse(controller.info().next!!.willFade)
        slots.isPaused = false
        pump { events.contains("start:b") }
        assertEquals(1, started.size)
    }

    @Test fun `a next track that fails to load is retried and then the fade is skipped before the lead-in starts`() {
        setUp(retryDelayMs = 30)
        slots.playTrack(write("a", tone(440.0, 40)))
        reserve(write("b", tone(880.0, 40)), CrossfadeController.Automix(exitMs = 30000, effects = CrossfadeController.Effects(muffleExit = true, leadMs = 6000)))
        Files.deleteIfExists(files.last()) // the next track's source goes away: every attempt to load it fails, like a refused stream URL

        // pull slowly so the failures and the retries happen well before the lead-in is due
        pump(30_000) { Thread.sleep(2); controller.info().next?.mode == CrossfadeController.Mode.NORMAL }

        assertFalse(controller.info().fading, "the outgoing track must not have been processed for a transition that cannot happen")
        assertNull(slots.standby)
        assertEquals(listOf("start:a"), events.toList())
        assertNotNull(controller.info().next, "the reservation survives for a normal start")
    }

    @Test fun `seeking re-plans the transition and a fade that was skipped by a seek comes back when seeking out of the window`() {
        setUp()
        slots.playTrack(write("a", tone(440.0, 40)))
        reserve(write("b", tone(880.0, 40)), CrossfadeController.Automix(exitMs = 30000, effects = CrossfadeController.Effects(muffleExit = true, leadMs = 6000)))
        assertEquals(6000L, controller.info().next!!.plan!!.fx!!.leadMs)

        fun seekTo(ms: Long): CrossfadeController.Next {
            controller.onSeek(ms)
            slots.playingTrack!!.position = ms
            pull() ?: pull()               // the next audio tick re-checks the plan
            return controller.info().next!!
        }

        // 3 s before the exit only 1 s of lead-in fits (the node needs 2 s of margin): shortened, not skipped
        val close = seekTo(27000)
        assertEquals(CrossfadeController.Mode.AUTOMIX, close.mode)
        assertEquals(1000L, close.plan!!.fx!!.leadMs)

        // 1 s before the exit there is no room for the automix at all: the ordinary crossfade is what is left
        val tooLate = seekTo(29000)
        assertEquals(CrossfadeController.Mode.CROSSFADE, tooLate.mode)
        assertNull(tooLate.plan!!.exitMs)

        // seeking back out of the window brings the full plan back
        val back = seekTo(5000)
        assertEquals(CrossfadeController.Mode.AUTOMIX, back.mode)
        assertEquals(6000L, back.plan!!.fx!!.leadMs)
        assertEquals(30000L, back.plan!!.exitMs)

        // and the transition then runs as planned
        val frames = runTransition()
        assertEquals(listOf("start:a", "end:a:FINISHED", "start:b"), events.toList())
        assertTrue(frames.isNotEmpty())
    }

    @Test fun `a fade the user cancelled by seeking during the overlap is not brought back by a later seek`() {
        setUp()
        slots.playTrack(write("a", tone(440.0, 40)))
        reserve(write("b", tone(880.0, 40)), CrossfadeController.Automix(exitMs = 14000, effects = CrossfadeController.Effects(muffleExit = true, leadMs = 6000)))
        pump { slots.standby != null }
        Thread.sleep(600)
        pump { controller.info().fading }
        controller.onSeek()
        slots.playingTrack!!.position = 1000
        pull() ?: pull()
        assertFalse(controller.info().fading)
        assertFalse(controller.info().next!!.willFade, "the user cancelled it: it stays a normal transition")

        controller.onSeek()
        slots.playingTrack!!.position = 500
        pull() ?: pull()
        assertFalse(controller.info().next!!.willFade, "seeking again must not undo that")
    }

    @Test fun `tempos in a 3 to 2 relation need only a small stretch`() {
        // 170 into 110 BPM: 110 x 1.5 = 165, so the outgoing track is stretched 3% to 165 BPM, not 35% down to 110
        assertEquals(165.0 / 170.0, nearestTempoRatio(110.0 / 170.0), 1e-9)
        assertEquals(1.0, nearestTempoRatio(1.0), 1e-9)
        assertEquals(1.0, nearestTempoRatio(2.0), 1e-9)     // double time
        assertEquals(1.0, nearestTempoRatio(0.5), 1e-9)     // half time
        assertEquals(0.75 * 1.2, nearestTempoRatio(1.2), 1e-9) // 120 into 144: 144 x 3/4 = 108, 10% from 120; a 4 to 3 relation
        assertEquals(1.05, nearestTempoRatio(1.05), 1e-9)

        setUp()
        slots.playTrack(write("a", tone(440.0, 40)))
        reserve(
            write("b", tone(880.0, 40)),
            CrossfadeController.Automix(exitMs = 30000, effects = CrossfadeController.Effects(currentBpm = 170.0, nextBpm = 110.0, tempoMatch = true, beatSync = true, leadMs = 8000))
        )
        val fx = controller.info().next!!.plan!!.fx!!
        assertEquals(165.0 / 170.0, fx.tempoRatio!!, 1e-9)
        assertEquals(60.0 / 165.0, fx.outgoingBeatSeconds!!, 1e-9)  // the outgoing track's beat as it will be heard
        assertEquals(60.0 / 110.0, fx.incomingBeatSeconds!!, 1e-9)
        assertTrue(fx.beatSync)
    }

    @Test fun `beats line up when the two songs are in a 3 to 2 relation`() {
        // 150 against 100 BPM: three kicks of the first fit two ticks of the second. The overlap should start with both on a beat.
        setUp()
        slots.playTrack(write("a", beats(150.0, 0.13, 30, 55.0, 40.0)))
        reserve(
            write("b", beats(100.0, 0.31, 30, 3000.0, 150.0)),
            CrossfadeController.Automix(
                exitMs = 14000, entryMs = 4000,
                effects = CrossfadeController.Effects(muffleExit = true, muffleHz = 8000, currentBpm = 150.0, nextBpm = 100.0, tempoMatch = true, beatSync = true, leadMs = 8000)
            )
        )
        assertNull(controller.info().next!!.plan!!.fx!!.tempoRatio, "150 is already 1.5 x 100: no stretch needed")
        val frames = runTransition()
        val all = concat(frames)
        val from = overlapStart(frames) * 960
        val to = from + 125 * 960
        val kicks = lowpass(all, 200.0)
        val lowTicks = lowpass(all, 2000.0)
        val ticks = FloatArray(all.size) { all[it] - lowTicks[it] }
        val a = BeatPhaseEstimator.estimate(kicks.copyOfRange(from, to), to - from, rate, 0.4)!!
        val b = BeatPhaseEstimator.estimate(ticks.copyOfRange(from, to), to - from, rate, 0.6)!!
        fun distanceToABeat(phase: Double, period: Double) = minOf(phase, period - phase)
        assertTrue(distanceToABeat(a.phaseSeconds, a.periodSeconds) < 0.04, "the outgoing track should be on a beat as the overlap starts, was ${a.phaseSeconds}")
        assertTrue(distanceToABeat(b.phaseSeconds, b.periodSeconds) < 0.04, "the incoming track should start on a beat, was ${b.phaseSeconds}")
    }

    @Test fun `both songs are audible together while the outgoing one muffles and the events come as the overlap ends`() {
        setUp(4000) // 200 frames of overlap
        slots.playTrack(write("a", tone(440.0, 30)))
        reserve(write("b", tone(300.0, 30)), CrossfadeController.Automix(exitMs = 14000, effects = CrossfadeController.Effects(muffleExit = true, muffleEntry = true, leadMs = 3000)))

        val frames = runTransition()
        val all = concat(frames)
        val start = overlapStart(frames)
        val alone = magnitude(all, 960 * 20, 960 * 50, 440.0)

        // about 30% into the overlap: the outgoing song has held its level while its low-pass closes, and the incoming song has already
        // come in (muffled). Both are clearly there, not one fading in from silence after the other has gone.
        val a = magnitude(all, (start + 50) * 960, (start + 70) * 960, 440.0)
        val b = magnitude(all, (start + 50) * 960, (start + 70) * 960, 300.0)
        assertTrue(a > alone * 0.5, "the outgoing song should still be audible: $a vs $alone")
        assertTrue(b > alone * 0.5, "the incoming song should already be audible: $b vs $alone")

        // the end and start events are sent as the overlap ends, not after the read-ahead frames have been replayed
        assertTrue(startEventAt - start in 195..215, "start event after ${startEventAt - start} frames of an overlap of 200")
    }
}

class SwitchableAudioPlayerTest {
    private val manager: AudioPlayerManager = DefaultAudioPlayerManager().also { it.registerSourceManager(LocalAudioSourceManager()) }
    private val slots = SwitchableAudioPlayer(manager.createPlayer())
    private val events = CopyOnWriteArrayList<String>()
    private val files = mutableListOf<Path>()

    @AfterEach fun cleanup() {
        slots.destroy()
        manager.shutdown()
        files.forEach { Files.deleteIfExists(it) }
    }

    private fun shortTrack(): AudioTrack {
        val file = Files.createTempFile("short-", ".wav")
        files.add(file)
        val pcm = ByteArray(48000 * 4) // one second of silence
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(pcm), AudioFormat(48000f, 16, 2, true, false), 48000L), AudioFileFormat.Type.WAVE, file.toFile())
        return manager.loadItemSync(file.toString()) as AudioTrack
    }

    private fun playToTheEnd() {
        slots.playTrack(shortTrack())
        val deadline = System.nanoTime() + 15_000_000_000L
        while (slots.playingTrack != null) {
            check(System.nanoTime() < deadline) { "the track never ended" }
            slots.provide() ?: Thread.sleep(1)
        }
    }

    @Test fun `a natural end is held back while asked to and delivered when released`() {
        slots.addListener(object : AudioEventAdapter() {
            override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) { events += "end:$endReason" }
        })

        slots.deferFinishedEnd = true
        playToTheEnd()
        assertTrue(events.isEmpty(), "the end must not be delivered while it is held back: $events")
        val held = slots.takeDeferredEnd()
        assertNotNull(held)
        assertEquals(AudioTrackEndReason.FINISHED, held!!.endReason)
        assertNull(slots.takeDeferredEnd(), "it can be taken once")

        slots.dispatch(held)
        assertEquals(listOf("end:FINISHED"), events.toList())

        // and nothing is held back when not asked
        events.clear()
        slots.deferFinishedEnd = false
        playToTheEnd()
        assertEquals(listOf("end:FINISHED"), events.toList())
        assertNull(slots.takeDeferredEnd())
    }
}
