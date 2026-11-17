package com.mobeen.space

import com.badlogic.gdx.Input.Keys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PauseMenuTest {
    @Test
    fun escapePausesAndResumes() {
        val h = GameHarness()
        h.frame(3)
        h.press(Keys.ESCAPE)
        assertTrue(h.game.pauseMenu.open)
        val pos = h.state.ship.pos.cpy()
        val clock = h.state.clock
        h.hold(Keys.UP, 60)
        assertEquals(pos, h.state.ship.pos, "nothing moves while paused")
        assertEquals(clock, h.state.clock)
        h.press(Keys.ENTER) // "Resume"
        assertFalse(h.game.pauseMenu.open)
        h.hold(Keys.UP, 30)
        assertTrue(h.state.ship.pos.dst(pos) > 1f)
    }

    @Test
    fun saveAndExitGoesToTheTitleAndKeepsTheGame() {
        val h = GameHarness()
        h.frame(3)
        h.state.stats.credits = 4321
        h.press(Keys.ESCAPE)
        h.press(Keys.DOWN) // past Settings
        h.press(Keys.DOWN)
        h.press(Keys.ENTER) // "Save and exit to the main menu"
        h.frame(3)
        assertTrue(h.screen is TitleScreen)
        assertFalse(h.game.inGame)
        val saved = GameState()
        assertTrue(SaveStore.load(saved, hardcore = false))
        assertEquals(4321, saved.stats.credits)

        h.press(Keys.ENTER) // "Continue Normal game"
        h.frame(3)
        assertTrue(h.screen is GameScreen)
        assertEquals(4321, h.state.stats.credits)
    }

    @Test
    fun aHardcoreGameIsSavedToItsOwnSlotOnExit() {
        val h = GameHarness()
        h.frame(3)
        h.state.resetForNewGame(true)
        h.state.stats.credits = 77
        h.press(Keys.ESCAPE)
        h.press(Keys.DOWN) // past Settings
        h.press(Keys.DOWN)
        h.press(Keys.ENTER)
        h.frame(3)
        assertTrue(h.screen is TitleScreen)
        assertTrue(SaveStore.hasSave(true))
        assertFalse(SaveStore.hasSave(false), "a Hardcore exit writes only the Hardcore slot")
    }

    @Test
    fun exitingFromTheRuinsEndsTheTripLikeFleeing() {
        val h = GameHarness()
        h.frame(3)
        h.state.loadout.bag.add(Gear(GearKind.RIOT_SHIELD))
        h.state.loadout.equipFromBag(0)
        h.state.loadout.bag.add(Gear(GearKind.PIPE_CLUB, Quality.FINE))
        h.game.enterDungeon(TestPlanets.ofType(PlanetType.ROCKY))
        h.frame(3)
        assertTrue(h.screen is DungeonScreen)
        h.press(Keys.ESCAPE)
        h.press(Keys.DOWN) // past Settings
        h.press(Keys.DOWN)
        h.press(Keys.ENTER)
        h.frame(3)
        assertTrue(h.screen is TitleScreen)
        assertNull(h.state.dungeon)
        assertTrue(h.state.loadout.bag.isEmpty(), "spare gear is left behind")
        assertEquals(GearKind.RIOT_SHIELD, h.state.loadout.shield?.kind, "equipped gear stays")
        assertTrue(h.state.locker.isEmpty(), "the pack is not returned to the locker")
    }

    @Test
    fun escapeClosesTheShipTerminalBeforeItPauses() {
        val h = GameHarness()
        h.frame(3)
        h.press(Keys.I)
        h.frame(3)
        h.hold(Keys.A, 40) // to the terminal
        h.press(Keys.E)
        h.frame(2)
        h.press(Keys.ESCAPE)
        assertFalse(h.game.pauseMenu.open, "the terminal takes Esc first")
        h.frame(2)
        h.press(Keys.ESCAPE)
        assertTrue(h.game.pauseMenu.open, "with the terminal closed, Esc pauses")
        h.press(Keys.ESCAPE)
        assertFalse(h.game.pauseMenu.open)
    }

    @Test
    fun thePauseMenuWorksOnTheSurfaceToo() {
        val h = GameHarness()
        h.frame(3)
        h.game.enterSurface(TestPlanets.ofType(PlanetType.DESERT))
        h.frame(3)
        h.press(Keys.ESCAPE)
        assertTrue(h.game.pauseMenu.open)
        h.frame(10)
        h.press(Keys.DOWN) // past Settings
        h.press(Keys.DOWN)
        h.press(Keys.ENTER)
        h.frame(3)
        assertTrue(h.screen is TitleScreen)
    }
}
