package dev.arbjerg.lavalink.api

/** Optional player capability: timecode of the last frame handed to the voice sender. */
interface AudioOutputPosition {
    val lastProvidedFramePositionMs: Long?
}
