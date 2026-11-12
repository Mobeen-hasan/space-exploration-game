package com.mobeen.space

import com.badlogic.gdx.Input.Keys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DevMenuTest {
    private fun openMenu(h: GameHarness) {
        h.press(Keys.F1)
        assertTrue(h.game.devMenu.open, "F1 should open the developer menu")
    }

    /** Pick an option the way a player would, then let a frame pass. */
    private fun choose(h: GameHarness, label: String) {
        assertTrue(h.game.devMenu.chooseByLabel(label), "no option starting with '$label': ${h.game.devMenu.options.map { it.label }}")
        h.frame(3)
    }

    @Test
    fun theMenuDoesNotExistOutsideDevMode() {
        val h = GameHarness()
        h.frame(3)
        h.press(Keys.F1)
        h.press(Keys.GRAVE)
        assertFalse(h.game.devMenu.open)
    }

    @Test
    fun openingTheMenuPausesTheGameAndEscapeClosesIt() {
        val h = GameHarness(devMode = true)
        h.frame(3)
        openMenu(h)
        val before = h.state.ship.pos.cpy()
        val clock = h.state.clock
        h.hold(Keys.UP, 60) // thrust would move the ship if the game were running
        assertEquals(before, h.state.ship.pos)
        assertEquals(clock, h.state.clock)
        h.press(Keys.ESCAPE)
        assertFalse(h.game.devMenu.open)
        h.hold(Keys.UP, 30)
        assertTrue(h.state.ship.pos.dst(before) > 1f, "after closing, the game runs again")
    }

    @Test
    fun keyboardChoosesAndRunsOptions() {
        val h = GameHarness(devMode = true)
        h.frame(3)
        openMenu(h)
        h.press(Keys.DOWN) // second option: refill everything
        h.state.stats.health = 10f
        h.press(Keys.ENTER)
        assertEquals(Stats.MAX, h.state.stats.health)
    }

    @Test
    fun godModeHoldsEveryMeterFull() {
        val h = GameHarness(devMode = true)
        h.frame(3)
        openMenu(h)
        choose(h, "God mode")
        assertTrue(h.state.stats.invulnerable)
        h.press(Keys.F1)
        h.state.stats.health = 5f
        h.state.stats.fuel = 0f
        h.frame(2)
        assertEquals(Stats.MAX, h.state.stats.health)
        assertEquals(Stats.MAX, h.state.stats.fuel)
    }

    @Test
    fun commonCheatsGiveCreditsAndANewDay() {
        val h = GameHarness(devMode = true)
        h.frame(3)
        openMenu(h)
        val credits = h.state.stats.credits
        val day = h.state.stats.day
        choose(h, "+1000 credits")
        choose(h, "Start a new day")
        assertEquals(credits + 1000, h.state.stats.credits)
        assertEquals(day + 1, h.state.stats.day)
        assertTrue(h.state.bounties.available.isNotEmpty())
    }

    @Test
    fun flyStraightToTheBountyTargetAndLand() {
        val h = GameHarness(devMode = true)
        h.frame(3)
        h.state.bounties.refreshIfNeeded(h.state)
        val c = h.state.bounties.available.first()
        h.state.bounties.accept(c)
        openMenu(h)
        choose(h, "Fly to the bounty target")
        assertEquals(c.planetId, h.state.ship.landedOn?.id)
        h.press(Keys.F1)
        h.frame(10)
        assertTrue(h.screen is GameScreen)
    }

    @Test
    fun surfaceTeleportsToTheRuinsEntranceAndBack() {
        val h = GameHarness(devMode = true)
        h.frame(3)
        openMenu(h)
        choose(h, "Land on the nearest planet")
        h.press(Keys.F1)
        h.press(Keys.E) // exit onto the surface
        h.frame(3)
        assertTrue(h.screen is SurfaceScreen)

        openMenu(h)
        choose(h, "Teleport to the ruins entrance")
        h.press(Keys.F1)
        h.press(Keys.E) // now standing at the archway: enter
        h.frame(3)
        assertTrue(h.screen is DungeonScreen, "the teleport should leave you at the entrance")
    }

    @Test
    fun ruinsCheatsRevealClearDescendAndReachTheTarget() {
        val h = GameHarness(devMode = true)
        h.frame(3)
        val p = TestPlanets.ofType(PlanetType.ROCKY, id = 77L)
        val c = Contract(1L, "Dev Target", "testing", p.id, "Test", p.type, 1000, 300, 1)
        h.state.bounties.available.add(c)
        h.state.bounties.accept(c)
        h.game.enterDungeon(p)
        h.frame(3)
        val run = assertNotNull(h.state.dungeon)

        openMenu(h)
        choose(h, "Reveal this floor")
        assertTrue(run.level.explored.all { it })
        choose(h, "Remove all monsters")
        assertTrue(run.level.monsters.isEmpty())
        choose(h, "Go down to floor 2")
        assertEquals(2, run.depth)
        choose(h, "Go to the last floor")
        assertEquals(run.maxDepth, run.depth)
        choose(h, "Teleport next to the bounty target")
        val target = run.level.monsters.first { it.type.boss }
        assertEquals(1, DungeonGenerator.distance(target.x, target.y, run.px, run.py))

        run.pack.clear()
        choose(h, "Get supplies")
        assertEquals(DungeonRun.PACK_SIZE, run.pack.size)
        choose(h, "Fill the lantern")
        assertEquals(DungeonRun.OIL_MAX, run.oil)
        choose(h, "Set mind to 20")
        assertEquals(20f, h.state.stats.mind)

        h.press(Keys.F1)
        h.frame(30) // the game runs again and draws hallucinations without trouble
        assertTrue(h.screen is DungeonScreen)
    }

    @Test
    fun godModeProtectsYouInTheRuins() {
        val h = GameHarness(devMode = true)
        h.frame(3)
        h.game.enterDungeon(TestPlanets.ofType(PlanetType.LAVA))
        h.frame(3)
        val run = assertNotNull(h.state.dungeon)
        h.state.stats.invulnerable = true
        run.level.monsters.add(Monster(MonsterType.BRUTE, run.px + 1, run.py, 999, 3f, AiState.HUNTING))
        repeat(30) { h.press(Keys.SPACE); h.frame(1) }
        assertFalse(run.dead)
        assertEquals(Stats.MAX, h.state.stats.health)
    }

    // ------------------------------------------------------------------ god mode regressions

    @Test
    fun turningGodModeOnByKeyboardSaysOnNotOff() {
        val h = GameHarness(devMode = true)
        h.frame(3)
        h.press(Keys.GRAVE) // open with the backtick key
        h.press(Keys.ENTER) // the first option is god mode
        assertTrue(h.state.stats.invulnerable)
        val notice = assertNotNull(h.game.devMenu.lastNotice)
        assertTrue(notice.contains("God mode: ON"), "the confirmation must show the new state, not the old one: $notice")
        assertTrue(h.game.devMenu.options.first().label.startsWith("God mode: ON"))
        h.press(Keys.GRAVE)
        assertTrue(h.state.stats.invulnerable, "closing the menu keeps god mode on")
    }

    @Test
    fun godModeStopsFuelAndHullLossInSpace() {
        val h = GameHarness(devMode = true)
        h.frame(3)
        h.state.stats.invulnerable = true
        h.hold(Keys.UP, 300)
        assertEquals(Stats.MAX, h.state.stats.fuel)
        assertEquals(Stats.MAX, h.state.stats.hull)
    }

    @Test
    fun godModeStopsLanternOilAndMindFromRunningDown() {
        val h = GameHarness(devMode = true)
        h.frame(3)
        h.game.enterDungeon(TestPlanets.ofType(PlanetType.ROCKY))
        h.frame(3)
        val run = assertNotNull(h.state.dungeon)
        h.state.stats.invulnerable = true
        val oil = run.oil
        run.level.monsters.add(Monster(MonsterType.FUGITIVE, run.px + 2, run.py, 999, 1f, AiState.HUNTING)) // scary
        run.toggleLantern() // even in the dark
        var lowestMind = Stats.MAX
        repeat(60) {
            h.press(Keys.SPACE)
            lowestMind = minOf(lowestMind, h.state.stats.mind)
        }
        run.toggleLantern()
        repeat(20) { h.press(Keys.SPACE) }
        assertEquals(oil, run.oil, "oil should not burn in god mode")
        assertEquals(Stats.MAX, lowestMind, "mind should never dip in god mode")
        assertEquals(Stats.MAX, h.state.stats.health)
        assertFalse(run.dead)
    }
}
