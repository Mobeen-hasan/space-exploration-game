package com.mobeen.space

import com.badlogic.gdx.Input.Keys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShipUpgradeTest {
    @Test
    fun upgradesCostMoreEachLevelAndStopAtTheMax() {
        val state = GameState()
        val ups = state.upgrades
        state.stats.credits = 149
        assertFalse(ups.buy(state, Upgrade.ENGINE), "too poor")
        state.stats.credits = 10_000
        for (lv in 0 until ShipUpgrades.MAX_LEVEL) {
            assertEquals(ShipUpgrades.PRICES[lv], ups.cost(Upgrade.ENGINE))
            assertTrue(ups.buy(state, Upgrade.ENGINE))
        }
        assertNull(ups.cost(Upgrade.ENGINE))
        assertFalse(ups.buy(state, Upgrade.ENGINE), "maxed out")
        assertEquals(10_000 - ShipUpgrades.PRICES.sum(), state.stats.credits)
        assertEquals(0, ups.level(Upgrade.RADAR))
    }

    @Test
    fun eachUpgradeChangesTheShip() {
        val state = GameState()
        val base = state.lockerSize
        val radar = state.upgrades.radarRange
        state.upgrades.maxAll()
        assertTrue(state.upgrades.thrustScale > 1.5f && state.upgrades.speedScale > 1.4f)
        assertTrue(state.upgrades.burnScale < 0.5f && state.upgrades.burnScale > 0f)
        assertTrue(state.upgrades.damageScale < 0.5f && state.upgrades.damageScale > 0f)
        assertTrue(state.upgrades.radarRange > radar * 2f)
        assertEquals(base + 6, state.lockerSize)
        state.stats.credits = 10_000
        repeat(state.lockerSize - state.locker.size) { assertTrue(Shop.buy(state, ItemType.BANDAGE)) }
        assertFalse(Shop.buy(state, ItemType.BANDAGE), "even a big locker fills up")
    }

    @Test
    fun upgradesAreSavedAndANewGameClearsThem() {
        val a = GameState()
        a.stats.credits = 10_000
        a.upgrades.buy(a, Upgrade.RADAR)
        a.upgrades.buy(a, Upgrade.RADAR)
        a.upgrades.buy(a, Upgrade.LOCKER)
        val b = GameState()
        assertTrue(SaveCodec.decode(b, SaveCodec.encode(a)))
        assertEquals(2, b.upgrades.level(Upgrade.RADAR))
        assertEquals(1, b.upgrades.level(Upgrade.LOCKER))
        val old = SaveCodec.encode(a).filterKeys { it != "upgrades" }
        val c = GameState()
        assertTrue(SaveCodec.decode(c, old), "older saves still load")
        assertEquals(0, c.upgrades.level(Upgrade.RADAR))
        b.resetForNewGame(false)
        assertEquals(0, b.upgrades.level(Upgrade.RADAR))
    }

    @Test
    fun aTunedEngineFliesFasterAndInjectorsSaveFuel() {
        fun fly(maxed: Boolean): Pair<Float, Float> {
            val h = GameHarness()
            h.frame(2)
            if (maxed) h.state.upgrades.maxAll()
            h.state.ship.restore(50_000f, 50_000f, 0f, 0f, 0f) // open space, far from planets
            h.state.stats.fuel = 100f
            h.hold(Keys.UP, 240)
            return h.state.ship.vel.len() to (100f - h.state.stats.fuel)
        }
        val (slowSpeed, slowBurn) = fly(false)
        val (fastSpeed, fastBurn) = fly(true)
        assertTrue(fastSpeed > slowSpeed * 1.3f, "$fastSpeed vs $slowSpeed")
        assertTrue(fastBurn < slowBurn * 0.6f, "$fastBurn vs $slowBurn")
    }

    @Test
    fun theDepotFitsUpgrades() {
        val h = GameHarness()
        h.frame(2)
        h.game.enterSurface(TestPlanets.ofType(PlanetType.ROCKY, id = 0L), Towns.TOWN_X + Building.DEPOT.offset)
        h.frame(3)
        h.state.stats.fuel = Stats.MAX
        h.state.stats.hull = Stats.MAX
        h.state.stats.credits = 1000
        h.press(Keys.E) // enter the depot; full tanks and hull, so the first choice is the engine
        h.frame(2)
        h.press(Keys.E)
        h.frame(2)
        assertEquals(1, h.state.upgrades.level(Upgrade.ENGINE))
        assertEquals(850, h.state.stats.credits)
        h.press(Keys.ESCAPE)
        h.frame(3)
    }
}
