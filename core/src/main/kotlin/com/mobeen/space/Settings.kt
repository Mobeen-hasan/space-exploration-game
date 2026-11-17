package com.mobeen.space

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import kotlin.math.roundToInt

/** Player options, kept apart from the saved games so they survive a new game. */
class Settings {
    var master = 0.8f
    var music = 0.6f
    var sfx = 0.8f
    var fullscreen = false

    fun load() {
        val p = prefs() ?: return
        master = p.getFloat("master", master).coerceIn(0f, 1f)
        music = p.getFloat("music", music).coerceIn(0f, 1f)
        sfx = p.getFloat("sfx", sfx).coerceIn(0f, 1f)
        fullscreen = p.getBoolean("fullscreen", fullscreen)
    }

    fun save() {
        val p = prefs() ?: return
        p.putFloat("master", master)
        p.putFloat("music", music)
        p.putFloat("sfx", sfx)
        p.putBoolean("fullscreen", fullscreen)
        p.flush()
    }

    fun resetToDefaults() {
        val d = Settings()
        master = d.master
        music = d.music
        sfx = d.sfx
        fullscreen = d.fullscreen
    }

    /** Switches between a window and full screen. Desktop only: phones are always full screen. */
    fun applyDisplay() {
        val app = Gdx.app ?: return
        if (app.type != Application.ApplicationType.Desktop) return
        val g = Gdx.graphics ?: return
        if (fullscreen) g.displayMode?.let { g.setFullscreenMode(it) } else g.setWindowedMode(1280, 720)
    }

    private fun prefs() = Gdx.app?.getPreferences(PREFS)

    companion object {
        const val PREFS = "space-exploration-settings"

        /** One step of a volume slider. */
        fun step(v: Float, dir: Int): Float = ((v * 10f).roundToInt() + dir).coerceIn(0, 10) / 10f

        fun percent(v: Float) = "${(v * 100f).roundToInt()}%"
    }
}

/** The settings rows, shared by the title screen and the pause menu. A/D or Left/Right change a value. */
object SettingsPage {
    fun rows(settings: Settings, back: () -> Unit): List<MenuRow> {
        fun changed() {
            settings.save()
            Sfx.play(SoundId.CLICK)
        }
        fun volumeRow(name: String, get: () -> Float, set: (Float) -> Unit) = MenuRow(
            "$name volume:   ${bar(get())}  ${Settings.percent(get())}      (A / D to change)",
            run = { set(if (get() >= 1f) 0f else Settings.step(get(), 1)); changed() },
            adjust = { dir -> set(Settings.step(get(), dir)); changed() },
        )
        val desktop = Gdx.app?.type == Application.ApplicationType.Desktop
        val list = arrayListOf(
            volumeRow("Master", { settings.master }) { settings.master = it },
            volumeRow("Music", { settings.music }) { settings.music = it },
            volumeRow("Effects", { settings.sfx }) { settings.sfx = it },
        )
        if (desktop) list.add(MenuRow("Full screen:   ${if (settings.fullscreen) "ON" else "OFF"}") {
            settings.fullscreen = !settings.fullscreen
            settings.applyDisplay()
            changed()
        })
        list.add(MenuRow("Reset to defaults") {
            val wasFull = settings.fullscreen
            settings.resetToDefaults()
            if (wasFull != settings.fullscreen) settings.applyDisplay()
            changed()
        })
        list.add(MenuRow("Back", run = back))
        return list
    }

    private fun bar(v: Float): String {
        val n = (v * 10f).roundToInt()
        return "[" + "#".repeat(n) + "-".repeat(10 - n) + "]"
    }
}
