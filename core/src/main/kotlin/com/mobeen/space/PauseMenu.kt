package com.mobeen.space

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType
import com.badlogic.gdx.math.Matrix4

/**
 * Pause the game and, if you like, save and go back to the main menu. Opened with Esc or the MENU tab
 * at the top of the screen. Screens pass [blocked] while one of their own menus is using Esc.
 */
class PauseMenu(private val settings: Settings = Settings()) {
    private val menu = OverlayMenu("PAUSED")
    val open: Boolean get() = menu.open

    /** True while the settings page is showing instead of the main pause list. */
    var inSettings = false
        private set

    fun close() {
        menu.open = false
        inSettings = false
    }

    private fun tabRect(w: Float, h: Float) = floatArrayOf(w / 2f - 112f, h - 24f, 70f, 22f)

    private fun rows(exitLabel: String, onExit: () -> Unit): List<MenuRow> = if (inSettings) {
        SettingsPage.rows(settings) { inSettings = false; menu.resetSelection() }
    } else listOf(
        MenuRow("Resume") { menu.open = false },
        MenuRow("Settings") { inSettings = true; menu.resetSelection() },
        MenuRow(exitLabel) {
            menu.open = false
            onExit()
        },
    )

    /** Call near the start of a screen's render. Returns true while paused: draw, but do not update. */
    fun frame(w: Float, h: Float, blocked: Boolean, exitLabel: String, onExit: () -> Unit): Boolean {
        val input = Gdx.input
        var toggled = false
        if (!blocked && input.isKeyJustPressed(Input.Keys.ESCAPE)) toggled = true
        if (!blocked && input.justTouched()) {
            val b = tabRect(w, h)
            val tx = input.x.toFloat()
            val ty = h - input.y.toFloat()
            if (tx >= b[0] && tx <= b[0] + b[2] && ty >= b[1] && ty <= b[1] + b[3]) toggled = true
        }
        if (toggled) {
            if (menu.open && inSettings) { inSettings = false; menu.resetSelection() } // Esc steps back out of the settings first
            else {
                menu.open = !menu.open
                inSettings = false
            }
            return true // this frame's key press is used up
        }
        if (!menu.open) return false
        currentExit = exitLabel
        menu.handle(w, h, rows(exitLabel, onExit))
        return true
    }

    private var currentExit = "Save and exit to the main menu"

    /** Call last in a screen's draw: the MENU tab, and the menu itself when open. */
    fun draw(shapes: ShapeRenderer, batch: SpriteBatch, font: BitmapFont, glyphs: GlyphLayout, hud: Matrix4, w: Float, h: Float) {
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        shapes.projectionMatrix = hud
        batch.projectionMatrix = hud
        val tab = tabRect(w, h)
        shapes.begin(ShapeType.Filled)
        shapes.setColor(0.3f, 0.55f, 0.9f, if (menu.open) 0.9f else 0.45f)
        shapes.rect(tab[0], tab[1], tab[2], tab[3])
        shapes.end()
        batch.begin()
        font.setColor(1f, 1f, 1f, 1f)
        glyphs.setText(font, "MENU")
        font.draw(batch, "MENU", tab[0] + (tab[2] - glyphs.width) / 2f, tab[1] + (tab[3] + font.capHeight) / 2f)
        batch.end()
        if (menu.open) menu.draw(shapes, batch, font, glyphs, hud, w, h, rows(currentExit) {})
    }

    companion object {
        const val SAVE_AND_EXIT = "Save and exit to the main menu"
        const val ABANDON_TRIP = "Exit to the main menu   (ends this trip: you lose your pack and spare gear)"
    }
}
