package com.mobeen.space

import com.badlogic.gdx.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerrainTest {
    private fun planetOf(type: PlanetType, id: Long): Planet = Planet(
        id = id, name = "Test", type = type, x = 0f, y = 0f, radius = 150f, gravityG = 1f,
        temperatureC = 20, atmosphere = "Thin", dayHours = 24f,
        c0 = Color(type.c0[0], type.c0[1], type.c0[2], 1f),
        c1 = Color(type.c1[0], type.c1[1], type.c1[2], 1f),
        c2 = Color(type.c2[0], type.c2[1], type.c2[2], 1f),
        atmoColor = Color(type.atmo[0], type.atmo[1], type.atmo[2], 1f),
        noiseSeed = 1f, spin = 0.05f,
    )

    private val seeds = longArrayOf(1L, 42L, 987654321L, -5L)

    @Test
    fun landingPadIsFlatAtZero() {
        for (type in PlanetType.entries) for (seed in seeds) {
            val t = Terrain(planetOf(type, seed))
            var x = -Terrain.PAD_FLAT
            while (x <= Terrain.PAD_FLAT) {
                assertEquals(0f, t.height(x), 0.001f, "$type seed $seed at x=$x")
                x += 10f
            }
        }
    }

    @Test
    fun worldLoopsSeamlessly() {
        for (type in PlanetType.entries) {
            val t = Terrain(planetOf(type, 7L))
            for (x in listOf(-500f, 0f, 1234.5f, 9000f, 20000f)) {
                assertEquals(t.height(x), t.height(x + Terrain.WIDTH), 0.01f, "$type x=$x")
                assertEquals(t.height(x), t.height(x - Terrain.WIDTH), 0.01f, "$type x=$x")
            }
        }
    }

    @Test
    fun slopesStayWalkableAndHeightsStayBounded() {
        for (type in PlanetType.entries) for (seed in seeds) {
            val t = Terrain(planetOf(type, seed))
            var maxSlope = 0f
            var maxH = 0f
            var x = 0f
            while (x < Terrain.WIDTH) {
                maxSlope = maxOf(maxSlope, abs(t.slope(x)))
                maxH = maxOf(maxH, abs(t.height(x)))
                x += 3f
            }
            assertTrue(maxSlope < SurfacePlayer.MAX_SLOPE, "$type seed $seed max slope $maxSlope")
            assertTrue(maxH < 350f, "$type seed $seed max height $maxH")
        }
    }

    @Test
    fun sameSeedGivesSameTerrainAndDifferentSeedsDiffer() {
        val a = Terrain(planetOf(PlanetType.ROCKY, 5L))
        val b = Terrain(planetOf(PlanetType.ROCKY, 5L))
        val c = Terrain(planetOf(PlanetType.ROCKY, 6L))
        assertEquals(a.height(4321f), b.height(4321f), 0f)
        assertTrue(abs(a.height(4321f) - c.height(4321f)) > 0.01f)
    }

    @Test
    fun liquidsAppearOnTheRightWorlds() {
        assertEquals(Liquid.WATER, Terrain(planetOf(PlanetType.OCEAN, 1L)).liquid)
        assertEquals(Liquid.LAVA, Terrain(planetOf(PlanetType.LAVA, 1L)).liquid)
        assertEquals(Liquid.ACID, Terrain(planetOf(PlanetType.TOXIC, 1L)).liquid)
        assertEquals(Liquid.NONE, Terrain(planetOf(PlanetType.DESERT, 1L)).liquid)
    }
}
