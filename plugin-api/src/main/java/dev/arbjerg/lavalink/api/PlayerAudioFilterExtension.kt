package dev.arbjerg.lavalink.api

import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat
import kotlinx.serialization.json.JsonElement

/** An audio filter extension that needs the identity of the player being decoded. */
interface PlayerAudioFilterExtension : AudioFilterExtension {
    /** When true, place this filter after Lavalink's built-in effects. */
    val afterBuiltInFilters: Boolean get() = false

    fun buildForPlayer(
        player: IPlayer,
        data: JsonElement,
        format: AudioDataFormat,
        output: FloatPcmAudioFilter
    ): FloatPcmAudioFilter?
}
