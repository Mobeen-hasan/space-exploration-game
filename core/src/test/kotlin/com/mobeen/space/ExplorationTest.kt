package com.mobeen.space

import com.badlogic.gdx.Input.Keys
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExplorationTest {
    @Test
    fun everyWorldHasFourSignalsAndALandmarkSpreadAroundIt() {
        for (type in PlanetType.entries) for (id in 1L..10L) {
            val planet = TestPlanets.ofType(type, id = id)
            val signals = Discoveries.signalsFor(planet)
            assertEquals(Discoveries.SIGNALS_PER_PLANET, signals.size, "$type $id")
            assertTrue(signals.last().landmark, "the landmark comes last")
            assertEquals(1, signals.count { it.landmark })
            assertEquals(signals.map { it.index }, signals.indices.toList())
            for (s in signals) {
                assertTrue(minOf(s.x, Terrain.WIDTH - s.x) > 600f, "$type $id: ${s.name} too close to the ship")
                assertTrue(abs(s.x - Terrain.ENTRANCE_X) > 600f, "$type $id: ${s.name} too close to the ruins")
                assertTrue(s.reward > 0 && s.name.isNotBlank() && s.lore.isNotBlank())
            }
            val xs = signals.map { it.x }.sorted()
            for (i in 1 until xs.size) assertTrue(xs[i] - xs[i - 1] > 800f, "$type $id: signals bunched up")
            assertEquals(signals.map { it.x }, Discoveries.signalsFor(planet).map { it.x }, "the same every time")
        }
    }

    @Test
    fun theSignalPointerGoesTheShortWayAroundThePlanet() {
        assertEquals(100f, Discoveries.loopOffset(600f, 500f), 0.01f)
        assertEquals(-100f, Discoveries.loopOffset(Terrain.WIDTH - 50f, 50f), 0.01f, "wraps around the loop")
        val planet = TestPlanets.ofType(PlanetType.ROCKY, id = 3L)
        val signals = Discoveries.signalsFor(planet)
        val first = signals.first()
        assertEquals(first, Discoveries.nearestUnscanned(signals, emptySet(), first.x))
        assertTrue(Discoveries.nearestUnscanned(signals, setOf(first.key), first.x) != first)
        assertNull(Discoveries.nearestUnscanned(signals, signals.map { it.key }.toSet(), 0f))
    }

    @Test
    fun chartsAndScansAreSavedAndANewGameClearsThem() {
        val a = GameState()
        a.charted.addAll(listOf(5L, -4294967290L))
        a.scanned.addAll(listOf("5:0", "5:4"))
        val b = GameState()
        assertTrue(SaveCodec.decode(b, SaveCodec.encode(a)))
        assertEquals(a.charted, b.charted)
        assertEquals(a.scanned, b.scanned)
        b.resetForNewGame(false)
        assertTrue(b.charted.isEmpty() && b.scanned.isEmpty())
        val old = SaveCodec.encode(a).filterKeys { it != "charted" && it != "scanned" }
        val c = GameState()
        assertTrue(SaveCodec.decode(c, old))
        assertTrue(c.charted.isEmpty())
    }

    @Test
    fun scanningASignalLogsItOncePaysAndPointsOnward() {
        val h = GameHarness()
        h.frame(2)
        val planet = TestPlanets.ofType(PlanetType.TOXIC, id = 44L)
        val signal = Discoveries.signalsFor(planet).first()
        h.game.enterSurface(planet, signal.x)
        h.frame(3)
        assertTrue(h.screen is SurfaceScreen)
        val credits = h.state.stats.credits
        h.press(Keys.E)
        h.frame(2)
        assertTrue(signal.key in h.state.scanned)
        assertTrue(planet.id in h.state.discovered, "scanning logs the world too")
        assertEquals(credits + signal.reward, h.state.stats.credits)
        h.press(Keys.E)
        h.frame(2)
        assertEquals(credits + signal.reward, h.state.stats.credits, "a signal pays once")
        h.frame(30)
    }

    @Test
    fun landmarksCanBeScannedToo() {
        val h = GameHarness()
        h.frame(2)
        val planet = TestPlanets.ofType(PlanetType.LAVA, id = 45L)
        val mark = Discoveries.signalsFor(planet).last()
        h.game.enterSurface(planet, mark.x)
        h.frame(3)
        h.press(Keys.E)
        h.frame(2)
        assertTrue(mark.key in h.state.scanned)
        h.frame(20)
    }

    @Test
    fun planetsSeenOnRadarAreChartedForTheMap() {
        val h = GameHarness()
        h.frame(5)
        assertTrue(h.state.charted.isNotEmpty(), "the planet near the start shows up on radar")
        assertTrue(h.state.discovered.isEmpty(), "but it is not visited until you land")
    }

    @Test
    fun theJournalOpensWithJPausesAndShowsTheMap() {
        val h = GameHarness()
        h.frame(3)
        val planet = PlanetGenerator.forChunk(0, 0)!!
        h.state.discovered.add(planet.id)
        h.state.scanned.add(Discoveries.signalsFor(planet).first().key)
        h.press(Keys.J)
        assertTrue(h.game.journal.open)
        assertEquals(listOf(planet.name), h.game.journal.visited(h.state).map { it.name })
        val pos = h.state.ship.pos.cpy()
        h.hold(Keys.UP, 30)
        assertEquals(pos, h.state.ship.pos, "the game is paused while you read")
        h.press(Keys.TAB)
        assertEquals(JournalOverlay.Tab.MAP, h.game.journal.tab)
        h.press(Keys.S) // zoom out
        h.frame(10)
        h.press(Keys.J)
        assertFalse(h.game.journal.open)
    }

    @Test
    fun theJournalOpensOnTheSurfaceAndFromTheShipTerminal() {
        val h = GameHarness()
        h.frame(2)
        h.game.enterSurface(TestPlanets.ofType(PlanetType.ICE, id = 46L))
        h.frame(3)
        h.press(Keys.J)
        assertTrue(h.game.journal.open)
        h.frame(10)
        h.press(Keys.ESCAPE)
        assertFalse(h.game.journal.open)
        assertFalse(h.game.pauseMenu.open, "Esc closed the journal, not opened the pause menu")

        h.press(Keys.I) // nothing on the surface; go back to space first
        h.game.returnToSpace()
        h.frame(3)
        h.press(Keys.I)
        h.frame(3)
        h.hold(Keys.A, 40) // to the terminal
        h.press(Keys.E)
        repeat(5) { h.press(Keys.S) } // down to "Journal and star map"
        h.press(Keys.ENTER)
        h.frame(2)
        assertTrue(h.game.journal.open)
        h.frame(10)
    }
}
