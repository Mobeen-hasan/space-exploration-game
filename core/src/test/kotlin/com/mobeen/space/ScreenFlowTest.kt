package com.mobeen.space

import com.badlogic.gdx.Input.Keys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Walks through the screen changes that crashed in play: every transition, drawn frame by frame. */
class ScreenFlowTest {
    private fun landedHarness(): GameHarness {
        val h = GameHarness()
        h.frame(5)
        val planet = PlanetGenerator.forChunk(0, 0)!!
        h.state.ship.land(planet, 1f, 0f)
        h.frame(5)
        return h
    }

    @Test
    fun spaceScreenRendersAndShipInteriorOpensFromFlight() {
        val h = GameHarness()
        h.frame(30)
        assertTrue(h.screen is GameScreen)

        h.press(Keys.I)
        h.frame(2)
        assertTrue(h.screen is InteriorScreen, "pressing I in flight should open the ship interior")
        h.frame(120)
    }

    @Test
    fun cockpitReturnsToFlightWithoutCrashing() {
        val h = GameHarness()
        h.press(Keys.I)
        h.frame(10)
        assertTrue(h.screen is InteriorScreen)

        h.press(Keys.E) // arrives standing at the cockpit
        h.frame(3)
        assertTrue(h.screen is GameScreen, "using the cockpit should return to flight")
        h.frame(60)
    }

    @Test
    fun landedShipExitsToSurfaceBoardsAndUsesTheAirlock() {
        val h = landedHarness()

        h.press(Keys.E)
        h.frame(3)
        assertTrue(h.screen is SurfaceScreen, "EXIT on a landed ship should open the surface")
        h.frame(90)

        h.hold(Keys.A, 10) // step back toward the ship
        h.press(Keys.E)
        h.frame(3)
        assertTrue(h.screen is InteriorScreen, "BOARD should open the ship interior")
        h.frame(60)

        h.hold(Keys.A, 40) // walk to the airlock
        h.press(Keys.E)
        h.frame(3)
        assertTrue(h.screen is SurfaceScreen, "the airlock should lead back outside")
        h.frame(60)
    }

    @Test
    fun landedShipCockpitLaunchesBackToSpace() {
        val h = landedHarness()
        h.press(Keys.E)
        h.frame(3)
        h.hold(Keys.A, 10)
        h.press(Keys.E)
        h.frame(3)
        assertTrue(h.screen is InteriorScreen)

        h.hold(Keys.D, 180) // walk from the airlock to the cockpit at the far right
        h.press(Keys.E)
        h.frame(3)
        assertTrue(h.screen is GameScreen, "the cockpit should return to flight")
        h.frame(60)
    }

    @Test
    fun sleepingRestoresEnergySavesAndStartsANewDay() {
        val h = GameHarness()
        h.state.stats.energy = 10f
        h.press(Keys.I)
        h.frame(5)
        assertTrue(h.screen is InteriorScreen)

        h.hold(Keys.A, 150) // walk from the cockpit to the bunk (x = 215)
        h.hold(Keys.D, 0)
        val before = GdxTestEnv.flushCount
        // The bunk is the nearest station only when standing close, so nudge into range if needed.
        h.press(Keys.E)
        h.frame(300)
        if (h.state.stats.day == 1) {
            // Not in range of the bunk: walk a bit and retry.
            h.hold(Keys.D, 15)
            h.press(Keys.E)
            h.frame(300)
        }
        assertEquals(2, h.state.stats.day)
        assertEquals(100f, h.state.stats.energy, 5f)
        assertTrue(GdxTestEnv.flushCount > before, "sleeping should save the game")
    }

    @Test
    fun collapsingWakesYouInTheBunkWithAPenalty() {
        val h = landedHarness()
        h.press(Keys.E)
        h.frame(3)
        assertTrue(h.screen is SurfaceScreen)

        val creditsBefore = h.state.stats.credits
        h.state.stats.energy = 0f // passing out from exhaustion
        h.frame(3)
        assertTrue(h.screen is InteriorScreen, "collapsing should move you to the ship interior")
        assertTrue(h.state.stats.credits < creditsBefore)
        assertTrue(h.state.stats.health > 0f)
        h.frame(30)
    }

    @Test
    fun everyPlanetTypeCanBeWalkedOnForAWhile() {
        for (type in PlanetType.entries) {
            val h = GameHarness()
            h.frame(2)
            val base = PlanetGenerator.forChunk(0, 0)!!
            val planet = Planet(
                base.id, base.name, type, base.x, base.y, base.radius, base.gravityG, base.temperatureC,
                base.atmosphere, base.dayHours, base.c0, base.c1, base.c2, base.atmoColor, base.noiseSeed, base.spin,
            )
            h.game.enterSurface(planet)
            h.frame(2)
            assertTrue(h.screen is SurfaceScreen, "$type")
            h.hold(Keys.D, 120)
            h.press(Keys.W)
            h.frame(60)
        }
    }
}
