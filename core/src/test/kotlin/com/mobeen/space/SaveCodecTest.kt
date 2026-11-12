package com.mobeen.space

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveCodecTest {
    private fun filledState(): GameState {
        val s = GameState()
        s.stats.health = 71.5f
        s.stats.energy = 33f
        s.stats.food = 64f
        s.stats.air = 12f
        s.stats.fuel = 48.25f
        s.stats.hull = 90f
        s.stats.credits = 1234
        s.stats.day = 7
        s.clock = 987.5f
        s.discovered.addAll(listOf(0L, 42L, -4294967290L))
        return s
    }

    @Test
    fun flyingShipRoundTrips() {
        val a = filledState()
        a.ship.restore(120f, -340f, 15f, -22f, 1.25f)
        val b = GameState()
        assertTrue(SaveCodec.decode(b, SaveCodec.encode(a)))

        assertEquals(71.5f, b.stats.health)
        assertEquals(1234, b.stats.credits)
        assertEquals(7, b.stats.day)
        assertEquals(987.5f, b.clock)
        assertEquals(a.discovered, b.discovered)
        assertEquals(120f, b.ship.pos.x)
        assertEquals(-340f, b.ship.pos.y)
        assertEquals(15f, b.ship.vel.x)
        assertEquals(1.25f, b.ship.angle)
        assertNull(b.ship.landedOn)
    }

    @Test
    fun landedShipRoundTripsIncludingNegativeChunks() {
        var found: Planet? = null
        loop@ for (cx in -6..-1) for (cy in -6..6) {
            val p = PlanetGenerator.forChunk(cx, cy)
            if (p != null) { found = p; break@loop }
        }
        val planet = assertNotNull(found)

        val a = filledState()
        val angle = 2.2f
        a.ship.land(planet, cos(angle), sin(angle))
        val b = GameState()
        assertTrue(SaveCodec.decode(b, SaveCodec.encode(a)))

        assertEquals(planet.id, b.ship.landedOn?.id)
        assertEquals(planet.name, b.ship.landedOn?.name)
        assertEquals(a.ship.pos.x, b.ship.pos.x, 0.01f)
        assertEquals(a.ship.landAngleRad, b.ship.landAngleRad, 0.0001f)
    }

    @Test
    fun planetCanBeRebuiltFromItsIdForAnyChunk() {
        for (cx in -5..5) for (cy in -5..5) {
            val p = PlanetGenerator.forChunk(cx, cy) ?: continue
            assertEquals(p.name, SaveCodec.planetFromId(p.id)?.name)
        }
    }

    @Test
    fun missingOrDamagedDataLeavesAFreshGame() {
        assertFalse(SaveCodec.decode(GameState(), emptyMap()))
        val broken = SaveCodec.encode(filledState()).toMutableMap()
        broken["fuel"] = "banana"
        assertFalse(SaveCodec.decode(GameState(), broken))
        val wrongVersion = SaveCodec.encode(filledState()).toMutableMap()
        wrongVersion["v"] = "99"
        assertFalse(SaveCodec.decode(GameState(), wrongVersion))
    }

    @Test
    fun mindIsSavedAndOldSavesStartCalm() {
        val a = filledState()
        a.stats.mind = 37f
        val b = GameState()
        assertTrue(SaveCodec.decode(b, SaveCodec.encode(a)))
        assertEquals(37f, b.stats.mind)

        val old = SaveCodec.encode(a).filterKeys { it != "mind" }
        val c = GameState()
        assertTrue(SaveCodec.decode(c, old))
        assertEquals(Stats.MAX, c.stats.mind)
    }
}
