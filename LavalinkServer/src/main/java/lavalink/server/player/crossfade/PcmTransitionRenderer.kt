package lavalink.server.player.crossfade

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * Overlap renderer: decodes one Opus frame from each player, mixes the PCM with an
 * equal-power curve and encodes a single Opus frame. Only used for the duration of
 * a fade; ordinary playback never passes through it. The caller owns the players,
 * scheduling and events.
 */
internal class PcmTransitionRenderer(configuration: AudioConfiguration, private val entryGain: Double = 1.0) : AutoCloseable {
    class Rendered(val opus: ByteArray, val timecode: Long, val fadeFinished: Boolean, val standbyStarved: Boolean)

    private val format = StandardAudioDataFormats.DISCORD_OPUS
    private val sampleCount = format.chunkSampleCount * format.channelCount
    private val aSamples = ShortArray(sampleCount)
    private val bSamples = ShortArray(sampleCount)
    private val mixed = ShortArray(sampleCount)
    private val decodeA = direct()
    private val decodeB = direct()
    private val encodeIn = direct()
    private val decoderA = format.createDecoder()
    private val decoderB = format.createDecoder()
    private val encoder = format.createEncoder(configuration)
    private var fadeOffsetFrames = 0L

    private fun direct() = ByteBuffer.allocateDirect(sampleCount * 2).order(ByteOrder.nativeOrder()).asShortBuffer()

    fun resetFade() {
        fadeOffsetFrames = 0
    }

    /**
     * A missing primary produces no output and does not consume standby. A missing
     * standby passes the primary's frame through unchanged and sets
     * [Rendered.standbyStarved] so the caller can cancel the overlap.
     */
    fun render(primary: AudioPlayer, standby: AudioPlayer?, fadeDurationMs: Long): Rendered? {
        require(fadeDurationMs > 0 && fadeDurationMs <= Long.MAX_VALUE / format.sampleRate)
        val primaryFrame = primary.provide() ?: return null
        val secondary = standby?.provide() ?: return Rendered(primaryFrame.data, primaryFrame.timecode, false, true)
        decode(decoderA, primaryFrame.data, decodeA, aSamples)
        decode(decoderB, secondary.data, decodeB, bSamples)
        val durationFrames = fadeDurationMs * format.sampleRate / 1000
        PcmCrossfadeMixer.mix(aSamples, bSamples, mixed, format.channelCount, fadeOffsetFrames, durationFrames, entryGain)
        fadeOffsetFrames = min(durationFrames, fadeOffsetFrames + format.chunkSampleCount)
        encodeIn.clear()
        encodeIn.put(mixed)
        encodeIn.flip()
        return Rendered(encoder.encode(encodeIn), primaryFrame.timecode, fadeOffsetFrames == durationFrames, false)
    }

    private fun decode(decoder: com.sedmelluq.discord.lavaplayer.format.transcoder.AudioChunkDecoder, data: ByteArray, scratch: java.nio.ShortBuffer, out: ShortArray) {
        scratch.clear()
        decoder.decode(data, scratch)
        for (i in out.indices) out[i] = scratch.get(i)
    }

    override fun close() {
        decoderA.close()
        decoderB.close()
        encoder.close()
    }
}
