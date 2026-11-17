package com.mobeen.space

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input.Keys
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioSettingsTest {
    @Test
    fun everySoundIsShortAudibleAndUnclipped() {
        for (id in SoundId.entries) {
            val s = Synth.sound(id)
            val seconds = s.size / Synth.RATE.toFloat()
            assertTrue(seconds in 0.03f..2f, "$id lasts $seconds s")
            val peak = s.maxOf { abs(it.toInt()) }
            assertTrue(peak in 8000..32767, "$id peak $peak")
        }
    }

    @Test
    fun musicLoopsAreLongAndQuietAtTheSeam() {
        for (t in Track.entries) {
            val m = Synth.music(t)
            val seconds = m.size / Synth.RATE.toFloat()
            assertTrue(seconds in 20f..60f, "$t lasts $seconds s")
            assertTrue(m.maxOf { abs(it.toInt()) } < 32767, "$t clips")
            // the end flows into the start: no big jump at the seam
            assertTrue(abs(m.last() - m.first()) < 12000, "$t seam jump ${m.last() - m.first()}")
        }
        assertEquals(Synth.music(Track.SPACE).toList(), Synth.music(Track.SPACE).toList(), "the same every time")
    }

    @Test
    fun wavFilesHaveAProperHeader() {
        val w = Synth.wav(shortArrayOf(1, -1, 300))
        assertEquals("RIFF", String(w, 0, 4))
        assertEquals("WAVE", String(w, 8, 4))
        assertEquals(44 + 6, w.size)
    }

    @Test
    fun volumeStepsStayInRange() {
        assertEquals(0.9f, Settings.step(0.8f, 1), 0.001f)
        assertEquals(1f, Settings.step(1f, 1), 0.001f)
        assertEquals(0f, Settings.step(0f, -1), 0.001f)
        assertEquals("70%", Settings.percent(0.7f))
    }

    @Test
    fun settingsAreSavedApartFromTheGame() {
        GdxTestEnv.install()
        GdxTestEnv.resetSaves()
        val a = Settings()
        a.master = 0.3f
        a.music = 0f
        a.save()
        val b = Settings()
        b.load()
        assertEquals(0.3f, b.master, 0.001f)
        assertEquals(0f, b.music, 0.001f)
        b.resetToDefaults()
        assertEquals(Settings().master, b.master)
        val state = GameState()
        state.resetForNewGame(false)
        val c = Settings()
        c.load()
        assertEquals(0.3f, c.master, 0.001f, "a new game keeps your settings")
    }

    @Test
    fun thePauseMenuHasASettingsPage() {
        val h = GameHarness()
        h.frame(2)
        h.press(Keys.ESCAPE)
        assertTrue(h.game.pauseMenu.open)
        h.press(Keys.S) // Settings
        h.press(Keys.E)
        assertTrue(h.game.pauseMenu.inSettings)
        val before = h.game.settings.master
        h.press(Keys.A) // master volume down a step
        assertEquals(Settings.step(before, -1), h.game.settings.master, 0.001f)
        h.press(Keys.D)
        h.press(Keys.D)
        assertEquals(Settings.step(before, 1), h.game.settings.master, 0.001f)
        h.press(Keys.ESCAPE) // back to the pause list, still paused
        assertFalse(h.game.pauseMenu.inSettings)
        assertTrue(h.game.pauseMenu.open)
        h.press(Keys.ESCAPE)
        assertFalse(h.game.pauseMenu.open)
        val saved = Settings().also { it.load() }
        assertEquals(h.game.settings.master, saved.master, 0.001f, "changes are saved straight away")
    }

    @Test
    fun theTitleScreenOpensSettings() {
        val h = GameHarness(startOnTitle = true)
        h.frame(3)
        assertEquals(Track.SPACE, Sfx.track)
        // a fresh install: New Normal, New Hardcore, Settings, Quit
        h.press(Keys.S)
        h.press(Keys.S)
        h.press(Keys.E)
        h.frame(2)
        val m = h.game.settings.music
        h.press(Keys.S) // music
        h.press(Keys.A)
        assertEquals(Settings.step(m, -1), h.game.settings.music, 0.001f)
        h.press(Keys.ESCAPE)
        h.frame(3)
        assertTrue(h.screen is TitleScreen)
    }

    @Test
    fun theGameMakesSounds() {
        val h = GameHarness()
        h.frame(2)
        Sfx.played.clear()
        h.press(Keys.ESCAPE)
        h.press(Keys.S)
        assertTrue(SoundId.CLICK in Sfx.played, "menus click")
        h.press(Keys.ESCAPE)

        val planet = TestPlanets.ofType(PlanetType.ROCKY, id = 0L)
        h.game.enterSurface(planet, Towns.TOWN_X + Building.BAR.offset)
        h.frame(3)
        assertEquals(Track.SURFACE, Sfx.track)
        assertTrue(SoundId.DOOR in Sfx.played)
        h.state.stats.credits = 100
        h.frame(2)
        Sfx.played.clear()
        h.press(Keys.E)
        h.press(Keys.E) // buy a drink
        h.frame(2)
        assertTrue(SoundId.BUY in Sfx.played, "spending money rings the till")
        h.press(Keys.ESCAPE)

        h.game.enterDungeon(planet)
        h.frame(3)
        assertEquals(Track.RUINS, Sfx.track)
        assertTrue(SoundId.STAIRS in Sfx.played)
        h.game.returnToSpace()
        h.frame(3)
        assertEquals(Track.SPACE, Sfx.track)
    }

    @Test
    fun withoutASoundCardTheGameStaysSilentButRuns() {
        assertEquals(null, Gdx.audio)
        val audio = Audio(Settings())
        audio.load()
        audio.play(SoundId.HIT)
        audio.thrust(1f)
        audio.music(Track.RUINS)
        audio.update(0.1f)
        audio.dispose()
    }
}
