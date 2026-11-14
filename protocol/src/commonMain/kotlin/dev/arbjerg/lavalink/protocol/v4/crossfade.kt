package dev.arbjerg.lavalink.protocol.v4

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The effective crossfade settings of a player.
 *
 * @property enabled whether a reserved next track is faded in
 * @property durationMs length of the overlap in milliseconds
 */
@Serializable
data class CrossfadeSettings(
    val enabled: Boolean,
    val durationMs: Long
)

/**
 * Player-level overrides. A present `null` resets the value to the server default.
 */
@Serializable
data class CrossfadeSettingsUpdate(
    val enabled: Omissible<Boolean?> = Omissible.Omitted(),
    val durationMs: Omissible<Long?> = Omissible.Omitted()
)

/**
 * Optional cue points that turn the transition into an automix: the next track can start at a chosen position
 * (such as its chorus) while the current one leaves at another. Every field is optional; when none are given the
 * transition is an ordinary crossfade, and cues the tracks cannot honour fall back to one as well.
 *
 * @property exitMs position in the current track, in milliseconds, where the transition begins. When absent the fade
 * is anchored to the end of the current track
 * @property entryMs position in the next track, in milliseconds, where it starts. When absent it starts from the beginning
 * @property entryGainDb gain applied to the next track at the start of the overlap, ramping to 0 dB by its end, for
 * matching loudness (a louder next track gets a negative value)
 * @property fadeMs length of this overlap in milliseconds, instead of the player's setting
 * @property effects optional effects that process the outgoing track before the overlap
 */
@Serializable
data class Automix(
    val exitMs: Long? = null,
    val entryMs: Long? = null,
    val entryGainDb: Double? = null,
    val fadeMs: Long? = null,
    val effects: AutomixEffects? = null
)

/**
 * Effects for an automix transition. They process the outgoing track on its own for [leadMs] before the overlap, so they need
 * that much time before the exit point; an effect that cannot be done (not enough time, tempos too far apart, ...) is dropped,
 * and the response shows which were kept. All fields are optional.
 *
 * @property muffleExit close a low-pass on the outgoing track over the lead-in and the overlap
 * @property muffleEntry start the incoming track muffled and open it over the overlap
 * @property muffleHz the cutoff the muffled sound reaches, 100 to 8000 (default 700)
 * @property leadMs how long before the overlap the outgoing track is processed on its own, up to 20000
 * (default 6000, or 8000 with [beatSync])
 * @property currentBpm tempo of the outgoing track at the transition, 40 to 250; needed for [tempoMatch] and [beatSync]
 * @property nextBpm tempo of the incoming track at its entry point
 * @property tempoMatch stretch the outgoing track toward a simple multiple of the incoming track's tempo (the same, double, half,
 * or the 3:2 and 4:3 relations; at most 10% change) during the lead-in, without changing its pitch
 * @property beatSync measure where the beats fall in the audio of both tracks and line them up, instead of trusting the
 * cue points to land on beats. Needs a long enough lead-in; when the beats are not clear, the cue points are used as they are
 */
@Serializable
data class AutomixEffects(
    val muffleExit: Boolean? = null,
    val muffleEntry: Boolean? = null,
    val muffleHz: Int? = null,
    val leadMs: Long? = null,
    val currentBpm: Double? = null,
    val nextBpm: Double? = null,
    val tempoMatch: Boolean? = null,
    val beatSync: Boolean? = null
)

/**
 * Reserves the next track. Exactly one of [encoded] and [identifier] must be present.
 * A `null` [userData] or [automix] is treated the same as an omitted one.
 */
@Serializable
data class NextTrackUpdate(
    val encoded: Omissible<String> = Omissible.Omitted(),
    val identifier: Omissible<String> = Omissible.Omitted(),
    val userData: Omissible<JsonObject?> = Omissible.Omitted(),
    val automix: Omissible<Automix?> = Omissible.Omitted()
)

/**
 * A reserved next track.
 *
 * @property crossfade whether it is currently scheduled to be faded in, rather than
 * started normally when the current track finishes
 * @property transition what will actually happen: `automix` (the requested cues were accepted),
 * `crossfade` (an ordinary fade, including when the cues could not be used) or `normal`
 * @property automix the cues in effect, only for an `automix` transition
 */
@Serializable
data class NextTrack(
    val track: Track,
    val crossfade: Boolean,
    val transition: String,
    val automix: Automix?
)

@Serializable
data class CrossfadeState(
    val settings: CrossfadeSettings,
    val fading: Boolean,
    val next: NextTrack?
)
