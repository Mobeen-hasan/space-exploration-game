package com.mobeen.space

import com.badlogic.gdx.Input.Keys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    // ------------------------------------------------------------------ ruins

    private fun atEntrance(): GameHarness {
        val h = landedHarness()
        val planet = PlanetGenerator.forChunk(0, 0)!!
        h.game.enterSurface(planet, Terrain.ENTRANCE_X - 20f)
        h.frame(3)
        assertTrue(h.screen is SurfaceScreen)
        h.frame(10)
        return h
    }

    @Test
    fun entranceOpensTheRuinsAndStairsLeadBackToTheSurface() {
        val h = atEntrance()
        h.press(Keys.E)
        h.frame(3)
        assertTrue(h.screen is DungeonScreen, "E at the entrance should open the ruins")
        assertNotNull(h.state.dungeon)
        h.frame(60)

        h.hold(Keys.A, 1) // the arrival tile is right beside the up stairs
        h.frame(4)
        assertTrue(h.screen is DungeonScreen, "stepping onto the stairs must not take them")
        assertNotNull(h.state.dungeon)

        h.press(Keys.E) // now choose to leave
        h.frame(4)
        assertTrue(h.screen is SurfaceScreen, "the entrance stairs should lead back to the surface")
        assertNull(h.state.dungeon)
        h.frame(60)
    }

    @Test
    fun ruinsCanBeWalkedFoughtThroughAndRenderedOnEveryPlanetType() {
        for (type in PlanetType.entries) {
            val h = GameHarness()
            h.frame(2)
            h.game.enterDungeon(TestPlanets.ofType(type))
            h.frame(3)
            assertTrue(h.screen is DungeonScreen, "$type")
            val run = h.state.dungeon!!
            // Put a sleeping monster beside the player so combat, floating text and health bars are drawn.
            run.level.monsters.add(Monster(MonsterType.HUSK, run.px + 1, run.py, 30, 1f, AiState.SLEEPING))
            for (key in listOf(Keys.D, Keys.D, Keys.W, Keys.S, Keys.A, Keys.SPACE)) {
                h.press(key)
                h.hold(key, 3)
                h.frame(5)
            }
            h.frame(30)
            assertTrue(h.screen is DungeonScreen || h.screen is InteriorScreen || h.screen is SurfaceScreen, "$type")
        }
    }

    @Test
    fun dyingInTheRuinsWakesYouInTheShipWithAPenalty() {
        val h = atEntrance()
        h.press(Keys.E)
        h.frame(3)
        assertTrue(h.screen is DungeonScreen)

        val credits = h.state.stats.credits
        h.state.stats.health = 0f
        h.frame(3)
        assertTrue(h.screen is InteriorScreen, "collapsing in the ruins should move you to the ship")
        assertNull(h.state.dungeon)
        assertTrue(h.state.stats.credits < credits)
        h.frame(30)
    }

    @Test
    fun usingTheStairsDownRendersTheNextFloor() {
        val h = atEntrance()
        h.press(Keys.E)
        h.frame(3)
        val run = h.state.dungeon!!
        run.px = run.level.downArriveX
        run.py = run.level.downArriveY
        h.hold(Keys.D, 1)
        h.frame(5)
        assertEquals(1, run.depth, "stepping onto the stairs must not take them")
        h.press(Keys.E)
        h.frame(30)
        assertEquals(2, run.depth)
        assertTrue(h.screen is DungeonScreen)
    }

    // ------------------------------------------------------------------ bounty board

    private fun atTerminal(): GameHarness {
        val h = GameHarness()
        h.frame(3)
        h.press(Keys.I)
        h.frame(3)
        assertTrue(h.screen is InteriorScreen)
        h.hold(Keys.A, 40) // from the cockpit spawn to the terminal
        h.press(Keys.E)
        h.frame(2)
        return h
    }

    @Test
    fun acceptingAndAbandoningAContractAtTheTerminal() {
        val h = atTerminal()
        assertNull(h.state.bounties.active)

        repeat(3) { h.press(Keys.S) } // down to "Bounty board"
        h.press(Keys.ENTER)
        h.frame(2)
        assertTrue(h.state.bounties.available.isNotEmpty(), "the board should list contracts")
        val first = h.state.bounties.available.first()

        h.press(Keys.ENTER) // accept the first contract
        h.frame(2)
        assertEquals(first.id, h.state.bounties.active?.id)
        h.frame(30)

        h.press(Keys.S) // from the disabled "ACTIVE" row down to "Abandon this contract"
        h.press(Keys.ENTER)
        h.frame(2)
        assertNull(h.state.bounties.active)
        h.press(Keys.ESCAPE)
        h.frame(30)
    }

    @Test
    fun theTargetWaitsInTheRuinsOnlyOnTheContractPlanet() {
        val h = GameHarness()
        h.frame(3)
        val target = TestPlanets.ofType(PlanetType.ROCKY, id = 123L)
        val other = TestPlanets.ofType(PlanetType.ROCKY, id = 456L)
        val c = Contract(5L, "Joss 'Cinder' Pike", "hijacked a barge", target.id, "Test", target.type, 2000, 400, 1)
        h.state.bounties.available.add(c)
        assertTrue(h.state.bounties.accept(c))

        h.game.enterDungeon(target)
        h.frame(3)
        assertTrue(h.screen is DungeonScreen)
        assertEquals(c, h.state.dungeon?.contract)
        h.frame(30)

        h.state.dungeon = null
        h.game.enterDungeon(other)
        h.frame(3)
        assertNull(h.state.dungeon?.contract, "other planets have no target")
        h.frame(30)
    }
}
