package com.mobeen.space

import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Screen

/** [devMode] turns on the developer menu (start the desktop game with `--dev`). */
class SpaceGame(val devMode: Boolean = false) : Game() {
    val state = GameState()
    val devMenu = DevMenu()
    private lateinit var space: GameScreen
    private var transient: Screen? = null // the surface or interior screen currently in use
    private var pendingSwitch: (() -> Screen)? = null

    override fun create() {
        SaveStore.load(state) // continue a saved game if there is one
        space = GameScreen(this, state)
        setScreen(space)
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
                contract = state.bounties.active?.takeIf { it.planetId == planet.id },
            ).also { state.dungeon = it }
        }
        DungeonScreen(this, state, run)
    }

    fun enterInterior(spawnX: Float) = requestSwitch { InteriorScreen(this, state, spawnX) }

    /** The player passed out or died: apply the penalty and wake them up in the ship's bunk. */
    fun collapse() {
        if (pendingSwitch != null) return
        state.pendingNotice = state.stats.applyCollapse()
        enterInterior(InteriorScreen.BUNK_SPAWN)
    }

    override fun dispose() {
        SaveStore.save(state)
        super.dispose()
        space.dispose()
        transient?.dispose()
    }
}
