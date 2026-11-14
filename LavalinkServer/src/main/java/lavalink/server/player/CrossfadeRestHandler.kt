package lavalink.server.player

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.arbjerg.lavalink.api.AudioPluginInfoModifier
import dev.arbjerg.lavalink.protocol.v4.*
import lavalink.server.io.SocketServer
import lavalink.server.player.crossfade.CrossfadeController
import lavalink.server.util.*
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

@RestController
class CrossfadeRestHandler(
    private val socketServer: SocketServer,
    private val pluginInfoModifiers: List<AudioPluginInfoModifier>,
) {

    companion object {
        private val log = LoggerFactory.getLogger(CrossfadeRestHandler::class.java)
    }

    @GetMapping("/v4/sessions/{sessionId}/players/{guildId}/crossfade")
    private fun getCrossfade(@PathVariable sessionId: String, @PathVariable guildId: Long): ResponseEntity<CrossfadeState> {
        val context = socketContext(socketServer, sessionId)
        return ResponseEntity.ok(existingPlayer(context, guildId).crossfadeState(context))
    }

    @PatchMapping("/v4/sessions/{sessionId}/players/{guildId}/crossfade")
    private fun patchCrossfade(
        @RequestBody update: CrossfadeSettingsUpdate,
        @PathVariable sessionId: String,
        @PathVariable guildId: Long
    ): ResponseEntity<CrossfadeState> {
        val context = socketContext(socketServer, sessionId)
        val player = context.getPlayer(guildId)

        update.durationMs.ifPresent {
            if (it != null && it !in CrossfadeController.MIN_DURATION_MS..CrossfadeController.MAX_DURATION_MS) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "durationMs must be between ${CrossfadeController.MIN_DURATION_MS} and ${CrossfadeController.MAX_DURATION_MS}"
                )
            }
        }

        log.debug("Crossfade settings for guild {}: enabled={}, durationMs={}", guildId, update.enabled, update.durationMs)
        player.crossfade.updateSettings(
            update.enabled.ifPresent { it },
            update.durationMs.ifPresent { it },
            update.enabled.isPresent(),
            update.durationMs.isPresent()
        )
        return ResponseEntity.ok(player.crossfadeState(context))
    }

    @GetMapping("/v4/sessions/{sessionId}/players/{guildId}/crossfade/next")
    private fun getNext(@PathVariable sessionId: String, @PathVariable guildId: Long): ResponseEntity<NextTrack> {
        val context = socketContext(socketServer, sessionId)
        val next = existingPlayer(context, guildId).crossfadeState(context).next
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No next track is reserved")
        return ResponseEntity.ok(next)
    }

    /**
     * Reserves (or replaces) the next track. The response is the loading outcome: only a 200 means this
     * track is reserved; any error leaves nothing reserved. A running fade is cancelled.
     */
    @PutMapping("/v4/sessions/{sessionId}/players/{guildId}/crossfade/next")
    private fun putNext(
        @RequestBody update: NextTrackUpdate,
        @PathVariable sessionId: String,
        @PathVariable guildId: Long
    ): ResponseEntity<NextTrack> {
        val context = socketContext(socketServer, sessionId)
        val player = existingPlayer(context, guildId)

        if (update.encoded.isPresent() == update.identifier.isPresent()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Specify exactly one of encoded and identifier")
        }

        val automix = parseAutomix(update.automix.ifPresent { it })

        log.debug(
            "Reserve next track for guild {} in session {} from {}", guildId, sessionId,
            if (update.encoded.isPresent()) "an encoded track" else "identifier '${(update.identifier as? Omissible.Present)?.value}'"
        )

        val decoded: AudioTrack? = update.encoded.ifPresent {
            try {
                decodeTrack(context.audioPlayerManager, it)
            } catch (e: kotlin.Exception) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message ?: "Invalid encoded track", e)
            }
        }

        val token = when (val begin = player.crossfade.begin()) {
            is CrossfadeController.Begin.Token -> begin.value
            CrossfadeController.Begin.NotPlaying ->
                throw ResponseStatusException(HttpStatus.CONFLICT, "Player is not playing a track")
        }

        val track = try {
            decoded ?: loadIdentifier(context.audioPlayerManager, (update.identifier as Omissible.Present).value)
        } catch (e: ResponseStatusException) {
            player.crossfade.fail(token)
            throw e
        }
        log.debug("Loaded '{}' to crossfade for guild {}", track.info.title, guildId)
        update.userData.ifPresentAndNotNull { track.userData = it }

        when (player.crossfade.complete(token, track, automix)) {
            CrossfadeController.Completion.READY -> {}
            CrossfadeController.Completion.STALE ->
                throw ResponseStatusException(HttpStatus.CONFLICT, "The reservation was replaced or cleared while loading")

            CrossfadeController.Completion.NOT_PLAYING ->
                throw ResponseStatusException(HttpStatus.CONFLICT, "Player stopped playing while the next track loaded")
        }

        return ResponseEntity.ok(
            player.crossfadeState(context).next
                ?: throw ResponseStatusException(HttpStatus.CONFLICT, "The reservation was consumed while loading")
        )
    }

    @DeleteMapping("/v4/sessions/{sessionId}/players/{guildId}/crossfade/next")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    private fun deleteNext(@PathVariable sessionId: String, @PathVariable guildId: Long) {
        val context = socketContext(socketServer, sessionId)
        log.debug("Clear next track for guild {} in session {}", guildId, sessionId)
        existingPlayer(context, guildId).crossfade.clearReservation()
    }

    private fun parseAutomix(request: Automix?): CrossfadeController.Automix {
        if (request == null) return CrossfadeController.Automix.NONE
        fun bad(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
        request.exitMs?.let { if (it < 0) bad("automix.exitMs must not be negative") }
        request.entryMs?.let { if (it < 0) bad("automix.entryMs must not be negative") }
        request.entryGainDb?.let { if (!it.isFinite() || it !in -30.0..12.0) bad("automix.entryGainDb must be between -30 and 12") }
        request.fadeMs?.let {
            if (it !in CrossfadeController.MIN_DURATION_MS..CrossfadeController.MAX_DURATION_MS) {
                bad("automix.fadeMs must be between ${CrossfadeController.MIN_DURATION_MS} and ${CrossfadeController.MAX_DURATION_MS}")
            }
        }
        val effects = request.effects?.let { e ->
            e.muffleHz?.let { if (it !in 100..8000) bad("automix.effects.muffleHz must be between 100 and 8000") }
            e.leadMs?.let { if (it !in 0..CrossfadeController.MAX_LEAD_MS) bad("automix.effects.leadMs must be between 0 and ${CrossfadeController.MAX_LEAD_MS}") }
            e.currentBpm?.let { if (!it.isFinite() || it !in 40.0..250.0) bad("automix.effects.currentBpm must be between 40 and 250") }
            e.nextBpm?.let { if (!it.isFinite() || it !in 40.0..250.0) bad("automix.effects.nextBpm must be between 40 and 250") }
            CrossfadeController.Effects(e.muffleExit, e.muffleEntry, e.muffleHz, e.leadMs, e.currentBpm, e.nextBpm, e.tempoMatch, e.beatSync)
        }
        return CrossfadeController.Automix(request.exitMs, request.entryMs, request.entryGainDb, request.fadeMs, effects)
    }

    private fun loadIdentifier(manager: com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager, identifier: String): AudioTrack {
        val item = try {
            loadAudioItem(manager, identifier)
        } catch (ex: FriendlyException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, ex.message, getRootCause(ex))
        }
        if (item !is AudioTrack) throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            if (item == null) "No matches found for identifier" else "Cannot reserve a playlist or search result"
        )
        return item
    }

    private fun LavalinkPlayer.crossfadeState(context: lavalink.server.io.SocketContext): CrossfadeState {
        val info = crossfade.info()
        return CrossfadeState(
            CrossfadeSettings(info.settings.enabled, info.settings.durationMs),
            info.fading,
            info.next?.let {
                NextTrack(
                    it.track.toTrack(context.audioPlayerManager, pluginInfoModifiers),
                    it.willFade,
                    it.mode.name.lowercase(),
                    it.plan?.takeIf { plan -> plan.mode == CrossfadeController.Mode.AUTOMIX }
                        ?.let { plan ->
                            Automix(
                                plan.exitMs, plan.entryMs, plan.entryGainDb, plan.fadeMs,
                                plan.fx?.let { fx ->
                                    AutomixEffects(
                                        muffleExit = fx.muffleExit, muffleEntry = fx.muffleEntry, muffleHz = fx.muffleHz.toInt(),
                                        leadMs = fx.leadMs, tempoMatch = fx.tempoRatio != null, beatSync = fx.beatSync
                                    )
                                }
                            )
                        }
                )
            }
        )
    }
}
