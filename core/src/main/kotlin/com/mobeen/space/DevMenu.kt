package com.mobeen.space

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType
import com.badlogic.gdx.math.Matrix4
import kotlin.math.min

class DevOption(val label: String, val run: () -> Unit)

/**
 * Developer cheats for testing. Only exists when the game is started in dev mode (`--dev`).
 * Open it with F1 or the backtick key, or tap the DEV tab at the top of the screen. The game
 * pauses while it is open. Each screen supplies its own options on top of the common ones.
 */
class DevMenu {
    var open = false
        private set

    /** The options shown right now, as last supplied by the current screen. */
    var options: List<DevOption> = emptyList()
        private set

    private var sel = 0
    private var notice: String? = null
    private var noticeTimer = 0f

    /**
     * Call at the start of a screen's render. Handles opening, closing and choosing. Returns true while
     * the menu is open, in which case the screen should draw but not update or read its own input.
     */
    fun frame(enabled: Boolean, w: Float, h: Float, provider: () -> List<DevOption>): Boolean {
        if (!enabled) {
            open = false
            return false
        }
        if (noticeTimer > 0f) noticeTimer -= Gdx.graphics.deltaTime
        val input = Gdx.input
        var toggled = input.isKeyJustPressed(Input.Keys.F1) || input.isKeyJustPressed(Input.Keys.GRAVE)
        var tapX = -1f
        var tapY = -1f
        if (input.justTouched()) {
            tapX = input.x.toFloat()
            tapY = h - input.y.toFloat()
            val b = tabRect(w, h)
            if (tapX >= b[0] && tapX <= b[0] + b[2] && tapY >= b[1] && tapY <= b[1] + b[3]) toggled = true
        }
        if (toggled) {
            open = !open
            sel = 0
        }
        if (!open) return false
        options = provider()
        if (sel >= options.size) sel = options.size - 1
        if (toggled || options.isEmpty()) return true

        if (input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            open = false
            return true
        }
        if (input.isKeyJustPressed(Input.Keys.UP) || input.isKeyJustPressed(Input.Keys.W)) sel = (sel + options.size - 1) % options.size
        if (input.isKeyJustPressed(Input.Keys.DOWN) || input.isKeyJustPressed(Input.Keys.S)) sel = (sel + 1) % options.size
        if (input.isKeyJustPressed(Input.Keys.ENTER) || input.isKeyJustPressed(Input.Keys.E)) choose(sel, provider)
        if (tapX >= 0f) {
            for (i in options.indices) {
                val r = rowRect(w, h, i)
                if (tapX >= r[0] && tapX <= r[0] + r[2] && tapY >= r[1] && tapY <= r[1] + r[3]) {
                    sel = i
                    choose(i, provider)
                }
            }
        }
        return true
    }

    /** Runs the option whose label starts with [prefix]. Used by tests. */
    fun chooseByLabel(prefix: String): Boolean {
        val i = options.indexOfFirst { it.label.startsWith(prefix) }
        if (i < 0) return false
        choose(i, null)
        return true
    }

    /** The confirmation shown after the last choice, for tests. */
    val lastNotice: String? get() = notice

    private fun choose(i: Int, provider: (() -> List<DevOption>)?) {
        val o = options.getOrNull(i) ?: return
        o.run()
        // Rebuild the list so toggles show their new state, and confirm with the label as it now reads.
        if (provider != null) options = provider()
        notice = "Done: ${options.getOrNull(i)?.label ?: o.label}"
        noticeTimer = 2.5f
    }

    private var lineH = 20f

    private fun tabRect(w: Float, h: Float) = floatArrayOf(w / 2f - 34f, h - 24f, 68f, 22f)

    private fun panelRect(w: Float, h: Float): FloatArray {
        val pw = min(w * 0.9f, lineH * 30f)
        val ph = (options.size + 3) * lineH * 1.5f
        return floatArrayOf((w - pw) / 2f, h - 34f - ph, pw, ph)
    }

