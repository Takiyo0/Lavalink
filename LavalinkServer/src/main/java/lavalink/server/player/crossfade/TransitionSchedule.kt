package lavalink.server.player.crossfade

/**
 * Per-player reservation state. The caller serializes operations with player
 * controls and owns track loading, the audio mixer, and event delivery.
 */
internal class TransitionSchedule {
    enum class Phase { EMPTY, LOADING, READY_NORMAL, READY_FADE, FADING }
    data class Snapshot(val generation: Long, val phase: Phase, val reservationId: String?)

    private var generation = 0L
    private var phase = Phase.EMPTY
    private var reservationId: String? = null
    private var fadeForbidden = false
    private var cancelledByUser = false

    fun snapshot() = Snapshot(generation, phase, reservationId)

    fun prepare(id: String): Long {
        require(id.isNotBlank())
        generation++
        reservationId = id
        fadeForbidden = false
        cancelledByUser = false
        phase = Phase.LOADING
        return generation
    }

    /** Returns false if an obsolete asynchronous load has completed. */
    fun prepared(token: Long, enabled: Boolean, fadeMs: Long, remainingMs: Long?, nextDurationMs: Long?, isStream: Boolean): Boolean {
        require(fadeMs > 0 && fadeMs <= Long.MAX_VALUE - 2000)
        return prepared(token, eligible(enabled, fadeMs, remainingMs, nextDurationMs, isStream))
    }

    /** As above, with the eligibility already decided by the caller (for example for an automix plan). */
    fun prepared(token: Long, fadeEligible: Boolean): Boolean {
        if (token != generation || phase != Phase.LOADING) return false
        phase = if (fadeEligible) Phase.READY_FADE else Phase.READY_NORMAL
        return true
    }

    /**
     * Re-evaluates a ready reservation after a settings change. A reservation whose
     * fade was cancelled or skipped stays normal. Returns true if the phase changed.
     */
    fun reassess(enabled: Boolean, fadeMs: Long, remainingMs: Long?, nextDurationMs: Long?, isStream: Boolean): Boolean {
        require(fadeMs > 0 && fadeMs <= Long.MAX_VALUE - 2000)
        return reassess(eligible(enabled, fadeMs, remainingMs, nextDurationMs, isStream))
    }

    fun reassess(fadeEligible: Boolean): Boolean {
        if (fadeForbidden || (phase != Phase.READY_FADE && phase != Phase.READY_NORMAL)) return false
        val next = if (fadeEligible) Phase.READY_FADE else Phase.READY_NORMAL
        if (next == phase) return false
        phase = next
        return true
    }

    private fun eligible(enabled: Boolean, fadeMs: Long, remainingMs: Long?, nextDurationMs: Long?, isStream: Boolean): Boolean {
        val minimum = fadeMs + 2000
        return enabled && !isStream && remainingMs != null && nextDurationMs != null &&
            remainingMs >= minimum && nextDurationMs >= minimum
    }

    fun loadFailed(token: Long): Boolean {
        if (token != generation || phase != Phase.LOADING) return false
        clear()
        return true
    }

    fun startFade(remainingMs: Long, fadeMs: Long): Boolean {
        require(fadeMs > 0)
        if (phase != Phase.READY_FADE || remainingMs > fadeMs || remainingMs <= 0) return false
        phase = Phase.FADING
        return true
    }

    /** Starts the fade; the caller has already decided that its window has opened. */
    fun beginFade(): Boolean {
        if (phase != Phase.READY_FADE) return false
        phase = Phase.FADING
        return true
    }

    /** The fade window was missed (for example by seeking); fall back to a normal transition. */
    fun forbidFade(): Boolean {
        if (phase != Phase.READY_FADE) return false
        phase = Phase.READY_NORMAL
        fadeForbidden = true
        return true
    }

    /**
     * Re-evaluates a ready reservation after a seek. A fade skipped only because its window was missed can come back (the user
     * seeked out of the window); one the user cancelled by pausing or seeking during the overlap cannot. Returns true if the phase changed.
     */
    fun rearm(fadeEligible: Boolean): Boolean {
        if (cancelledByUser || (phase != Phase.READY_FADE && phase != Phase.READY_NORMAL)) return false
        fadeForbidden = false
        val next = if (fadeEligible) Phase.READY_FADE else Phase.READY_NORMAL
        if (next == phase) return false
        phase = next
        return true
    }

    /** Pause or seek during overlap keeps the reservation but forbids retrying. */
    fun cancelFade(): Boolean {
        if (phase != Phase.FADING) return false
        phase = Phase.READY_NORMAL
        fadeForbidden = true
        cancelledByUser = true
        return true
    }

    fun clear() {
        generation++
        phase = Phase.EMPTY
        reservationId = null
        fadeForbidden = false
        cancelledByUser = false
    }
}
