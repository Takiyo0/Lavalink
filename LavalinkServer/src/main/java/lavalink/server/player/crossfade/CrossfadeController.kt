package lavalink.server.player.crossfade

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.player.event.TrackEndEvent
import com.sedmelluq.discord.lavaplayer.player.event.TrackStartEvent
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.min

/**
 * Owns one player's reserved next track and the overlap with the current one.
 *
 * Ordinary playback is untouched: frames come straight from the audible player.
 * Only while a fade runs are two Opus frames decoded, mixed and re-encoded. The
 * standby player starts (paused) shortly before the fade so its buffer is ready,
 * and is promoted to the audible player when the fade completes.
 *
 * All state is guarded by [lock]; nothing under the lock performs network or
 * disk I/O. Listener notifications and starting the next track run after the
 * outermost lock release (see [locked]).
 */
/**
 * The tempos the outgoing track can be brought to, as multiples of the incoming track's: the same, double, half, and the 3:2 and 4:3
 * relations DJs mix on. 170 BPM into 110 BPM is not close to any of the first three, but 110 x 1.5 = 165 is only 3% from 170.
 */
private val TEMPO_RELATIONS = doubleArrayOf(1.0, 2.0, 0.5, 1.5, 2.0 / 3, 4.0 / 3, 0.75)

/**
 * Given next tempo over current tempo, the stretch factor for the outgoing track that brings it to the nearest of [TEMPO_RELATIONS]
 * (the smallest change, whichever direction).
 */
internal fun nearestTempoRatio(ratio: Double): Double = TEMPO_RELATIONS.map { ratio * it }.minBy { Math.abs(Math.log(it)) }

