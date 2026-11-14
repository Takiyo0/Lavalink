package lavalink.server.player.crossfade

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TransitionScheduleTest {
    @Test fun `threshold is fade duration plus two seconds for both tracks`() {
        val s = TransitionSchedule()
        var token = s.prepare("next")
        assertTrue(s.prepared(token, true, 3000, 4999, 5000, false))
        assertEquals(TransitionSchedule.Phase.READY_NORMAL, s.snapshot().phase)
        token = s.prepare("next")
        assertTrue(s.prepared(token, true, 3000, 5000, 4999, false))
        assertEquals(TransitionSchedule.Phase.READY_NORMAL, s.snapshot().phase)
        token = s.prepare("next")
        assertTrue(s.prepared(token, true, 3000, 5000, 5000, false))
        assertEquals(TransitionSchedule.Phase.READY_FADE, s.snapshot().phase)
        assertFalse(s.startFade(3001, 3000))
        assertTrue(s.startFade(3000, 3000))
        assertTrue(s.cancelFade())
        assertEquals(TransitionSchedule.Phase.READY_NORMAL, s.snapshot().phase)
        assertEquals("next", s.snapshot().reservationId)
    }

    @Test fun `unknown duration and disabled mode retain normal transition`() {
        val s = TransitionSchedule()
        for ((enabled, duration, stream) in listOf(Triple(false, 5000L, false), Triple(true, null, false), Triple(true, 5000L, true))) {
            val token = s.prepare("next")
            assertTrue(s.prepared(token, enabled, 3000, 5000, duration, stream))
            assertEquals(TransitionSchedule.Phase.READY_NORMAL, s.snapshot().phase)
        }
    }

    @Test fun `replaced and cleared loads cannot revive stale reservations`() {
        val s = TransitionSchedule()
        val old = s.prepare("old")
        val current = s.prepare("current")
        assertFalse(s.prepared(old, true, 3000, 10000, 10000, false))
        assertFalse(s.loadFailed(old))
        assertTrue(s.prepared(current, true, 3000, 10000, 10000, false))
        s.clear()
        assertFalse(s.prepared(current, true, 3000, 10000, 10000, false))
        assertEquals(TransitionSchedule.Phase.EMPTY, s.snapshot().phase)
    }

    @Test fun `skipped or cancelled fades cannot be revived by a settings change`() {
        val s = TransitionSchedule()
        s.prepared(s.prepare("a"), true, 3000, 10000, 10000, false)
        assertTrue(s.forbidFade())
        assertFalse(s.reassess(true, 3000, 10000, 10000, false))
        assertEquals(TransitionSchedule.Phase.READY_NORMAL, s.snapshot().phase)

        s.prepared(s.prepare("b"), true, 3000, 10000, 10000, false)
        assertTrue(s.startFade(3000, 3000))
        assertTrue(s.cancelFade())
        assertFalse(s.reassess(true, 3000, 10000, 10000, false))
    }

    @Test fun `settings changes move a ready reservation between normal and fade`() {
        val s = TransitionSchedule()
        s.prepared(s.prepare("a"), false, 3000, 10000, 10000, false)
        assertTrue(s.reassess(true, 3000, 9000, 10000, false))
        assertEquals(TransitionSchedule.Phase.READY_FADE, s.snapshot().phase)
        assertFalse(s.reassess(true, 3000, 9000, 10000, false))
        assertTrue(s.reassess(true, 3000, 4000, 10000, false))
        assertEquals(TransitionSchedule.Phase.READY_NORMAL, s.snapshot().phase)
        assertTrue(s.reassess(true, 3000, 9000, 10000, false))
        assertTrue(s.startFade(3000, 3000))
        assertFalse(s.reassess(false, 3000, 9000, 10000, false), "no re-evaluation while fading")
        s.clear()
        assertFalse(s.reassess(true, 3000, 9000, 10000, false), "no re-evaluation when empty")
    }

    @Test fun `caller decided eligibility and fade start`() {
        val s = TransitionSchedule()
        assertTrue(s.prepared(s.prepare("a"), true))
        assertEquals(TransitionSchedule.Phase.READY_FADE, s.snapshot().phase)
        assertTrue(s.beginFade())
        assertEquals(TransitionSchedule.Phase.FADING, s.snapshot().phase)
        assertFalse(s.beginFade())

        assertTrue(s.prepared(s.prepare("b"), false))
        assertEquals(TransitionSchedule.Phase.READY_NORMAL, s.snapshot().phase)
        assertFalse(s.beginFade())
        assertTrue(s.reassess(true))
        assertEquals(TransitionSchedule.Phase.READY_FADE, s.snapshot().phase)
    }
}
