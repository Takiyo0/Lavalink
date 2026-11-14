package lavalink.server.player

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.arbjerg.lavalink.protocol.v4.Message
import io.netty.buffer.Unpooled
import lavalink.server.config.ServerConfig
import lavalink.server.io.SocketContext
import lavalink.server.player.crossfade.CrossfadeController
import moe.kyokobot.koe.MediaConnection
import moe.kyokobot.koe.media.AudioFrameProvider
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin

/** Exercises the player's real provider, listeners and controls with a mocked session and voice connection. */
class LavalinkPlayerCrossfadeTest {
    private val files = mutableListOf<Path>()
    private lateinit var manager: AudioPlayerManager
    private lateinit var executor: ScheduledExecutorService
    private lateinit var context: SocketContext
    private lateinit var player: LavalinkPlayer
    private lateinit var provider: AudioFrameProvider

    @BeforeEach fun setUp() {
        manager = DefaultAudioPlayerManager().also { it.registerSourceManager(LocalAudioSourceManager()) }
        executor = Executors.newSingleThreadScheduledExecutor()
        context = mock(SocketContext::class.java)
        `when`(context.sessionPaused).thenReturn(true) // suppresses player update messages needing a voice connection
        `when`(context.playerUpdateService).thenReturn(executor)
        val config = ServerConfig().apply {
            crossfade.enabled = true
            crossfade.durationMs = 500
        }
        player = LavalinkPlayer(context, 1L, config, manager, emptyList())
        val connection = mock(MediaConnection::class.java)
        player.provideTo(connection)
        val captor = ArgumentCaptor.forClass(AudioFrameProvider::class.java)
        verify(connection).audioSender = captor.capture()
        provider = captor.value
    }

    @AfterEach fun tearDown() {
        player.destroy()
        executor.shutdownNow()
        manager.shutdown()
        files.forEach { Files.deleteIfExists(it) }
    }

    private fun wav(hz: Double, seconds: Int): AudioTrack {
        val file = Files.createTempFile("player-crossfade-", ".wav")
        files.add(file)
        val frames = 48000 * seconds
        val pcm = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val sample = (8000 * sin(2 * PI * hz * i / 48000)).toInt()
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

    private fun reserve(track: AudioTrack): Boolean {
        val token = (player.crossfade.begin() as CrossfadeController.Begin.Token).value
        assertEquals(CrossfadeController.Completion.READY, player.crossfade.complete(token, track))
        return player.crossfade.info().next!!.willFade
    }

    /** Messages sent to the client, as short labels. */
    private fun sent(): List<String> = mockingDetails(context).invocations
        .filter { it.method.name == "sendMessage" }
        .mapNotNull { it.arguments[1] as? Message.EmittedEvent }
        .map {
            when (it) {
                is Message.EmittedEvent.TrackStartEvent -> "start:${it.track.info.identifier.substringAfterLast('\\').substringAfterLast('/').substringBefore('-')}"
                is Message.EmittedEvent.TrackEndEvent -> "end:${it.track.info.identifier.substringAfterLast('\\').substringAfterLast('/').substringBefore('-')}:${it.reason}"
                else -> it::class.simpleName!!
            }
        }

    /**
     * The test pulls frames far faster than real time, so give the next track's standby a moment to load before the fade is due.
     * Without it, a slow machine finds the standby empty at the start of the fade and the fade is (rightly) cancelled.
     */
    private fun warmUp(): Int {
        var n = 0
        val frames = pump { ++n > 30 }
        Thread.sleep(600)
        return frames
    }

    private fun pump(timeoutMs: Long = 20_000, until: () -> Boolean): Int {
        var frames = 0
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!until()) {
            check(System.nanoTime() < deadline) { "timed out; sent=${sent()}" }
            if (provider.canProvide()) {
                val out = Unpooled.buffer()
                assertTrue(provider.provideFrame(out))
                assertTrue(out.readableBytes() > 0)
                out.release()
                frames++
            } else Thread.sleep(1)
        }
        return frames
    }

    @Test fun `fade through the real provider reports one end and one start and keeps plugin-visible identity`() {
        val handle = player.audioPlayer
        val a = wav(440.0, 4)
        val b = wav(880.0, 4)
        player.play(a)
        assertTrue(reserve(b))
        val frames = warmUp() + pump { sent().contains("end:player:FINISHED") && sent().count { it.startsWith("end:") } == 2 }
        assertEquals(listOf("start:player", "end:player:FINISHED", "start:player", "end:player:FINISHED"), sent())
        assertTrue(frames in 340..360, "frames=$frames")
        assertSame(handle, player.audioPlayer)
        assertNull(player.track)
        assertNull(player.crossfade.info().next)
    }

    @Test fun `explicit play and stop take precedence over a reservation`() {
        player.play(wav(440.0, 6))
        assertTrue(reserve(wav(880.0, 6)))
        player.play(wav(220.0, 3))
        assertNull(player.crossfade.info().next)

        assertTrue(reserve(wav(880.0, 6)))
        player.stop()
        assertNull(player.crossfade.info().next)
        assertNull(player.track)
    }

    @Test fun `pause during the overlap falls back to a normal transition`() {
        player.play(wav(440.0, 4))
        assertTrue(reserve(wav(880.0, 4)))
        warmUp()
        pump { player.crossfade.info().fading }
        player.setPause(true)
        assertFalse(player.crossfade.info().fading)
        assertFalse(player.crossfade.info().next!!.willFade)
        player.setPause(false)
        pump { sent().size >= 3 }
        assertEquals(listOf("start:player", "end:player:FINISHED", "start:player"), sent())
        assertTrue(player.track!!.position < 500, "the reserved track should begin from its start")
    }

    @Test fun `end time marker still consumes the reservation`() {
        val a = wav(440.0, 6)
        player.play(a)
        player.endTimeMs = 3000
        a.setMarker(com.sedmelluq.discord.lavaplayer.track.TrackMarker(3000, TrackEndMarkerHandler(player)))
        assertTrue(reserve(wav(880.0, 6)))
        pump { sent().size >= 3 }
        assertEquals("start:player", sent()[0])
        assertEquals("end:player:FINISHED", sent()[1])
        assertEquals("start:player", sent()[2])
        assertNotNull(player.track)
    }
}
