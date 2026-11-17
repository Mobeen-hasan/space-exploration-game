package com.mobeen.space

import com.badlogic.gdx.Input.Keys
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TownTest {
    @Test
    fun aboutAThirdOfWorldsHaveATownAndTheStartingWorldAlwaysDoes() {
        val start = PlanetGenerator.forChunk(0, 0)!!
        assertEquals(0L, start.id)
        assertTrue(Towns.hasTown(start))
        val withTown = (1L..400L).count { Towns.hasTown(TestPlanets.ofType(PlanetType.ROCKY, id = it)) }
        assertTrue(withTown in 90..190, "towns on $withTown of 400 worlds")
        val p = TestPlanets.ofType(PlanetType.DESERT, id = 0L)
        val a = Towns.townFor(p)!!
        val b = Towns.townFor(p)!!
        assertEquals(a.name, b.name, "the same town every visit")
        assertEquals(a.folk.map { it.name + it.role }, b.folk.map { it.name + it.role })
        assertEquals(3, a.folk.size)
        val none = (1L..50L).map { TestPlanets.ofType(PlanetType.ICE, id = it) }.first { !Towns.hasTown(it) }
        assertNull(Towns.townFor(none))
    }

    @Test
    fun theTownStandsOnFlatGroundAndSignalsKeepClear() {
        for (type in PlanetType.entries) {
            val planet = TestPlanets.ofType(type, id = 0L)
            val t = Terrain(planet)
            var x = Towns.TOWN_X - Towns.HALF_WIDTH
            while (x <= Towns.TOWN_X + Towns.HALF_WIDTH) {
                assertEquals(0f, t.height(x), 0.5f, "$type ground at $x")
                x += 20f
            }
            for (s in Discoveries.signalsFor(planet))
                assertTrue(abs(s.x - Towns.TOWN_X) > Towns.HALF_WIDTH + 600f, "$type: ${s.name} is in town")
        }
        assertEquals(0f, Towns.townOffset(Towns.TOWN_X + Terrain.WIDTH), 0.01f, "the town comes round again")
    }

    @Test
    fun theTraderBuysAndSells() {
        val state = GameState()
        val stock = TownServices.stock(TestPlanets.ofType(PlanetType.ROCKY, id = 0L), 3)
        assertEquals(3, stock.size)
        assertTrue(stock.none { it.kind.starter })
        val g = stock.first()
        val price = TownServices.price(g)
        state.stats.credits = price - 1
        assertFalse(TownServices.buy(state, g), "too poor")
        state.stats.credits = price
        assertTrue(TownServices.buy(state, g))
        assertEquals(0, state.stats.credits)
        assertEquals(listOf(g), state.loadout.bag)
        val paid = TownServices.sell(state, 0)
        assertTrue(paid in 1 until price, "selling back loses money")
        assertEquals(paid, state.stats.credits)
        assertTrue(state.loadout.bag.isEmpty())
        assertEquals(0, TownServices.sell(state, 0))
        assertTrue(TownServices.price(Gear(GearKind.ARC_SABER, Quality.RARE)) > TownServices.price(Gear(GearKind.ARC_SABER, Quality.WORN)))
    }

    @Test
    fun theClinicHealsEverythingForAPrice() {
        val state = GameState()
        assertEquals(0, TownServices.treatmentCost(state.stats))
        assertFalse(TownServices.treat(state), "nothing to treat")
        state.stats.health = 40f
        state.stats.brokenLeg = true
        state.stats.infected = true
        val cost = TownServices.treatmentCost(state.stats)
        assertEquals(90 + 40, cost)
        state.stats.credits = cost
        assertTrue(TownServices.treat(state))
        assertEquals(Stats.MAX, state.stats.health)
        assertFalse(state.stats.brokenLeg || state.stats.infected)
        assertEquals(0, state.stats.credits)
    }

    @Test
    fun theDepotFillsUpAsFarAsYourCreditsGo() {
        val state = GameState()
        state.stats.fuel = 20f
        state.stats.credits = 30
        val added = TownServices.refuel(state)
        assertEquals(50f, added, 0.01f)
        assertEquals(70f, state.stats.fuel, 0.01f)
        assertEquals(0, state.stats.credits)
        assertEquals(0f, TownServices.refuel(state), "broke")
        state.stats.credits = 1000
        state.stats.hull = 50f
        TownServices.repair(state)
        assertEquals(Stats.MAX, state.stats.hull, 0.01f)
        assertEquals(1000 - 40, state.stats.credits)
        assertEquals(0f, TownServices.repair(state), "nothing to fix")
    }

    @Test
    fun theBarCalmsYouAndRumorsChartTheMap() {
        val state = GameState()
        state.stats.mind = 50f
        state.stats.credits = 100
        assertTrue(TownServices.buyDrink(state))
        assertEquals(70f, state.stats.mind, 0.01f)
        assertEquals(90, state.stats.credits)
        val here = PlanetGenerator.forChunk(0, 0)!!
        val rnd = Random(1)
        repeat(10) { assertNotNull(TownServices.hearRumor(state, here, rnd)) }
        assertEquals(40, state.stats.credits)
        assertTrue(state.charted.isNotEmpty(), "ten rumors put something on the map")
        state.stats.credits = 0
        assertNull(TownServices.hearRumor(state, here, rnd), "no credits, no rumor")
    }

    @Test
    fun walkingUpToTheTraderOpensItsShop() {
        val h = GameHarness()
        h.frame(2)
        val planet = TestPlanets.ofType(PlanetType.ROCKY, id = 0L)
        h.game.enterSurface(planet, Towns.TOWN_X + Building.TRADER.offset)
        h.frame(3)
        h.state.stats.credits = 5000
        h.state.loadout.bag.clear()
        h.press(Keys.E) // enter
        h.frame(2)
        h.press(Keys.E) // buy the first piece
        h.frame(2)
        assertEquals(1, h.state.loadout.bag.size)
        assertTrue(h.state.stats.credits < 5000)
        h.press(Keys.ESCAPE)
        h.frame(2)
        assertFalse(h.game.pauseMenu.open, "Esc left the shop, not opened the pause menu")
        h.frame(10)
    }

    @Test
    fun theBarAndTheTownsfolk() {
        val h = GameHarness()
        h.frame(2)
        val planet = TestPlanets.ofType(PlanetType.OCEAN, id = 0L)
        h.game.enterSurface(planet, Towns.TOWN_X + Building.BAR.offset)
        h.frame(3)
        h.state.stats.mind = 30f
        h.state.stats.credits = 100
        h.press(Keys.E)
        h.frame(2)
        h.press(Keys.E) // a drink
        h.frame(2)
        assertEquals(90, h.state.stats.credits)
        assertTrue(h.state.stats.mind > 45f)
        h.press(Keys.ESCAPE)
        h.frame(2)

        h.game.enterSurface(planet, Towns.TOWN_X + Towns.townFor(planet)!!.folk.first().offset)
        h.frame(3)
        h.press(Keys.E) // talk
        h.frame(2)
        val pos = h.state.stats.credits
        h.press(Keys.E) // ask about something else
        h.press(Keys.S)
        h.press(Keys.E) // goodbye
        h.frame(5)
        assertEquals(pos, h.state.stats.credits, "talking is free")
        assertFalse(h.game.pauseMenu.open)
    }
}
