package com.mobeen.space.desktop

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.mobeen.space.SpaceGame

/** Start with `--dev` (or the environment variable SPACE_DEV=1) to enable the developer menu. */
fun main(args: Array<String>) {
    val devMode = "--dev" in args || System.getenv("SPACE_DEV") == "1"
    val config = Lwjgl3ApplicationConfiguration()
    config.setTitle(if (devMode) "Space Exploration (dev mode)" else "Space Exploration")
    config.setWindowedMode(1280, 720)
    config.useVsync(true)
    config.setForegroundFPS(60)
    config.setBackBufferConfig(8, 8, 8, 8, 16, 0, 4)
    Lwjgl3Application(SpaceGame(devMode), config)
}
