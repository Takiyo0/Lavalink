package lavalink.server.player.filters

import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.filter.UniversalPcmAudioFilter
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats
import dev.arbjerg.lavalink.api.AudioFilterExtension
import dev.arbjerg.lavalink.api.IPlayer
import dev.arbjerg.lavalink.api.PlayerAudioFilterExtension
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class FilterChainPlayerTest {
    @Test fun `context-aware and legacy extensions receive their proper callbacks`() {
        val player = mock(IPlayer::class.java)
        val output = mock(UniversalPcmAudioFilter::class.java)
        var seen: IPlayer? = null
        var legacyCalls = 0
        val calls = mutableListOf<String>()
        val aware = object : PlayerAudioFilterExtension {
            override val name = "aware"
            override val afterBuiltInFilters = true
            override fun isEnabled(data: JsonElement) = true
            override fun buildForPlayer(player: IPlayer, data: JsonElement, format: AudioDataFormat, output: FloatPcmAudioFilter): FloatPcmAudioFilter {
                seen = player
                calls.add("aware")
                return output
            }
        }
        val legacy = object : AudioFilterExtension {
            override val name = "legacy"
            override fun isEnabled(data: JsonElement) = true
            override fun build(data: JsonElement, format: AudioDataFormat?, output: FloatPcmAudioFilter?): FloatPcmAudioFilter? {
                legacyCalls++
                calls.add("legacy")
                return output
            }
        }
        val chain = FilterChain()
        chain.parsePluginConfigs(mapOf("aware" to JsonObject(emptyMap()), "legacy" to JsonObject(emptyMap())), listOf(legacy, aware))
        chain.buildChainForPlayer(player, null, StandardAudioDataFormats.DISCORD_OPUS, output)
        assertSame(player, seen)
        assertEquals(1, legacyCalls)
        assertEquals(listOf("aware", "legacy"), calls)
    }
}
