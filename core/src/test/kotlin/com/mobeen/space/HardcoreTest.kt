package com.mobeen.space

import com.badlogic.gdx.Input.Keys
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HardcoreTest {
    private fun veteran(): GameState {
        val s = GameState()
        s.stats.credits = 1200
        s.stats.day = 9
        s.stats.health = 40f
        s.discovered.addAll(listOf(1L, 2L, 3L))
        s.bounties.refreshIfNeeded(s)
        val c = s.bounties.available.first()
        s.bounties.accept(c)
        s.bounties.markCaptured(c)
        s.bounties.submit(s.stats)
        s.bounties.refreshIfNeeded(s)
        s.bounties.accept(s.bounties.available.first())
        s.loadout.bag.add(Gear(GearKind.ARC_SABER, Quality.RARE))
        s.loadout.equipFromBag(0)
        s.locker.clear()
        s.locker.add(ItemType.TONIC)
        s.ship.restore(5000f, -3000f, 10f, 0f, 1f)
        return s
    }

    @Test
    fun aNewGameStartsFromScratchInTheChosenMode() {
        val s = veteran()
        s.resetForNewGame(hardcoreMode = true)
        assertTrue(s.hardcore)
        assertEquals(Stats.START_CREDITS, s.stats.credits)
        assertEquals(1, s.stats.day)
        assertEquals(Stats.MAX, s.stats.health)
        assertTrue(s.discovered.isEmpty())
        assertEquals(0, s.bounties.completedCount)
        assertNull(s.bounties.active)
        assertEquals(GearKind.STUN_BATON, s.loadout.weapon.kind)
        assertEquals(GameState.STARTING_KIT, s.locker)
        assertEquals(0f, s.ship.pos.x)
        assertNull(s.ship.landedOn)

        s.resetForNewGame(hardcoreMode = false)
        assertFalse(s.hardcore)
    }

    @Test
    fun theEndOfAHardcoreGameSaysWhatYouAchieved() {
        val s = veteran()
        val words = s.hardcoreEpitaph("Your mind gave way in the dark.")
        assertTrue(words.startsWith("Your mind gave way"), words)
        assertTrue(words.contains("9 days") && words.contains("1 bounty") && words.contains("3 worlds"), words)
        assertTrue(words.contains("Normal game is untouched"))
    }

    @Test
    fun theModeIsSavedAndOldSavesAreNormal() {
        val a = GameState()
        a.hardcore = true
        val b = GameState()
        assertTrue(SaveCodec.decode(b, SaveCodec.encode(a)))
        assertTrue(b.hardcore)
        val old = SaveCodec.encode(a).filterKeys { it != "hardcore" }
        val c = GameState()
        assertTrue(SaveCodec.decode(c, old))
        assertFalse(c.hardcore)
    }

    // ------------------------------------------------------------------ separate save slots

    private fun savedGame(hardcore: Boolean, credits: Int): GameState {
        val s = GameState()
        s.resetForNewGame(hardcore)
        s.stats.credits = credits
        return s
    }

    @Test
    fun normalAndHardcoreAreSavedSeparately() {
        GameHarness() // a fresh, empty save store
        SaveStore.save(savedGame(hardcore = false, credits = 777))
        SaveStore.save(savedGame(hardcore = true, credits = 55))
        assertTrue(SaveStore.hasSave(false) && SaveStore.hasSave(true))

        val n = GameState()
        assertTrue(SaveStore.load(n, hardcore = false))
        assertEquals(777, n.stats.credits)
        assertFalse(n.hardcore)
        val hc = GameState()
        assertTrue(SaveStore.load(hc, hardcore = true))
        assertEquals(55, hc.stats.credits)
        assertTrue(hc.hardcore)

        SaveStore.delete(hardcore = true)
        assertFalse(SaveStore.hasSave(true))
        assertTrue(SaveStore.hasSave(false), "deleting the Hardcore save leaves the Normal one alone")
        assertEquals("day 1, 777 credits", SaveStore.summary(false))
        assertNull(SaveStore.summary(true))
    }

    @Test
    fun aHardcoreGameLeftInTheNormalSlotByAnOlderBuildIsMovedToItsOwnSlot() {
        GameHarness()
        val old = savedGame(hardcore = true, credits = 123)
        // An older build wrote every game to the Normal slot.
        GdxTestEnv.saved.getOrPut("space-exploration-save") { HashMap() }.putAll(SaveCodec.encode(old))
        SaveStore.migrate()
        assertFalse(SaveStore.hasSave(false))
        val moved = GameState()
        assertTrue(SaveStore.load(moved, hardcore = true))
        assertEquals(123, moved.stats.credits)
    }

    @Test
    fun startingAHardcoreGameLeavesTheNormalGameAlone() {
        val h = GameHarness(startOnTitle = true)
        SaveStore.save(savedGame(hardcore = false, credits = 900))
        h.game.setScreen(TitleScreen(h.game, h.state)) // reopen the title so it sees the save
        h.frame(3)
        // Rows: Continue Normal, New Normal, New Hardcore.
        h.press(Keys.DOWN)
        h.press(Keys.DOWN)
        h.press(Keys.ENTER)
        h.frame(3)
        assertTrue(h.screen is GameScreen, "no confirmation needed: there was no Hardcore save")
        assertTrue(h.state.hardcore)
        val normal = GameState()
        assertTrue(SaveStore.load(normal, hardcore = false))
        assertEquals(900, normal.stats.credits, "the Normal game is untouched")
    }

    @Test
    fun dyingInHardcoreEndsThatGameOnlyAndLeadsBackToTheTitle() {
        val h = GameHarness()
        SaveStore.save(savedGame(hardcore = false, credits = 640))
        h.state.resetForNewGame(true)
        SaveStore.save(h.state)
        h.frame(3)
        h.state.stats.health = 0f
        h.frame(3)
        assertTrue(h.screen is TitleScreen, "a Hardcore death shows the end screen")
        assertFalse(SaveStore.hasSave(true), "the Hardcore save is gone")
        assertTrue(SaveStore.hasSave(false), "the Normal save is not")
        assertFalse(h.game.inGame)

        h.press(Keys.ENTER) // back to the main menu
        h.frame(3)
        h.press(Keys.ENTER) // the first row is now "Continue Normal game"
        h.frame(3)
        assertTrue(h.screen is GameScreen)
        assertFalse(h.state.hardcore)
        assertEquals(640, h.state.stats.credits)
        h.frame(20)
    }

    @Test
    fun startingOverASaveOfTheSameModeNeedsASecondPress() {
        val h = GameHarness(startOnTitle = true)
        SaveStore.save(savedGame(hardcore = false, credits = 999))
        h.game.setScreen(TitleScreen(h.game, h.state))
        h.frame(3)
        h.press(Keys.DOWN) // from "Continue Normal" down to "New Normal game"
        h.press(Keys.ENTER)
        h.frame(3)
        assertTrue(h.screen is TitleScreen, "the first press only asks for confirmation")
        val stillThere = GameState()
        SaveStore.load(stillThere, hardcore = false)
        assertEquals(999, stillThere.stats.credits)
        h.press(Keys.ENTER)
        h.frame(3)
        assertTrue(h.screen is GameScreen)
        assertEquals(Stats.START_CREDITS, h.state.stats.credits)
        assertFalse(h.state.hardcore)
    }

    @Test
    fun dyingInNormalModeStillWakesYouInTheShip() {
        val h = GameHarness()
        h.frame(3)
        h.state.stats.health = 0f
        h.frame(3)
        assertTrue(h.screen is InteriorScreen)
    }

    @Test
    fun hardcoreGetsOneLastStandPerTrip() {
        val planet = TestPlanets.ofType(PlanetType.ROCKY)
        val state = GameState()
        state.hardcore = true
        val run = DungeonRun(planet, state, 1L, Random(3))
        run.level.monsters.clear()
        val killer = Monster(MonsterType.BRUTE, run.px + 1, run.py, 9999, 20f, AiState.HUNTING) // every hit is lethal
        run.level.set(run.px + 1, run.py, Tile.FLOOR)
        run.level.monsters.add(killer)
        var turns = 0
        while (!run.lastStandUsed && turns++ < 100) run.waitTurn()
        assertTrue(run.lastStandUsed)
        assertFalse(run.dead, "the first killing blow leaves you standing")
        assertEquals(1f, state.stats.health)
        while (!run.dead && turns++ < 200) run.waitTurn()
        assertTrue(run.dead, "but only once")
    }

    @Test
    fun normalModeHasNoLastStand() {
        val planet = TestPlanets.ofType(PlanetType.ROCKY)
        val state = GameState()
        val run = DungeonRun(planet, state, 1L, Random(3))
        run.level.monsters.clear()
        run.level.set(run.px + 1, run.py, Tile.FLOOR)
        run.level.monsters.add(Monster(MonsterType.BRUTE, run.px + 1, run.py, 9999, 20f, AiState.HUNTING))
        var turns = 0
        while (!run.dead && turns++ < 100) run.waitTurn()
        assertTrue(run.dead)
        assertFalse(run.lastStandUsed)
    }

    @Test
    fun theTitleScreenStartsANewHardcoreGameWhenNothingIsSaved() {
        val h = GameHarness(startOnTitle = true)
        h.frame(5)
        assertTrue(h.screen is TitleScreen)
        h.press(Keys.DOWN) // no saves: the rows are New Normal, New Hardcore
        h.press(Keys.ENTER)
        h.frame(3)
        assertTrue(h.screen is GameScreen)
        assertTrue(h.state.hardcore)
        assertTrue(SaveStore.hasSave(true))
        assertFalse(SaveStore.hasSave(false))
    }
}
