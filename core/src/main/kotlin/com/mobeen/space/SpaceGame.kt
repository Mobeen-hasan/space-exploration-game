package com.mobeen.space

import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Screen

/** [devMode] turns on the developer menu (start the desktop game with `--dev`). */
/**
 * [devMode] turns on the developer menu (start the desktop game with `--dev`).
 * [startOnTitle] shows the title screen first; tests turn it off to start straight in space.
 */
class SpaceGame(val devMode: Boolean = false, private val startOnTitle: Boolean = true) : Game() {
    val state = GameState()
    val devMenu = DevMenu()
    val pauseMenu = PauseMenu()
    val journal = JournalOverlay()
    private lateinit var space: GameScreen
    private var transient: Screen? = null // the surface or interior screen currently in use
    private var pendingSwitch: (() -> Screen)? = null

    /** True while a game is being played (not on the title screen). Only then is it saved on exit. */
    var inGame = false
        private set

    override fun create() {
        SaveStore.migrate()
        space = GameScreen(this, state)
        if (startOnTitle) {
            val title = TitleScreen(this, state)
            transient = title
            setScreen(title)
        } else {
            SaveStore.load(state, hardcore = false)
            inGame = true
            setScreen(space)
        }
    }

    /** Save the game and go back to the title screen. */
    fun exitToTitle() {
        if (pendingSwitch != null) return
        SaveStore.save(state)
        inGame = false
        pauseMenu.close()
        journal.close()
        requestSwitch { TitleScreen(this, state) }
    }

    /** Leave the title screen and play the game now held in [state]. */
    fun startPlaying() {
        inGame = true
        returnToSpace()
    }

    /**
     * Screens ask to be replaced from inside their own render(). Replacing them right away would
     * dispose their drawing tools while they are still mid-frame, so the switch is queued and
     * done at the start of the next frame instead. Only one switch can be pending at a time.
     */
    private fun requestSwitch(factory: () -> Screen) {
        if (pendingSwitch != null) return
        pendingSwitch = factory
        Gdx.app.postRunnable { runSwitch() }
    }

    private fun runSwitch() {
        val factory = pendingSwitch ?: return
        pendingSwitch = null
        val next = factory()
        val old = transient
        transient = if (next === space) null else next
        setScreen(next)
        old?.dispose()
    }

    fun returnToSpace() = requestSwitch { space }

    fun enterSurface(planet: Planet, spawnX: Float = 55f) = requestSwitch { SurfaceScreen(this, state, planet, spawnX) }

    /** Go down into the planet's ruins, continuing the current expedition if there is one. */
    fun enterDungeon(planet: Planet) = requestSwitch {
        val existing = state.dungeon
        val run = if (existing != null && existing.planet.id == planet.id && !existing.exited && !existing.dead) {
            existing
        } else {
            DungeonRun(
                planet, state, kotlin.random.Random.nextLong(),
                contract = state.bounties.hunting?.takeIf { it.planetId == planet.id },
            ).also { state.dungeon = it }
        }
        DungeonScreen(this, state, run)
    }

    fun enterInterior(spawnX: Float) = requestSwitch { InteriorScreen(this, state, spawnX) }

    /**
     * The player passed out or died. In Normal mode: apply the penalty and wake them in the ship's bunk.
     * In Hardcore: this hunter is gone; the next one inherits their legacy, and the save is overwritten at once.
     */
    fun collapse() {
        if (pendingSwitch != null) return
        if (state.hardcore) {
            val epitaph = state.hardcoreEpitaph(state.stats.collapseReason())
            SaveStore.delete(hardcore = true) // one life: there is nothing to reload
            inGame = false
            state.dungeon = null
            requestSwitch { TitleScreen(this, state, epitaph = epitaph) }
            return
        }
        state.pendingNotice = state.stats.applyCollapse()
        enterInterior(InteriorScreen.BUNK_SPAWN)
    }

    override fun dispose() {
        if (inGame) SaveStore.save(state)
        super.dispose()
        space.dispose()
        transient?.dispose()
    }
}