internal class CrossfadeController(
    private val manager: AudioPlayerManager,
    private val slots: SwitchableAudioPlayer,
    private val defaults: Settings,
    private val host: Host,
    private val label: String = "player",
    private val standbyRetryDelayMs: Long = STANDBY_RETRY_DELAY_MS,
) : AutoCloseable {
    companion object {
        private val log = LoggerFactory.getLogger(CrossfadeController::class.java)
        const val MIN_DURATION_MS = 100L
        const val MAX_DURATION_MS = 30_000L

        /**
         * How long before the transition's first moment the standby starts loading. Long enough that a slow source is ready and a
         * failed load (for example a refused stream URL) can be retried in time; short enough not to hold a connection idle for minutes.
         */
        const val PREROLL_MS = 25_000L

        /** Extra attempts, each with a fresh copy of the track, after the standby fails to load. */
        const val STANDBY_RETRIES = 2
        const val STANDBY_RETRY_DELAY_MS = 3_000L

        /** Seeking this far back beyond the preroll window discards the prepared standby. */
        const val PREROLL_DROP_MARGIN_MS = 1_000L

        /**
         * The fade finishes this long before the current track's reported end. Reported lengths are
         * approximate and the audio can run dry early; a fade that ended exactly at the end would then
         * be cut short by the end-of-track event instead of completing cleanly.
         */
        const val END_MARGIN_MS = 500L

        /** A fade whose window was missed by more than this (seek) is skipped. */
        const val START_TOLERANCE_MS = 500L

        /** After a seek, the player's reported position counts as caught up with the target once it is this close. */
        const val SEEK_SETTLED_MS = 1_500L

        /** Audio ticks (20 ms each) to wait for the reported position to catch up with a seek before trusting it anyway. */
        const val SEEK_MAX_TICKS = 300

        // effects
        const val MAX_TEMPO_CHANGE = 0.10
        const val DEFAULT_LEAD_MS = 6_000L
        const val DEFAULT_BEAT_LEAD_MS = 8_000L
        const val MIN_LEAD_MS = 1_000L
        const val MIN_BEAT_LEAD_MS = 4_500L
        const val MIN_BEAT_LEAD_TEMPO_MS = 7_500L
        const val MAX_LEAD_MS = 20_000L
        const val DEFAULT_MUFFLE_HZ = 700
        const val MIN_MUFFLE_HZ = 100.0
        const val MAX_MUFFLE_HZ = 8_000.0
    }

    data class Settings(val enabled: Boolean, val durationMs: Long)

    /**
     * Optional per-transition cue points from the client. All absent means an ordinary crossfade.
     *
     * @property exitMs position in the current track where the transition begins; absent anchors the fade to its end
     * @property entryMs position in the next track where it starts; absent starts it from the beginning
     * @property entryGainDb gain of the next track at the start of the overlap, ramping to 0 dB by its end
     * @property fadeMs length of this overlap instead of the player's setting
     */
    data class Automix(
        val exitMs: Long? = null,
        val entryMs: Long? = null,
        val entryGainDb: Double? = null,
        val fadeMs: Long? = null,
        val effects: Effects? = null,
    ) {
        val isEmpty get() = exitMs == null && entryMs == null && entryGainDb == null && fadeMs == null && effects?.isEmpty != false

        companion object {
            val NONE = Automix()
        }
    }

    /**
     * Optional effects for an automix transition. They process the outgoing track on its own for [leadMs] before the overlap, so
     * they need time: if the current track does not have it, or the tracks cannot support an effect, that effect is dropped.
     *
     * @property muffleExit close a low-pass on the outgoing track
     * @property muffleEntry open a low-pass on the incoming track
     * @property muffleHz the cutoff the muffled sound reaches (default 700)
     * @property leadMs how long before the overlap the outgoing track is processed on its own
     * @property currentBpm the outgoing track's tempo, needed for [tempoMatch] and [beatSync]
     * @property nextBpm the incoming track's tempo
     * @property tempoMatch stretch the outgoing track toward a simple multiple of the incoming track's tempo (up to 10%) during the lead-in
     * @property beatSync measure where the beats fall in the audio of both tracks and line them up
     */
    data class Effects(
        val muffleExit: Boolean? = null,
        val muffleEntry: Boolean? = null,
        val muffleHz: Int? = null,
        val leadMs: Long? = null,
        val currentBpm: Double? = null,
        val nextBpm: Double? = null,
        val tempoMatch: Boolean? = null,
        val beatSync: Boolean? = null,
    ) {
        val isEmpty get() = muffleExit != true && muffleEntry != true && tempoMatch != true && beatSync != true
    }

    enum class Mode { AUTOMIX, CROSSFADE, NORMAL }

    /** The transition the server will actually perform, after checking the request against both tracks. */
    data class Plan(
        val mode: Mode,
        val fadeMs: Long,
        val exitMs: Long?,
        val entryMs: Long,
        val entryGainDb: Double,
        internal val fx: TransitionEffects? = null,
    )

    data class Next(val track: AudioTrack, val mode: Mode, val plan: Plan?) {
        val willFade get() = mode != Mode.NORMAL
    }

    data class Info(val settings: Settings, val fading: Boolean, val next: Next?)

    sealed interface Begin {
        data class Token(val value: Long) : Begin
        data object NotPlaying : Begin
    }

    enum class Completion { READY, STALE, NOT_PLAYING }

    interface Host {
        /** Applies the audible player's volume and filters to a freshly created standby. */
        fun configureStandby(player: AudioPlayer)

        /** The standby became the audible player. */
        fun promoted(player: AudioPlayer)

        /** Starts [track] on the audible player after the current one finished normally. */
        fun startReserved(track: AudioTrack)

        /** Position at which the current track is stopped by an end time, if any. */
        fun endTimeMs(): Long?
    }

    private val lock = ReentrantLock()
    private val schedule = TransitionSchedule()
    private val pending = ConcurrentLinkedQueue<() -> Unit>()
    private var reserved: AudioTrack? = null
    private var plan: Plan? = null
    private var requested = Automix.NONE
    private var activeFadeMs = 0L
    private var renderer: PcmTransitionRenderer? = null
    private var engine: TransitionEngine? = null

    /** An effects transition that has handed over to the incoming track but is still replaying the frames it read ahead of it. */
    private var postRoll: TransitionEngine? = null

    /** The previous transition's read-ahead frames, played as the outgoing audio of a transition that began before the replay was over. */
    private var replayFrom: TransitionEngine? = null

    /** Set by the standby's own listener when its track failed to load or play. */
    @Volatile
    private var standbyFailed = false
    private var standbyFailures = 0
    private var standbyRetryAtNanos = 0L

    @Volatile
    private var ticking = false

    /** A seek happened: re-check the plan against the new position at the next tick. */
    @Volatile
    private var replanPending = false

    /**
     * Right after a seek the player keeps reporting the old position for a moment (the old audio is still playing out). Reading
     * that would make a fade look missed or due, so the seek's target is used until the reported position has caught up.
     */
    private var seekTargetMs: Long? = null
    private var seekTicks = 0

    @Volatile
    var enabledOverride: Boolean? = null
        private set

    @Volatile
    var durationOverride: Long? = null
        private set

    /** Timecode of the last frame returned by [provide]. */
    @Volatile
    var lastTimecode = 0L
        private set

    fun settings() = Settings(enabledOverride ?: defaults.enabled, durationOverride ?: defaults.durationMs)

    fun updateSettings(enabled: Boolean?, durationMs: Long?, changeEnabled: Boolean, changeDuration: Boolean) = locked {
        if (changeEnabled) enabledOverride = enabled
        if (changeDuration) durationOverride = durationMs
        reassessLocked()
    }

    fun info(): Info = locked {
        val phase = schedule.snapshot().phase
        val track = reserved
        val next = if (track != null && (phase == TransitionSchedule.Phase.READY_NORMAL ||
                    phase == TransitionSchedule.Phase.READY_FADE || phase == TransitionSchedule.Phase.FADING)
        ) {
            val fading = phase == TransitionSchedule.Phase.READY_FADE || phase == TransitionSchedule.Phase.FADING
            val p = plan.takeIf { fading }
            Next(track, if (p != null) p.mode else Mode.NORMAL, p)
        } else null
        Info(settings(), phase == TransitionSchedule.Phase.FADING, next)
    }

    // ---- reservation -------------------------------------------------------

    /**
     * Starts a reservation, replacing any existing one. The caller always wins: a running
     * overlap is cancelled and the current track is restored. Until the replacement has
     * loaded successfully nothing is reserved, so a failure never falls back to the old one.
     */
    fun begin(): Begin = locked {
        if (slots.current.playingTrack == null) return@locked Begin.NotPlaying
        if (schedule.snapshot().phase == TransitionSchedule.Phase.FADING) cancelFadeLocked()
        dropStandby()
        releaseReserved()
        val token = schedule.prepare(UUID.randomUUID().toString())
        resetStandbyFailures()
        sync()
        log.debug("[{}] Loading next track for crossfade (reservation {})", label, token)
        Begin.Token(token)
    }

    fun complete(token: Long, track: AudioTrack, automix: Automix = Automix.NONE): Completion = locked {
        val current = slots.current.playingTrack
        if (current == null) {
            if (schedule.loadFailed(token)) sync()
            return@locked Completion.NOT_PLAYING
        }
        val s = settings()
        val chosen = evaluate(current, track, automix, s)
        if (!schedule.prepared(token, chosen != null)) {
            log.debug("[{}] Ignoring stale load of '{}' (reservation {} was replaced or cleared)", label, track.info.title, token)
            return@locked Completion.STALE
        }
        reserved = track
        requested = automix
        plan = chosen
        sync()
        log.debug(
            "[{}] Next track '{}' reserved: {} (enabled={}, requested={}, current remaining={}ms, next length={}ms)",
            label, track.info.title, describe(chosen), s.enabled, if (automix.isEmpty) "none" else automix,
            remainingMs(current), durationOf(track)
        )
        Completion.READY
    }

    /** Releases a reservation whose track could not be loaded. */
    fun fail(token: Long) = locked {
        if (schedule.loadFailed(token)) {
            releaseReserved()
            dropStandby()
            sync()
            log.debug("[{}] Loading the next track failed; nothing is reserved (reservation {})", label, token)
        }
    }

    /** Clear, stop, replacement and other explicit commands take precedence over a reservation. */
    fun clearReservation() = locked {
        val phase = schedule.snapshot().phase
        if (phase != TransitionSchedule.Phase.EMPTY) {
            log.debug("[{}] Reservation cleared by an explicit command (was {}, '{}')", label, phase, reserved?.info?.title)
        }
        if (phase == TransitionSchedule.Phase.FADING) {
            releaseDeferredEndLocked()
            closeRenderer()
        }
        abortPostRollLocked()
        dropStandby()
        schedule.clear()
        releaseReserved()
        sync()
    }

    // ---- player controls ---------------------------------------------------

    fun onPause() = locked {
        if (schedule.snapshot().phase == TransitionSchedule.Phase.FADING) cancelFadeLocked()
    }

    /**
     * A seek during a transition cancels it. Otherwise the plan is checked again against the new position on the next audio tick
     * (the seek has not been applied yet when this runs): the lead-in is shortened if it no longer fits, and a fade that was skipped
     * because the window had been missed comes back if the user seeks out of it.
     */
    fun onSeek(targetMs: Long? = null) = locked {
        seekTargetMs = targetMs
        seekTicks = 0
        abortPostRollLocked()
        if (schedule.snapshot().phase == TransitionSchedule.Phase.FADING) cancelFadeLocked()
        else if (reserved != null) {
            replanPending = true
            sync()
        }
    }

    /**
     * The position of [track], with a seek that has not taken effect yet taken into account. The reported position cannot be trusted
     * to say when it has: it can show the target while the seek is pending and the old position again just after. The timecode of the
     * frames actually being played is what settles it.
     */
    private fun positionOf(track: AudioTrack): Long {
        // while the read-ahead of the last transition is replayed the player is up to 6 s ahead of what is heard
        if (postRoll != null) return lastTimecode
        val actual = track.position
        val target = seekTargetMs ?: return actual
        if (Math.abs(lastTimecode - target) <= SEEK_SETTLED_MS) {
            seekTargetMs = null
            return actual
        }
        return target
    }

    /**
     * Called after the end of a track has been reported to the client. A finished
     * track consumes the reservation; any other end during an overlap cancels it.
     */
    fun onTrackEnded(reason: AudioTrackEndReason) = locked {
        val phase = schedule.snapshot().phase
        if (phase != TransitionSchedule.Phase.EMPTY) log.debug("[{}] Current track ended ({}) while {}", label, reason, phase)
        when (phase) {
            TransitionSchedule.Phase.FADING ->
                if (reason == AudioTrackEndReason.FINISHED) {
                    // an effects transition plays out what is left of the current track and completes by itself
                    val running = engine
                    if (running != null) running.currentEnded() else commitLocked(emitEnd = false)
                } else cancelFadeLocked()

            TransitionSchedule.Phase.READY_NORMAL, TransitionSchedule.Phase.READY_FADE ->
                if (reason == AudioTrackEndReason.FINISHED) advanceLocked()

            else -> {}
        }
    }

    // ---- audio -------------------------------------------------------------

    /** Fills [buffer] with the next Opus frame. Called from the voice sender thread. */
    fun provide(frame: MutableAudioFrame, buffer: ByteBuffer): Boolean {
        if (!ticking) return passthrough(frame)
        return locked {
            if (seekTargetMs != null && !slots.current.isPaused && ++seekTicks > SEEK_MAX_TICKS) seekTargetMs = null
            if (replanPending) replanLocked()
            if (schedule.snapshot().phase == TransitionSchedule.Phase.READY_FADE) tickReadyLocked()
            if (schedule.snapshot().phase == TransitionSchedule.Phase.FADING) fadeFrameLocked(frame, buffer)
            else if (postRoll != null) postRollFrameLocked(frame, buffer)
            else passthrough(frame)
        }
    }

    private fun passthrough(frame: MutableAudioFrame): Boolean = slots.provide(frame).also {
        if (it) lastTimecode = frame.timecode
    }

    private fun tickReadyLocked() {
        val audible = slots.current
        val track = audible.playingTrack ?: return
        if (audible.isPaused) return
        val chosen = plan
        if (chosen == null) {
            skipFadeLocked()
            return
        }
        // Milliseconds until the transition is due: until its lead-in begins, until the cue-out point, or until the fade
        // has to begin to finish before the end.
        val untilStart = if (chosen.fx != null && chosen.exitMs != null) {
            chosen.exitMs - chosen.fx.leadMs - positionOf(track)
        } else if (chosen.exitMs != null) {
            chosen.exitMs - positionOf(track)
        } else {
            val remaining = remainingMs(track)
            if (remaining == null) {
                skipFadeLocked()
                return
            }
            remaining - (chosen.fadeMs + END_MARGIN_MS)
        }
        if (untilStart > PREROLL_MS + PREROLL_DROP_MARGIN_MS) {
            dropStandby() // sought back; prepare again when the window is near
            return
        }
        if (standbyFailed) {
            standbyFailed = false
            standbyFailures++
            dropStandby()
            if (standbyFailures > STANDBY_RETRIES) {
                log.warn("[{}] The next track failed to load {} times; it will start normally instead of crossfading", label, standbyFailures)
                skipFadeLocked()
                return
            }
            standbyRetryAtNanos = System.nanoTime() + standbyRetryDelayMs * 1_000_000
            log.info("[{}] Loading the next track failed; trying again with a fresh copy (attempt {} of {})", label, standbyFailures + 1, STANDBY_RETRIES + 1)
        }
        if (untilStart <= PREROLL_MS && slots.standby == null && System.nanoTime() >= standbyRetryAtNanos && !startStandbyLocked()) {
            skipFadeLocked()
            return
        }
        if (untilStart > 0) return
        val standby = slots.standby
        if (standby == null || untilStart < -START_TOLERANCE_MS) {
            skipFadeLocked()
            return
        }
        // the classic crossfade reads the player directly and cannot play the replay first
        if (postRoll != null && !(chosen.fx != null && chosen.exitMs != null)) return
        if (schedule.beginFade()) {
            // an effects transition that begins while the last one is still being replayed takes the replay as the start of its outgoing audio
            replayFrom = postRoll
            postRoll = null
            log.debug(
                "[{}] Starting {}ms {} into '{}' ({}ms {})", label, chosen.fadeMs,
                if (chosen.mode == Mode.AUTOMIX) "automix transition" else "crossfade", reserved?.info?.title,
                if (chosen.exitMs != null) -untilStart else remainingMs(track),
                if (chosen.exitMs != null) "past the cue-out point" else "of the current track left, the fade ends $END_MARGIN_MS ms before its end"
            )
            activeFadeMs = chosen.fadeMs
            if (chosen.fx != null && chosen.exitMs != null) {
                slots.deferFinishedEnd = true
                engine = TransitionEngine(
                    manager.configuration, chosen.fadeMs, dbToLinear(chosen.entryGainDb), chosen.exitMs,
                    chosen.exitMs - chosen.fx.leadMs, chosen.fx, label
                )
            } else {
                renderer = PcmTransitionRenderer(manager.configuration, dbToLinear(chosen.entryGainDb))
            }
            standby.isPaused = false
            sync()
        }
    }

    private fun fadeFrameLocked(frame: MutableAudioFrame, buffer: ByteBuffer): Boolean {
        val audible = slots.current
        if (audible.isPaused) {
            cancelFadeLocked()
            return passthrough(frame)
        }
        if (standbyFailed) {
            standbyFailed = false
            log.warn("[{}] The incoming track failed while the transition ran; cancelling it", label)
            cancelFadeLocked()
            return passthrough(frame)
        }
        val running = engine
        if (running != null) return engineFrameLocked(running, audible, frame, buffer)
        val rendered = try {
            renderer?.render(audible, slots.standby, activeFadeMs)
        } catch (e: Exception) {
            log.error("Crossfade rendering failed; falling back to a normal transition", e)
            cancelFadeLocked()
            return false
        }
        // The current track may have ended inside render(), which promoted the standby.
        if (slots.current !== audible) return passthrough(frame)
        if (renderer == null) return passthrough(frame)
        if (rendered == null) return false
        buffer.clear()
        buffer.put(rendered.opus)
        lastTimecode = rendered.timecode
        if (rendered.standbyStarved) {
            log.warn("Crossfade standby had no audio ready; cancelling the overlap")
            cancelFadeLocked()
        } else if (rendered.fadeFinished) {
            commitLocked(emitEnd = true)
        }
        return true
    }

    /** Replays what was read ahead of the incoming track, which is already the audible player; then the player carries on from there. */
    private fun postRollFrameLocked(frame: MutableAudioFrame, buffer: ByteBuffer): Boolean {
        val replay = postRoll ?: return passthrough(frame)
        if (slots.current.isPaused) return false
        val output = replay.nextPostRollFrame()
        if (output is TransitionEngine.Output.Frame) {
            buffer.clear()
            buffer.put(output.opus)
            lastTimecode = output.timecode
            return true
        }
        abortPostRollLocked()
        return passthrough(frame)
    }

    private fun abortPostRollLocked() {
        postRoll?.close()
        postRoll = null
        sync()
    }

    private fun engineFrameLocked(running: TransitionEngine, audible: AudioPlayer, frame: MutableAudioFrame, buffer: ByteBuffer): Boolean {
        val output = try {
            running.render(sourceOf(audible), slots.standby)
        } catch (e: Exception) {
            log.error("Automix rendering failed; falling back to a normal transition", e)
            cancelFadeLocked()
            return false
        }
        if (slots.current !== audible || engine == null) return passthrough(frame)
        return when (output) {
            is TransitionEngine.Output.Frame -> {
                buffer.clear()
                buffer.put(output.opus)
                lastTimecode = output.timecode
                // the incoming track has taken over: tell the client now, and replay the read-ahead frames afterwards
                if (output.overlapEnded) commitLocked(emitEnd = true, keepEngine = true)
                true
            }

            TransitionEngine.Output.Waiting -> false
            TransitionEngine.Output.Aborted -> {
                log.warn("[{}] The incoming track did not provide audio in time; cancelling the transition", label)
                cancelFadeLocked()
                passthrough(frame)
            }

            TransitionEngine.Output.Done -> {
                commitLocked(emitEnd = true)
                passthrough(frame)
            }
        }
    }

    // ---- transitions -------------------------------------------------------

    private fun startStandbyLocked(): Boolean {
        val source = reserved ?: return false
        return try {
            val clone = source.makeClone()
            clone.userData = source.userData
            val margin = plan?.fx?.marginMs ?: 0L
            val entry = plan?.entryMs ?: 0L
            if (entry - margin > 0) clone.position = entry - margin
            val player = manager.createPlayer()
            slots.attach(player)
            player.addListener(StandbyWatcher(player))
            host.configureStandby(player)
            player.isPaused = true
            slots.standby = player
            player.playTrack(clone)
            log.debug("[{}] Preparing '{}' on a standby player for the crossfade (starting at {}ms)", label, source.info.title, entry - margin)
            true
        } catch (e: Exception) {
            log.warn("Could not prepare the crossfade standby", e)
            dropStandby()
            false
        }
    }

    /**
     * Watches the standby's own player. Its events never reach the client (the standby is not the audible player), so a failure
     * would otherwise only show as missing audio, found when the transition is already due.
     */
    private inner class StandbyWatcher(private val player: AudioPlayer) : AudioEventAdapter() {
        override fun onTrackException(player: AudioPlayer, track: AudioTrack, exception: FriendlyException) =
            failed("exception: ${exception.message}")

        override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) = failed("stuck for ${thresholdMs}ms")

        override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
            if (endReason == AudioTrackEndReason.LOAD_FAILED) failed("load failed")
        }

        private fun failed(why: String) {
            if (slots.standby !== player) return
            log.warn("[{}] The standby track '{}' failed: {}", label, reserved?.info?.title, why)
            standbyFailed = true
        }
    }

    /** A fade is no longer possible; the reservation still transitions normally. */
    private fun skipFadeLocked() {
        log.debug("[{}] Skipping the crossfade; '{}' will start normally", label, reserved?.info?.title)
        dropStandby()
        schedule.forbidFade()
        sync()
    }

    private fun cancelFadeLocked() {
        log.debug("[{}] Crossfade cancelled; '{}' will start normally from its beginning", label, reserved?.info?.title)
        schedule.cancelFade()
        releaseDeferredEndLocked()
        closeRenderer()
        dropStandby()
        sync()
    }

    /** The outgoing track ended by itself while the transition ran and the transition is not going to complete: the client still has to be told. */
    private fun releaseDeferredEndLocked() {
        val held = slots.takeDeferredEnd() ?: return
        pending.add { slots.dispatch(held) }
    }

    /**
     * Promotes the standby. The notifications and the retired player's destruction are queued first,
     * so nothing that fails afterwards can leave the next track playing without its start event.
     * The retired player's listeners are never modified, as it may be dispatching an event right now.
     */
    private fun commitLocked(emitEnd: Boolean, keepEngine: Boolean = false) {
        val old = slots.current
        val next = slots.standby
        if (next == null) {
            cancelFadeLocked()
            return
        }
        // if the outgoing track ended by itself during the transition, its end was held back so it is delivered with the start
        val held = slots.takeDeferredEnd()
        val oldTrack = held?.track ?: old.playingTrack
        val newTrack = next.playingTrack
        log.debug("[{}] Crossfade complete: '{}' is now playing", label, newTrack?.info?.title)
        schedule.clear()
        releaseReserved()
        slots.standby = null
        slots.switchTo(next)
        slots.detach(old)
        pending.add {
            if ((emitEnd || held != null) && oldTrack != null) slots.dispatch(TrackEndEvent(old, oldTrack, AudioTrackEndReason.FINISHED))
            if (newTrack != null) slots.dispatch(TrackStartEvent(next, newTrack))
        }
        pending.add { old.destroy() }
        if (keepEngine) {
            replayFrom?.close()
            replayFrom = null
            postRoll = engine
            engine = null
            renderer?.close()
            renderer = null
            slots.deferFinishedEnd = false
        } else {
            closeRenderer()
        }
        sync()
        try {
            host.promoted(next)
        } catch (e: Exception) {
            log.warn("[{}] Could not reattach filters after the crossfade", label, e)
        }
    }

    private fun advanceLocked() {
        val track = reserved ?: return
        log.debug("[{}] Starting reserved track '{}' normally", label, track.info.title)
        schedule.clear()
        releaseReserved()
        dropStandby()
        sync()
        pending.add { host.startReserved(track) }
    }

    private fun replanLocked() {
        replanPending = false
        val phase = schedule.snapshot().phase
        val current = slots.current.playingTrack
        val track = reserved
        if ((phase == TransitionSchedule.Phase.READY_FADE || phase == TransitionSchedule.Phase.READY_NORMAL) && current != null && track != null) {
            val chosen = evaluate(current, track, requested, settings())
            val changed = schedule.rearm(chosen != null)
            val armed = schedule.snapshot().phase == TransitionSchedule.Phase.READY_FADE
            if (armed) {
                // a different plan (another lead-in, an entry margin) means the standby, if any, was prepared for the old one
                if (chosen != plan) dropStandby()
                plan = chosen
            } else {
                dropStandby()
            }
            log.debug("[{}] Plan re-checked after a seek to {}ms: {}{}", label, positionOf(current), describe(plan.takeIf { armed }), if (changed) " (changed)" else "")
        }
        sync()
    }

    private fun reassessLocked() {
        val phase = schedule.snapshot().phase
        if (phase != TransitionSchedule.Phase.READY_FADE && phase != TransitionSchedule.Phase.READY_NORMAL) return
        val current = slots.current.playingTrack
        val track = reserved
        val chosen = if (current != null && track != null) evaluate(current, track, requested, settings()) else null
        val changed = schedule.reassess(chosen != null)
        if (schedule.snapshot().phase == TransitionSchedule.Phase.READY_FADE) plan = chosen
        if (changed) {
            if (schedule.snapshot().phase == TransitionSchedule.Phase.READY_NORMAL) dropStandby()
            sync()
        }
    }

    /**
     * Decides what the transition will be: the requested automix cues if both tracks allow them, otherwise an
     * ordinary crossfade if that is possible, otherwise null for a normal transition.
     */
    private fun evaluate(current: AudioTrack, next: AudioTrack, automix: Automix, s: Settings): Plan? {
        if (!s.enabled || current.info.isStream || next.info.isStream) return null
        val nextLength = durationOf(next) ?: return null
        val end = endOf(current) ?: return null
        val position = positionOf(current)

        if (!automix.isEmpty) {
            val fade = automix.fadeMs ?: s.durationMs
            val entry = automix.entryMs ?: 0L
            val exit = automix.exitMs
            val enoughNext = nextLength - entry >= fade + 2000 && (entry == 0L || next.isSeekable)
            val enoughCurrent = if (exit == null) end - position >= fade + 2000 else exit < end && exit - position >= 2000 && end - exit >= fade
            if (enoughNext && enoughCurrent) {
                val fx = automix.effects?.takeIf { !it.isEmpty }?.let { planEffects(it, exit ?: (end - fade - END_MARGIN_MS), position, entry, next, fade) }
                val gain = automix.entryGainDb ?: 0.0
                return if (fx != null) Plan(Mode.AUTOMIX, fade, exit ?: (end - fade - END_MARGIN_MS), entry, gain, fx)
                else Plan(Mode.AUTOMIX, fade, exit, entry, gain)
            }
            log.debug(
                "[{}] Automix cues not usable for '{}' (next ok={}, current ok={}); trying an ordinary crossfade",
                label, next.info.title, enoughNext, enoughCurrent
            )
        }

        val fade = s.durationMs
        return if (end - position >= fade + 2000 && nextLength >= fade + 2000) Plan(Mode.CROSSFADE, fade, null, 0L, 0.0) else null
    }

    /**
     * Checks the requested effects against the tracks and the time available. An effect that cannot be done is dropped (and logged),
     * not an error: the transition is still an automix, just plainer. Null when nothing is left to do.
     */
    private fun planEffects(request: Effects, exitPosition: Long, position: Long, entryMs: Long, next: AudioTrack, fadeMs: Long): TransitionEffects? {
        val bpmOk = { bpm: Double? -> bpm != null && bpm in 40.0..250.0 }
        val haveTempos = bpmOk(request.currentBpm) && bpmOk(request.nextBpm)

        var ratio: Double? = null
        if (request.tempoMatch == true) {
            if (haveTempos) {
                val r = nearestTempoRatio(request.nextBpm!! / request.currentBpm!!)
                if (Math.abs(r - 1) in 0.005..MAX_TEMPO_CHANGE) ratio = r
                else log.debug("[{}] Tempo match skipped: ratio {} is not within {}%", label, r, (MAX_TEMPO_CHANGE * 100).toInt())
            } else log.debug("[{}] Tempo match skipped: both tempos are needed", label)
        }

        // the outgoing track plays on its own for the lead-in, so it needs that much time before the overlap
        var lead = request.leadMs ?: if (request.beatSync == true) DEFAULT_BEAT_LEAD_MS else DEFAULT_LEAD_MS
        lead = minOf(lead, exitPosition - position - 2000)
        if (lead < MIN_LEAD_MS) {
            log.debug("[{}] Effects skipped: only {}ms of lead-in fit before the overlap", label, lead)
            return null
        }

        var beatSync = request.beatSync == true && haveTempos
        if (request.beatSync == true && !beatSync) log.debug("[{}] Beat sync skipped: both tempos are needed", label)
        val minBeatLead = if (ratio != null) MIN_BEAT_LEAD_TEMPO_MS else MIN_BEAT_LEAD_MS
        if (beatSync && lead < minBeatLead) {
            log.debug("[{}] Beat sync skipped: it needs {}ms of lead-in, {}ms fit", label, minBeatLead, lead)
            beatSync = false
        }

        val muffleExit = request.muffleExit == true
        val muffleEntry = request.muffleEntry == true
        if (!muffleExit && !muffleEntry && ratio == null && !beatSync) return null

        val incoming = if (haveTempos) 60.0 / request.nextBpm!! else null
        val outgoing = if (haveTempos) 60.0 / request.currentBpm!! / (ratio ?: 1.0) else null
        val margin = if (beatSync) minOf(entryMs, Math.ceil(incoming!! * 1000).toLong() + 60) else 0L
        return TransitionEffects(
            muffleExit, muffleEntry, (request.muffleHz ?: DEFAULT_MUFFLE_HZ).toDouble().coerceIn(MIN_MUFFLE_HZ, MAX_MUFFLE_HZ),
            lead, ratio, beatSync, outgoing, incoming, margin
        )
    }

    private fun describe(p: Plan?) = when (p?.mode) {
        Mode.AUTOMIX -> "will automix (fade ${p.fadeMs}ms, exit ${p.exitMs ?: "at the end"}, entry ${p.entryMs}ms, entry gain ${p.entryGainDb} dB" +
            (p.fx?.let { ", effects: lead ${it.leadMs}ms, muffle exit=${it.muffleExit} entry=${it.muffleEntry} at ${it.muffleHz.toInt()}Hz, tempo ${it.tempoRatio ?: "off"}, beat sync ${it.beatSync}" } ?: "") + ")"
        Mode.CROSSFADE -> "will crossfade (fade ${p.fadeMs}ms)"
        else -> "normal transition"
    }

    private fun dbToLinear(db: Double) = Math.pow(10.0, db / 20.0)

    private fun resetStandbyFailures() {
        standbyFailed = false
        standbyFailures = 0
        standbyRetryAtNanos = 0L
    }

    private fun releaseReserved() {
        resetStandbyFailures()
        reserved = null
        plan = null
        requested = Automix.NONE
    }

    private fun dropStandby() {
        val standby = slots.standby ?: return
        slots.standby = null
        slots.detach(standby)
        try {
            standby.destroy()
        } catch (e: Exception) {
            log.warn("Failed to destroy the crossfade standby", e)
        }
    }

    private fun sourceOf(player: AudioPlayer) = object : TransitionEngine.FrameSource {
        override fun provide(): AudioFrame? {
            val replay = replayFrom
            if (replay != null) {
                replay.takeReplayFrame()?.let { return it }
                replay.close()
                replayFrom = null
            }
            return player.provide()
        }

        override val playing get() = player.playingTrack != null
    }

    private fun closeRenderer() {
        replayFrom?.close()
        replayFrom = null
        slots.deferFinishedEnd = false
        renderer?.close()
        renderer = null
        engine?.close()
        engine = null
    }

    private fun sync() {
        val phase = schedule.snapshot().phase
        ticking = phase == TransitionSchedule.Phase.READY_FADE || phase == TransitionSchedule.Phase.FADING || replanPending || postRoll != null
    }

    private fun durationOf(track: AudioTrack) = track.duration.takeIf { it > 0 && it != Long.MAX_VALUE }

    /** Where the track stops: its end, or an earlier end time set by the client. Null for streams and unknown lengths. */
    private fun endOf(track: AudioTrack): Long? {
        if (track.info.isStream) return null
        val duration = durationOf(track) ?: return null
        return host.endTimeMs()?.let { min(it, duration) } ?: duration
    }

    private fun remainingMs(track: AudioTrack): Long? = endOf(track)?.let { (it - positionOf(track)).coerceAtLeast(0) }

    override fun close() = locked {
        slots.takeDeferredEnd()
        closeRenderer()
        abortPostRollLocked()
        dropStandby()
        schedule.clear()
        releaseReserved()
        sync()
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
            if (!lock.isHeldByCurrentThread) drain()
        }
    }

    private fun drain() {
        while (true) {
            val action = pending.poll() ?: return
            try {
                action()
            } catch (e: Exception) {
                log.error("[{}] A crossfade follow-up action failed", label, e)
            }
        }
    }
}
