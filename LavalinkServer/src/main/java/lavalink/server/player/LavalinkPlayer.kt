/*
 * Copyright (c) 2021 Freya Arbjerg and contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package lavalink.server.player

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import dev.arbjerg.lavalink.api.AudioPluginInfoModifier
import dev.arbjerg.lavalink.api.IPlayer
import dev.arbjerg.lavalink.api.AudioOutputPosition
import lavalink.server.player.crossfade.CrossfadeController
import lavalink.server.player.crossfade.SwitchableAudioPlayer
import io.netty.buffer.ByteBuf
import lavalink.server.config.ServerConfig
import lavalink.server.io.SocketContext
import lavalink.server.io.SocketServer.Companion.sendPlayerUpdate
import lavalink.server.player.filters.FilterChain
import moe.kyokobot.koe.MediaConnection
import moe.kyokobot.koe.codec.CodecInstance
import moe.kyokobot.koe.media.AudioFrameProvider
import java.nio.ByteBuffer
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class LavalinkPlayer(
    override val socketContext: SocketContext,
    override val guildId: Long,
    private val serverConfig: ServerConfig,
    audioPlayerManager: AudioPlayerManager,
    pluginInfoModifiers: List<AudioPluginInfoModifier>
) : AudioEventAdapter(), IPlayer, AudioOutputPosition {
    @Volatile
    override var lastProvidedFramePositionMs: Long? = null
        private set
    private val buffer = ByteBuffer.allocate(StandardAudioDataFormats.DISCORD_OPUS.maximumChunkSize())
    private val mutableFrame = MutableAudioFrame().apply { setBuffer(buffer) }

    val audioLossCounter = AudioLossCounter()
    var endMarkerHit = false

    /** Position at which the current track is stopped by an end time, if any. */
    @Volatile
    var endTimeMs: Long? = null

    var filters: FilterChain = FilterChain()
        set(value) {
            audioPlayer.setFilterFactory(value.takeIf { it.isEnabled }?.let { chain ->
                PcmFilterFactory { track, format, output ->
                    chain.buildChainForPlayer(this, track, format, output)
                }
            })
            // The standby is not the player plugins know, so it gets no player-aware filters until promoted.
            slots.standby?.setFilterFactory(standbyFilterFactory(value))
            field = value
        }

    private val slots = SwitchableAudioPlayer(audioPlayerManager.createPlayer())

    override val audioPlayer: AudioPlayer get() = slots

    internal val crossfade = CrossfadeController(
        audioPlayerManager,
        slots,
        CrossfadeController.Settings(
            serverConfig.crossfade.enabled,
            serverConfig.crossfade.durationMs.coerceIn(CrossfadeController.MIN_DURATION_MS, CrossfadeController.MAX_DURATION_MS)
        ),
        object : CrossfadeController.Host {
            override fun configureStandby(player: AudioPlayer) {
                player.volume = slots.volume
                player.setFilterFactory(standbyFilterFactory(filters))
            }

            override fun promoted(player: AudioPlayer) {
                endTimeMs = null
                // Reattach player-aware filters (such as the spectrum tap) now that this is the audible player.
                if (filters.isEnabled) player.setFilterFactory(PcmFilterFactory { track, format, output ->
                    filters.buildChainForPlayer(this@LavalinkPlayer, track, format, output)
                })
            }

            override fun startReserved(track: AudioTrack) {
                endTimeMs = null
                slots.playTrack(track)
                sendPlayerUpdate(socketContext, this@LavalinkPlayer)
            }

            override fun endTimeMs() = this@LavalinkPlayer.endTimeMs
        },
        "guild $guildId"
    )

    init {
        slots.addListener(this)
        slots.addListener(EventEmitter(audioPlayerManager, this, pluginInfoModifiers))
        slots.addListener(audioLossCounter)
    }

    private fun standbyFilterFactory(chain: FilterChain): PcmFilterFactory? =
        chain.takeIf { it.isEnabled }?.let { PcmFilterFactory { track, format, output -> it.buildChainForPlayer(null, track, format, output) } }

    private var updateFuture: ScheduledFuture<*>? = null

    override val isPlaying: Boolean
        get() = audioPlayer.playingTrack != null && !audioPlayer.isPaused

    override val track: AudioTrack?
        get() = audioPlayer.playingTrack

    fun destroy() {
        crossfade.close()
        audioPlayer.destroy()
    }

    fun provideTo(connection: MediaConnection) {
        connection.audioSender = Provider()
    }


    override fun play(track: AudioTrack) {
        crossfade.clearReservation()
        endTimeMs = null
        audioPlayer.playTrack(track)
        sendPlayerUpdate(socketContext, this)
    }

    override fun stop() {
        // An end-time marker stops the track as a normal end, which may start the reserved one.
        if (!endMarkerHit) crossfade.clearReservation()
        audioPlayer.stopTrack()
    }

    override fun setPause(pause: Boolean) {
        if (pause) crossfade.onPause()
        audioPlayer.isPaused = pause
    }

    override fun seekTo(position: Long) {
        val track = audioPlayer.playingTrack ?: throw RuntimeException("Can't seek when not playing anything")
        crossfade.onSeek(position)
        track.position = position
    }

    override fun setVolume(volume: Int) {
        audioPlayer.volume = volume
    }

    override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
        lastProvidedFramePositionMs = null
        // 2025-07-31 changed from !! to ? due to possible race condition (or general condition) where
        // updateFuture has not be initialised yet somehow.
        updateFuture?.cancel(false)
    }

    override fun onTrackStart(player: AudioPlayer, track: AudioTrack) {
        lastProvidedFramePositionMs = null
        if (updateFuture?.isCancelled == false) {
            return
        }

        updateFuture = socketContext.playerUpdateService.scheduleAtFixedRate(
            { sendPlayerUpdate(socketContext, this) },
            0,
            serverConfig.playerUpdateInterval.toLong(),
            TimeUnit.SECONDS
        )
    }

    private inner class Provider : AudioFrameProvider {

        override fun onCodecChanged(codec: CodecInstance) {
        }

        override fun dispose() {}

        override fun canProvide() = crossfade.provide(mutableFrame, buffer).also { provided ->
            if (!provided) {
                audioLossCounter.onLoss()
            } else {
                lastProvidedFramePositionMs = crossfade.lastTimecode
            }
        }

        override fun provideFrame(buf: ByteBuf): Boolean {
            audioLossCounter.onSuccess()
            buf.writeBytes(buffer.flip())
            return true
        }
    }
}
