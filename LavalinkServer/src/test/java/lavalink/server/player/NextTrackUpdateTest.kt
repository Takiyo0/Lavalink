package lavalink.server.player

import dev.arbjerg.lavalink.protocol.v4.NextTrackUpdate
import dev.arbjerg.lavalink.protocol.v4.Omissible
import dev.arbjerg.lavalink.protocol.v4.json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NextTrackUpdateTest {
    @Test fun `null user data is accepted and omitted user data stays omitted`() {
        val withNull = json.decodeFromString(NextTrackUpdate.serializer(), """{"encoded":"abc","userData":null}""")
        assertEquals(Omissible.Present<String?>("abc"), withNull.encoded.let { Omissible.Present<String?>((it as Omissible.Present).value) })
        assertEquals(null, (withNull.userData as Omissible.Present).value)

        val omitted = json.decodeFromString(NextTrackUpdate.serializer(), """{"identifier":"x"}""")
        assertTrue(omitted.userData is Omissible.Omitted)

        val withObject = json.decodeFromString(NextTrackUpdate.serializer(), """{"identifier":"x","userData":{"a":1}}""")
        assertTrue((withObject.userData as Omissible.Present).value != null)
    }

    @Test fun `automix cues are parsed and optional`() {
        val full = json.decodeFromString(
            NextTrackUpdate.serializer(),
            """{"encoded":"abc","automix":{"exitMs":166380,"entryMs":54120,"entryGainDb":-3.36,"fadeMs":4000}}"""
        )
        val cues = (full.automix as Omissible.Present).value!!
        assertEquals(166380L, cues.exitMs)
        assertEquals(54120L, cues.entryMs)
        assertEquals(-3.36, cues.entryGainDb)
        assertEquals(4000L, cues.fadeMs)

        val partial = json.decodeFromString(NextTrackUpdate.serializer(), """{"encoded":"abc","automix":{"entryMs":1000}}""")
        val some = (partial.automix as Omissible.Present).value!!
        assertEquals(1000L, some.entryMs)
        assertEquals(null, some.exitMs)

        assertEquals(null, (json.decodeFromString(NextTrackUpdate.serializer(), """{"encoded":"a","automix":null}""").automix as Omissible.Present).value)
        assertTrue(json.decodeFromString(NextTrackUpdate.serializer(), """{"encoded":"a"}""").automix is Omissible.Omitted)
    }

    @Test fun `automix effects are parsed and optional`() {
        val update = json.decodeFromString(
            NextTrackUpdate.serializer(),
            """{"encoded":"abc","automix":{"exitMs":166380,"effects":{"muffleExit":true,"muffleHz":600,"leadMs":8000,"currentBpm":171.0,"nextBpm":172.5,"tempoMatch":true,"beatSync":true}}}"""
        )
        val effects = (update.automix as Omissible.Present).value!!.effects!!
        assertEquals(true, effects.muffleExit)
        assertEquals(600, effects.muffleHz)
        assertEquals(8000L, effects.leadMs)
        assertEquals(171.0, effects.currentBpm)
        assertEquals(true, effects.tempoMatch)
        assertEquals(true, effects.beatSync)
        assertEquals(null, effects.muffleEntry)

        val plain = json.decodeFromString(NextTrackUpdate.serializer(), """{"encoded":"abc","automix":{"exitMs":1000}}""")
        assertEquals(null, (plain.automix as Omissible.Present).value!!.effects)
    }
}
