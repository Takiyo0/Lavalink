package lavalink.server.player.crossfade

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.ShortBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin

class OpusMixFeasibilityTest {
    @Test fun `two local players can decode mix and reencode without changing manager format`() {
        fun wav(hz: Double): java.nio.file.Path {
            val file = Files.createTempFile("crossfade-tone", ".wav")
            val frames = 48000 * 2
            val pcm = ByteArray(frames * 4)
            for (i in 0 until frames) {
                val sample = (10000 * sin(2 * PI * hz * i / 48000)).toInt()
                for (channel in 0..1) {
                    pcm[i * 4 + channel * 2] = sample.toByte()
                    pcm[i * 4 + channel * 2 + 1] = (sample shr 8).toByte()
                }
            }
            val format = AudioFormat(48000f, 16, 2, true, false)
            AudioSystem.write(AudioInputStream(ByteArrayInputStream(pcm), format, frames.toLong()), AudioFileFormat.Type.WAVE, file.toFile())
            return file
        }
        val first = wav(440.0)
        val second = wav(880.0)
        val manager = DefaultAudioPlayerManager()
        manager.registerSourceManager(LocalAudioSourceManager())
        val a = manager.createPlayer()
        val b = manager.createPlayer()
        val format = StandardAudioDataFormats.DISCORD_OPUS
        val decoderA = format.createDecoder()
        val decoderB = format.createDecoder()
        val encoder = format.createEncoder(manager.configuration)
        val verifyDecoder = format.createDecoder()
        try {
            assertTrue(manager.configuration.outputFormat == format)
            a.playTrack(manager.loadItemSync(first.toString()) as AudioTrack)
            b.playTrack(manager.loadItemSync(second.toString()) as AudioTrack)
            fun frame(player: com.sedmelluq.discord.lavaplayer.player.AudioPlayer): ByteArray {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < deadline) {
                    player.provide()?.let { return it.data }
                    Thread.sleep(10)
                }
                error("timed out waiting for audio")
            }
            val channels = format.channelCount
            val count = format.chunkSampleCount * channels
            fun direct() = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
            val left = direct()
            val right = direct()
            decoderA.decode(frame(a), left)
            decoderB.decode(frame(b), right)
            val mixed = ShortArray(count)
            PcmCrossfadeMixer.mix(ShortArray(count) { left.get(it) }, ShortArray(count) { right.get(it) }, mixed, channels, 12000, 48000)
            val encoderInput = direct().apply { put(mixed); flip() }
            val encoded = encoder.encode(encoderInput)
            val result = direct()
            verifyDecoder.decode(encoded, result)
            assertTrue(encoded.isNotEmpty())
            assertTrue((0 until count).any { kotlin.math.abs(result.get(it).toInt()) > 5 }, "mixed frame should contain audio")
            assertTrue(manager.configuration.outputFormat == format, "shared manager output format changed")
        } finally {
            a.destroy(); b.destroy(); decoderA.close(); decoderB.close(); encoder.close(); verifyDecoder.close()
            manager.shutdown(); Files.deleteIfExists(first); Files.deleteIfExists(second)
        }
    }

    @Test fun `separate manager can produce PCM while main manager retains Opus`() {
        val main = DefaultAudioPlayerManager()
        val pcm = DefaultAudioPlayerManager()
        val source = LocalAudioSourceManager()
        main.registerSourceManager(source)
        pcm.registerSourceManager(source)
        pcm.configuration.outputFormat = StandardAudioDataFormats.DISCORD_PCM_S16_LE
        val player = pcm.createPlayer()
        val file = Files.createTempFile("crossfade-pcm", ".wav")
        val audioFormat = AudioFormat(48000f, 16, 2, true, false)
        val samples = ByteArray(48000 * 4)
        for (i in 0 until 48000) {
            val sample = (8000 * sin(2 * PI * 440 * i / 48000)).toInt()
            for (channel in 0..1) {
                samples[i * 4 + channel * 2] = sample.toByte()
                samples[i * 4 + channel * 2 + 1] = (sample shr 8).toByte()
            }
        }
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(samples), audioFormat, 48000), AudioFileFormat.Type.WAVE, file.toFile())
        try {
            val track = main.loadItemSync(file.toString()) as AudioTrack
            player.playTrack(track)
            var frame: com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (frame == null && System.nanoTime() < deadline) { frame = player.provide(); if (frame == null) Thread.sleep(10) }
            assertTrue(frame != null, "PCM player should provide a frame")
            assertTrue(frame!!.format == StandardAudioDataFormats.DISCORD_PCM_S16_LE)
            assertTrue(main.configuration.outputFormat == StandardAudioDataFormats.DISCORD_OPUS)
        } finally {
            player.destroy()
            // Test-only: both managers share this source and are torn down together.
            pcm.shutdown(); main.shutdown(); Files.deleteIfExists(file)
        }
    }
}
