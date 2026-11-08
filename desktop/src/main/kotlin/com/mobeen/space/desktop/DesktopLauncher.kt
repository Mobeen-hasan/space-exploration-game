package com.mobeen.space.desktop

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.mobeen.space.SpaceGame

fun main() {
    val config = Lwjgl3ApplicationConfiguration()
    config.setTitle("Space Exploration")
    config.setWindowedMode(1280, 720)
    config.useVsync(true)
    config.setForegroundFPS(60)
    config.setBackBufferConfig(8, 8, 8, 8, 16, 0, 4)
    Lwjgl3Application(SpaceGame(), config)
}
