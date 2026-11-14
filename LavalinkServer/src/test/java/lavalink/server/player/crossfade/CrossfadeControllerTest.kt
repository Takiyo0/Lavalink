package lavalink.server.player.crossfade

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Drives the controller the way the voice sender does, using real local audio and no Discord connection. */
class CrossfadeControllerTest {
    private val files = mutableListOf<Path>()
    private val manager: AudioPlayerManager = DefaultAudioPlayerManager().also { it.registerSourceManager(LocalAudioSourceManager()) }
    private val slots = SwitchableAudioPlayer(manager.createPlayer())
    private val events = CopyOnWriteArrayList<String>()
    private val started = CopyOnWriteArrayList<String>()
    private var enabled = true
    private var markerStop = false
    private lateinit var controller: CrossfadeController

    private val buffer = ByteBuffer.allocate(StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize())
    private val frame = MutableAudioFrame().apply { setBuffer(buffer) }
    private val decoder = StandardAudioDataFormats.DISCORD_OPUS.createDecoder()

    private fun setUp(fadeMs: Long = 500) {
        controller = CrossfadeController(manager, slots, CrossfadeController.Settings(enabled, fadeMs), object : CrossfadeController.Host {
            override fun configureStandby(player: AudioPlayer) {}
            override fun promoted(player: AudioPlayer) {}
            override fun startReserved(track: AudioTrack) {
                started += track.identifier
                slots.playTrack(track)
            }
            override fun endTimeMs(): Long? = null
        })
        slots.addListener(object : AudioEventAdapter() {
            override fun onTrackStart(player: AudioPlayer, track: AudioTrack) { events += "start:${name(track)}" }
            override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
                // An end-time marker stops the track but reports it as finished, like EventEmitter does.
                val reason = if (markerStop && endReason == AudioTrackEndReason.STOPPED) AudioTrackEndReason.FINISHED else endReason
                events += "end:${name(track)}:$reason"
                controller.onTrackEnded(reason)
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

    private fun wav(prefix: String, hz: Double, seconds: Int): AudioTrack {
        val file = Files.createTempFile("$prefix-", ".wav"); files.add(file)
        val frames = 48000 * seconds
        val pcm = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val sample = (10000 * sin(2 * PI * hz * i / 48000)).toInt()
            for (channel in 0..1) {
                pcm[i * 4 + channel * 2] = sample.toByte()
                pcm[i * 4 + channel * 2 + 1] = (sample shr 8).toByte()
            }
        }
        AudioSystem.write(
            AudioInputStream(ByteArrayInputStream(pcm), AudioFormat(48000f, 16, 2, true, false), frames.toLong()),
            AudioFileFormat.Type.WAVE, file.toFile()
        )
        return manager.loadItemSync(file.toString()) as AudioTrack
    }

    private fun reserve(next: AudioTrack, automix: CrossfadeController.Automix = CrossfadeController.Automix.NONE): Boolean {
        val token = (controller.begin() as CrossfadeController.Begin.Token).value
        assertEquals(CrossfadeController.Completion.READY, controller.complete(token, next, automix))
        return controller.info().next!!.willFade
    }

    private fun mode() = controller.info().next!!.mode

    /** A track that plays [hz1] until [switchMs] and [hz2] afterwards, to tell where playback began. */
    private fun wav2(prefix: String, hz1: Double, hz2: Double, switchMs: Int, seconds: Int): AudioTrack {
        val file = Files.createTempFile("$prefix-", ".wav"); files.add(file)
        val frames = 48000 * seconds
        val pcm = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val hz = if (i < 48L * switchMs) hz1 else hz2
            val sample = (10000 * sin(2 * PI * hz * i / 48000)).toInt()
            for (channel in 0..1) {
                pcm[i * 4 + channel * 2] = sample.toByte()
                pcm[i * 4 + channel * 2 + 1] = (sample shr 8).toByte()
            }
        }
        AudioSystem.write(
            AudioInputStream(ByteArrayInputStream(pcm), AudioFormat(48000f, 16, 2, true, false), frames.toLong()),
            AudioFileFormat.Type.WAVE, file.toFile()
        )
        return manager.loadItemSync(file.toString()) as AudioTrack
    }

    /** Pulls one frame; returns the left channel of the decoded PCM, or null if nothing was available. */
    private fun pull(): ShortArray? {
        buffer.clear()
        if (!controller.provide(frame, buffer)) return null
        buffer.flip()
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val out = ByteBuffer.allocateDirect(960 * 2 * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        decoder.decode(bytes, out)
        return ShortArray(960) { out.get(it * 2) }
    }

    private fun pump(timeoutMs: Long = 20_000, until: () -> Boolean): List<ShortArray> {
        val frames = ArrayList<ShortArray>()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!until()) {
            check(System.nanoTime() < deadline) { "timed out; events=$events" }
            pull()?.let { frames += it } ?: Thread.sleep(1)
        }
        return frames
    }

    private fun magnitude(samples: ShortArray, hz: Double): Double {
        var re = 0.0
        var im = 0.0
        for (i in samples.indices) {
            val angle = 2 * PI * hz * i / 48000
            re += samples[i] * cos(angle)
            im -= samples[i] * sin(angle)
        }
        return sqrt(re * re + im * im) / samples.size
    }

    @Test fun `fade overlaps both tracks and emits one end and start`() {
        setUp()
        val a = wav("a", 440.0, 4)
        val b = wav("b", 880.0, 4)
        slots.playTrack(a)
        assertTrue(reserve(b))
        val warm = pump { slots.standby != null }
        Thread.sleep(400) // the test pulls frames far faster than real time: let the standby load before the fade is due
        val frames = warm + pump { events.contains("end:b:FINISHED") }
        pump(200) { true } // nothing more to drain

        assertEquals(listOf("start:a", "end:a:FINISHED", "start:b", "end:b:FINISHED"), events.toList())
        assertEquals(1, events.count { it.startsWith("start:b") })
        assertEquals(0, started.size, "a faded transition must not use the normal start path")
        // 3 s of A, a 0.5 s overlap that ends 0.5 s before A's end, and 3.5 s of B at 20 ms per frame.
        assertTrue(frames.size in 340..360, "unexpected frame count ${frames.size}")

        val ratios = frames.map { magnitude(it, 880.0) / (magnitude(it, 440.0) + magnitude(it, 880.0) + 1e-9) }
        assertTrue(ratios.first() < 0.1, "should start as A")
        assertTrue(ratios.last() > 0.9, "should end as B")
        assertTrue(ratios.any { it in 0.35..0.65 }, "no frame mixed both tracks evenly")
        val peaks = frames.map { f -> f.maxOf { kotlin.math.abs(it.toInt()) } }
        assertTrue(peaks.all { it <= 32767 })
        assertNull(slots.standby)
    }

    @Test fun `disabled fading starts the reserved track normally`() {
        enabled = false
        setUp()
        slots.playTrack(wav("a", 440.0, 4))
        val b = wav("b", 880.0, 3)
        assertFalse(reserve(b))
        pump { events.contains("end:b:FINISHED") }
        assertEquals(listOf("start:a", "end:a:FINISHED", "start:b", "end:b:FINISHED"), events.toList())
        assertEquals(1, started.size)
        assertNull(controller.info().next)
    }

    @Test fun `short next track falls back to a normal transition`() {
        setUp(1000)
        slots.playTrack(wav("a", 440.0, 6))
        assertFalse(reserve(wav("b", 880.0, 2))) // needs fade + 2 s = 3 s
        assertTrue(reserve(wav("c", 880.0, 3)))
    }

    @Test fun `pause during overlap restores current track and keeps next for a normal start`() {
        setUp()
        slots.playTrack(wav("a", 440.0, 4))
        assertTrue(reserve(wav("b", 880.0, 4)))
        pump { controller.info().fading }
        pump(5000) { slots.current.playingTrack!!.position > 3200 || !controller.info().fading }
        controller.onPause()
        slots.isPaused = true
        assertFalse(controller.info().fading)
        assertNull(slots.standby)
        assertFalse(controller.info().next!!.willFade)
        slots.isPaused = false

        pump(5000) { events.contains("start:b") }
        assertEquals(listOf("start:a", "end:a:FINISHED", "start:b"), events.toList())
        assertEquals(1, started.size, "b must start through the normal path")
        pull() // first frame of the normal transition
        assertTrue(slots.playingTrack!!.position < 200, "b must start from its beginning")
    }

    @Test fun `seek during overlap cancels it`() {
        setUp()
        slots.playTrack(wav("a", 440.0, 5))
        assertTrue(reserve(wav("b", 880.0, 4)))
        pump { controller.info().fading }
        controller.onSeek()
        slots.playingTrack!!.position = 500
        assertFalse(controller.info().fading)
        assertNull(slots.standby)
        pump { events.contains("start:b") }
        assertEquals(1, started.size)
        assertEquals(listOf("start:a", "end:a:FINISHED", "start:b"), events.toList())
    }

    @Test fun `current track stopping during the overlap cancels it without advancing twice`() {
        setUp()
        slots.playTrack(wav("a", 440.0, 4))
        assertTrue(reserve(wav("b", 880.0, 4)))
        pump { controller.info().fading }
        slots.stopTrack()
        assertFalse(controller.info().fading)
        assertNull(slots.standby)
        assertEquals("end:a:STOPPED", events.last())
        assertFalse(events.contains("start:b"))
        assertNotNull(controller.info().next, "the reservation survives for the caller to resolve")
    }

    @Test fun `clear and explicit replacement take precedence over a prepared standby`() {
        setUp()
        slots.playTrack(wav("a", 440.0, 8))
        assertTrue(reserve(wav("b", 880.0, 4)))
        pump(10_000) { slots.standby != null }
        controller.clearReservation()
        assertNull(slots.standby)
        assertNull(controller.info().next)
        pump(10_000) { events.contains("end:a:FINISHED") }
        assertFalse(events.any { it.startsWith("start:b") })
    }

    @Test fun `stale load completion is rejected and failed load releases the reservation`() {
        setUp()
        slots.playTrack(wav("a", 440.0, 4))
        val first = (controller.begin() as CrossfadeController.Begin.Token).value
        val second = (controller.begin() as CrossfadeController.Begin.Token).value
        assertEquals(CrossfadeController.Completion.STALE, controller.complete(first, wav("b", 880.0, 4)))
        controller.fail(second)
        assertNull(controller.info().next)
        assertEquals(CrossfadeController.Begin.NotPlaying::class, controller.also { slots.stopTrack() }.begin()::class)
    }

    @Test fun `settings changes re-evaluate a ready reservation`() {
        enabled = false
        setUp()
        slots.playTrack(wav("a", 440.0, 6))
        assertFalse(reserve(wav("b", 880.0, 6)))
        controller.updateSettings(true, null, changeEnabled = true, changeDuration = false)
        assertTrue(controller.info().next!!.willFade)
        controller.updateSettings(null, null, changeEnabled = true, changeDuration = false)
        assertFalse(controller.info().next!!.willFade, "reset to the disabled server default")
    }

    @Test fun `reserving during the overlap cancels it and the replacement plays normally`() {
        setUp()
        slots.playTrack(wav("a", 440.0, 4))
        assertTrue(reserve(wav("c", 660.0, 4)))
        pump { controller.info().fading }
        val token = (controller.begin() as CrossfadeController.Begin.Token).value
        assertFalse(controller.info().fading)
        assertNull(slots.standby)
        assertNull(controller.info().next, "nothing is reserved until the replacement has loaded")
        assertEquals(CrossfadeController.Completion.READY, controller.complete(token, wav("d", 880.0, 4)))
        assertFalse(controller.info().next!!.willFade, "too little time is left to fade again")
        pump { events.contains("start:d") }
        assertEquals(listOf("start:a", "end:a:FINISHED", "start:d"), events.toList())
        assertEquals(1, started.size)
    }

    @Test fun `failed replacement leaves nothing reserved and nothing plays next`() {
        setUp()
        slots.playTrack(wav("a", 440.0, 4))
        assertTrue(reserve(wav("b", 880.0, 4)))
        val token = (controller.begin() as CrossfadeController.Begin.Token).value
        controller.fail(token)
        assertNull(controller.info().next)
        pump { events.contains("end:a:FINISHED") }
        repeat(50) { pull(); Thread.sleep(2) }
        assertEquals(listOf("start:a", "end:a:FINISHED"), events.toList())
    }

    @Test fun `current track ending inside its own event dispatch promotes the next track with one start event`() {
        setUp()
        slots.playTrack(wav("a", 440.0, 4))
        assertTrue(reserve(wav("b", 880.0, 4)))
        pump { controller.info().fading }
        markerStop = true
        slots.stopTrack() // the end event is dispatched on a's own listener list, as for a marker or a dry buffer
        assertFalse(controller.info().fading)
        assertEquals(listOf("start:a", "end:a:FINISHED", "start:b"), events.toList())
        assertEquals("b", name(slots.playingTrack!!))
        assertNull(slots.standby)
        pump { events.contains("end:b:FINISHED") }
        assertEquals(listOf("start:a", "end:a:FINISHED", "start:b", "end:b:FINISHED"), events.toList())
    }

    @Test fun `automix starts the next track at its cue when the current track reaches its exit`() {
        setUp(500)
        slots.playTrack(wav("a", 440.0, 10))
        // b plays 1200 Hz for its first 3 s and 880 Hz afterwards: only a cue-in at 3 s avoids the 1200 Hz part
        val automix = CrossfadeController.Automix(exitMs = 5000, entryMs = 3000)
        assertTrue(reserve(wav2("b", 1200.0, 880.0, 3000, 10), automix))
        assertEquals(CrossfadeController.Mode.AUTOMIX, mode())
        assertEquals(5000L, controller.info().next!!.plan!!.exitMs)

        pump { slots.standby != null }
        Thread.sleep(300) // let the standby buffer: the test pulls frames faster than real time
        val frames = pump { events.contains("start:b") }

        assertEquals(listOf("start:a", "end:a:FINISHED", "start:b"), events.toList())
        // 5 s of a, then a 0.5 s overlap; the fade began at the exit point, not near a's 10 s end.
        assertTrue(frames.size in 268..282, "unexpected frame count ${frames.size}")
        val position = slots.playingTrack!!.position
        assertTrue(position in 3400..3800, "next track should be about 3.5 s in, was $position")
        val fade = frames.takeLast(25)
        assertTrue(fade.all { magnitude(it, 1200.0) < 0.1 * (magnitude(it, 440.0) + magnitude(it, 880.0) + 1) }, "the intro tone leaked into the overlap")
        assertTrue(frames.takeLast(1).single().let { magnitude(it, 880.0) > 5 * magnitude(it, 440.0) })
        pump { events.contains("end:b:FINISHED") }
        assertEquals(1, events.count { it.startsWith("start:b") })
    }

    @Test fun `automix cues the track cannot honour fall back to an ordinary crossfade`() {
        setUp(500)
        slots.playTrack(wav("a", 440.0, 8))
        // the exit is only 0.5 s away, too close for the next track to be ready
        assertTrue(reserve(wav("b", 880.0, 8), CrossfadeController.Automix(exitMs = 500, entryMs = 3000)))
        assertEquals(CrossfadeController.Mode.CROSSFADE, mode())
        assertNull(controller.info().next!!.plan!!.exitMs)

        // the entry leaves too little of the next track for the overlap
        assertTrue(reserve(wav("c", 880.0, 8), CrossfadeController.Automix(entryMs = 7000)))
        assertEquals(CrossfadeController.Mode.CROSSFADE, mode())
        assertEquals(0L, controller.info().next!!.plan!!.entryMs)
    }

    @Test fun `without automix parameters it is the ordinary crossfade`() {
        setUp(500)
        slots.playTrack(wav("a", 440.0, 8))
        assertTrue(reserve(wav("b", 880.0, 8)))
        assertEquals(CrossfadeController.Mode.CROSSFADE, mode())
    }

    @Test fun `automix with fading disabled starts the next track normally from its beginning`() {
        enabled = false
        setUp(500)
        slots.playTrack(wav("a", 440.0, 4))
        assertFalse(reserve(wav("b", 880.0, 6), CrossfadeController.Automix(exitMs = 1000, entryMs = 3000)))
        assertEquals(CrossfadeController.Mode.NORMAL, mode())
        pump { events.contains("start:b") }
        assertEquals(1, started.size)
        pull()
        assertTrue(slots.playingTrack!!.position < 300, "a normal transition must not skip into the track")
    }

    @Test fun `a missed exit point falls back to a normal transition`() {
        setUp(500)
        slots.playTrack(wav("a", 440.0, 10))
        assertTrue(reserve(wav("b", 880.0, 8), CrossfadeController.Automix(exitMs = 5000, entryMs = 2000)))
        pump { slots.standby != null }
        slots.playingTrack!!.position = 7000 // seeked well past the exit
        pump(5000) { mode() == CrossfadeController.Mode.NORMAL }
        assertFalse(controller.info().fading)
        assertNull(slots.standby)
        pump { events.contains("start:b") }
        assertEquals(1, started.size)
    }

    @Test fun `automix fade length overrides the player setting and falls back when it no longer fits`() {
        setUp(500)
        slots.playTrack(wav("a", 440.0, 8))
        assertTrue(reserve(wav("b", 880.0, 8), CrossfadeController.Automix(fadeMs = 1500)))
        assertEquals(CrossfadeController.Mode.AUTOMIX, mode())
        assertEquals(1500L, controller.info().next!!.plan!!.fadeMs)

        // with 3 s left the 1.5 s overlap no longer fits (it needs 3.5 s), but the player's 0.5 s crossfade does
        slots.playingTrack!!.position = 5000
        controller.updateSettings(null, null, changeEnabled = false, changeDuration = false)
        assertEquals(CrossfadeController.Mode.CROSSFADE, mode())
        assertEquals(500L, controller.info().next!!.plan!!.fadeMs)
    }
}