    private fun rowRect(w: Float, h: Float, i: Int): FloatArray {
        val p = panelRect(w, h)
        val rh = lineH * 1.5f
        val top = p[1] + p[3] - rh * 1.6f
        return floatArrayOf(p[0] + lineH * 0.5f, top - (i + 1) * rh, p[2] - lineH, rh * 0.92f)
    }

    /** Call last in a screen's draw. Draws the DEV tab (always, in dev mode) and the menu when open. */
    fun draw(
        enabled: Boolean, shapes: ShapeRenderer, batch: SpriteBatch, font: BitmapFont, glyphs: GlyphLayout,
        hud: Matrix4, w: Float, h: Float, godMode: Boolean = false,
    ) {
        if (!enabled) return
        lineH = font.lineHeight
        Gdx.gl.glEnable(com.badlogic.gdx.graphics.GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(com.badlogic.gdx.graphics.GL20.GL_SRC_ALPHA, com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA)
        shapes.projectionMatrix = hud
        batch.projectionMatrix = hud
        val tab = tabRect(w, h)
        shapes.begin(ShapeType.Filled)
        shapes.setColor(0.9f, 0.2f, 0.6f, if (open) 0.9f else 0.45f)
        shapes.rect(tab[0], tab[1], tab[2], tab[3])
        if (godMode) {
            shapes.setColor(1f, 0.8f, 0.2f, 0.85f)
            shapes.rect(tab[0] + tab[2] + 6f, tab[1], tab[2] * 1.9f, tab[3])
        }
        if (open) {
            val p = panelRect(w, h)
            shapes.setColor(0.06f, 0.02f, 0.06f, 0.94f)
            shapes.rect(p[0], p[1], p[2], p[3])
            for (i in options.indices) {
                val r = rowRect(w, h, i)
                shapes.setColor(0.9f, 0.2f, 0.6f, if (i == sel) 0.35f else 0.08f)
                shapes.rect(r[0], r[1], r[2], r[3])
            }
        }
        shapes.end()

        batch.begin()
        font.setColor(1f, 1f, 1f, 1f)
        glyphs.setText(font, "DEV")
        font.draw(batch, "DEV", tab[0] + (tab[2] - glyphs.width) / 2f, tab[1] + (tab[3] + font.capHeight) / 2f)
        if (godMode) {
            font.setColor(0.1f, 0.05f, 0f, 1f)
            glyphs.setText(font, "GOD MODE")
            font.draw(batch, "GOD MODE", tab[0] + tab[2] + 6f + (tab[2] * 1.9f - glyphs.width) / 2f, tab[1] + (tab[3] + font.capHeight) / 2f)
        }
        if (open) {
            val p = panelRect(w, h)
            font.setColor(1f, 0.6f, 0.85f, 1f)
            font.draw(batch, "DEVELOPER MENU   (game paused)   W/S choose, E/Enter run, Esc/F1 close", p[0] + lineH * 0.5f, p[1] + p[3] - lineH * 0.5f)
            for ((i, o) in options.withIndex()) {
                val r = rowRect(w, h, i)
                font.setColor(1f, 1f, 1f, 0.95f)
                font.draw(batch, o.label, r[0] + lineH * 0.5f, r[1] + (r[3] + font.capHeight) / 2f)
            }
            val n = notice
            if (n != null && noticeTimer > 0f) {
                font.setColor(0.6f, 1f, 0.7f, 1f)
                font.draw(batch, n, p[0] + lineH * 0.5f, p[1] + lineH * 1.2f)
            }
        }
        batch.end()
    }

    companion object {
        /** Cheats that make sense on every screen. */
        fun common(state: GameState): List<DevOption> {
            val s = state.stats
            return listOf(
                DevOption("God mode: ${if (s.invulnerable) "ON" else "OFF"}  (nothing hurts, nothing runs out)") { s.invulnerable = !s.invulnerable },
                DevOption("Refill health, energy, food, air, mind, fuel and hull") { s.refillAll() },
                DevOption("+1000 credits") { s.credits += 1000 },
                DevOption("Advance time by 1 minute (planet day and night)") { state.clock += 60f },
                DevOption("Start a new day (fresh bounty contracts)") {
                    s.day += 1
                    state.bounties.refreshIfNeeded(state)
                },
            )
        }
    }
}
