package lavalink.server.player.crossfade

import com.sedmelluq.discord.lavaplayer.filter.PcmFilterFactory
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.event.*
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A stable [AudioPlayer] identity in front of the player that is currently audible.
 * Plugins keep listening to the same object when a crossfade promotes the standby
 * player. Listeners only see events from the current player; events for the
 * transition itself are injected with [dispatch].
 */
internal class SwitchableAudioPlayer(initial: AudioPlayer) : AudioPlayer {
    @Volatile
    var current: AudioPlayer = initial
        private set

    /** Receives volume changes so a prepared standby matches the audible player. */
    @Volatile
    var standby: AudioPlayer? = null

    /**
     * While true, the natural end (FINISHED) of the audible track is held back instead of delivered. An effects transition keeps playing
     * for a while after the outgoing track's audio is over, and the client must hear about the end and the next start together.
     */
    @Volatile
    var deferFinishedEnd = false

    @Volatile
    private var deferredEnd: TrackEndEvent? = null

    /** The end held back by [deferFinishedEnd], if any. Taking it is up to the caller to deliver. */
    fun takeDeferredEnd(): TrackEndEvent? {
        val held = deferredEnd
        deferredEnd = null
        return held
    }

    private val listeners = CopyOnWriteArrayList<AudioEventListener>()
    private val forwarders = ConcurrentHashMap<AudioPlayer, AudioEventListener>()

    init {
        attach(initial)
    }

    fun attach(player: AudioPlayer) {
        val forwarder = AudioEventListener {
            if (player === current) {
                if (deferFinishedEnd && it is TrackEndEvent && it.endReason == AudioTrackEndReason.FINISHED) deferredEnd = it else dispatch(it)
            }
        }
        forwarders[player] = forwarder
        player.addListener(forwarder)
    }

    /**
     * Stops forwarding [player]'s events. The forwarder is deliberately left registered on the
     * player: Lavaplayer keeps listeners in a plain list, and removing one while that player is
     * dispatching an event (a track ending, an end-time marker) throws
     * ConcurrentModificationException. Once [player] is no longer [current] its forwarder is inert,
     * and it is garbage collected with the player.
     */
    fun detach(player: AudioPlayer) {
        forwarders.remove(player)
    }

    fun switchTo(next: AudioPlayer) {
        current = next
    }

    fun dispatch(event: AudioEvent) {
        val own = rewrap(event)
        listeners.forEach { it.onEvent(own) }
    }

    private fun rewrap(event: AudioEvent): AudioEvent = when (event) {
        is TrackStartEvent -> TrackStartEvent(this, event.track)
        is TrackEndEvent -> TrackEndEvent(this, event.track, event.endReason)
        is TrackExceptionEvent -> TrackExceptionEvent(this, event.track, event.exception)
        is TrackStuckEvent -> TrackStuckEvent(this, event.track, event.thresholdMs, event.stackTrace)
        is PlayerPauseEvent -> PlayerPauseEvent(this)
        is PlayerResumeEvent -> PlayerResumeEvent(this)
        else -> event
    }

    override fun getPlayingTrack(): AudioTrack? = current.playingTrack
    override fun playTrack(track: AudioTrack?) = current.playTrack(track)
    override fun startTrack(track: AudioTrack?, noInterrupt: Boolean) = current.startTrack(track, noInterrupt)
    override fun stopTrack() = current.stopTrack()
    override fun getVolume() = current.volume
    override fun setVolume(volume: Int) {
        current.volume = volume
        standby?.volume = volume
    }

    override fun setFilterFactory(factory: PcmFilterFactory?) = current.setFilterFactory(factory)
    override fun setFrameBufferDuration(duration: Int?) = current.setFrameBufferDuration(duration)
    override fun isPaused() = current.isPaused
    override fun setPaused(value: Boolean) { current.isPaused = value }

    override fun destroy() {
        standby?.let { detach(it); it.destroy() }
        standby = null
        current.destroy()
    }

    override fun addListener(listener: AudioEventListener) { listeners.add(listener) }
    override fun removeListener(listener: AudioEventListener) { listeners.remove(listener) }
    override fun checkCleanup(threshold: Long) = current.checkCleanup(threshold)

    override fun provide(): AudioFrame? = current.provide()
    override fun provide(timeout: Long, unit: TimeUnit): AudioFrame? = current.provide(timeout, unit)
    override fun provide(targetFrame: MutableAudioFrame): Boolean = current.provide(targetFrame)
    override fun provide(targetFrame: MutableAudioFrame, timeout: Long, unit: TimeUnit): Boolean =
        current.provide(targetFrame, timeout, unit)
}
