package com.mobeen.space

import com.badlogic.gdx.Game

class SpaceGame : Game() {
    val state = GameState()
    private lateinit var space: GameScreen
    private var surface: SurfaceScreen? = null

    override fun create() {
        space = GameScreen(this, state)
        setScreen(space)
    }

    fun enterSurface(planet: Planet) {
        val s = SurfaceScreen(this, state, planet)
        surface = s
        setScreen(s)
    }

    fun returnToSpace() {
        setScreen(space)
        surface?.dispose()
        surface = null
    }

    override fun dispose() {
        super.dispose()
        space.dispose()
        surface?.dispose()
    }
}
