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
import kotlin.math.min

/** One line in an [OverlayMenu]. Disabled rows are shown but cannot be chosen. [adjust] makes A/D change a value. */
class MenuRow(val label: String, val enabled: Boolean = true, val adjust: ((Int) -> Unit)? = null, val run: () -> Unit = {})

/**
 * A simple pop-up list drawn over a screen: W/S or arrows to move, E or Enter to choose, Esc to close,
 * or tap a row. The screen rebuilds the rows every frame, so they always show the current state.
 */
class OverlayMenu(private val title: String) {
    var open = false
    private var sel = 0
    private var lineH = 20f

    /** Put the highlight back on the first row, for when the rows are replaced by a different page. */
    fun resetSelection() {
        sel = 0
    }

    fun handle(w: Float, h: Float, rows: List<MenuRow>) {
        if (!open || rows.isEmpty()) return
        val input = Gdx.input
        if (input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            open = false
            return
        }
        if (sel >= rows.size || !rows[sel].enabled) sel = rows.indexOfFirst { it.enabled }.coerceAtLeast(0)
        val before = sel
        if (input.isKeyJustPressed(Input.Keys.UP) || input.isKeyJustPressed(Input.Keys.W)) sel = step(rows, -1)
        if (input.isKeyJustPressed(Input.Keys.DOWN) || input.isKeyJustPressed(Input.Keys.S)) sel = step(rows, 1)
        if (sel != before) Sfx.play(SoundId.CLICK, 0.35f)
        rows.getOrNull(sel)?.takeIf { it.enabled }?.adjust?.let { adjust ->
            if (input.isKeyJustPressed(Input.Keys.LEFT) || input.isKeyJustPressed(Input.Keys.A)) adjust(-1)
            if (input.isKeyJustPressed(Input.Keys.RIGHT) || input.isKeyJustPressed(Input.Keys.D)) adjust(1)
        }
        if (input.isKeyJustPressed(Input.Keys.ENTER) || input.isKeyJustPressed(Input.Keys.E)) {
            rows.getOrNull(sel)?.takeIf { it.enabled }?.let {
                Sfx.play(SoundId.CLICK)
                it.run.invoke()
            }
            return
        }
        if (input.justTouched()) {
            val tx = input.x.toFloat()
            val ty = h - input.y.toFloat()
            for (i in rows.indices) {
                val r = rowRect(w, h, i, rows.size)
                if (tx >= r[0] && tx <= r[0] + r[2] && ty >= r[1] && ty <= r[1] + r[3] && rows[i].enabled) {
                    sel = i
                    Sfx.play(SoundId.CLICK)
                    rows[i].run()
                    return
                }
            }
        }
    }

    /** Move the selection to the next choosable row in [dir], wrapping around. */
    private fun step(rows: List<MenuRow>, dir: Int): Int {
        var i = sel
        repeat(rows.size) {
            i = (i + dir + rows.size) % rows.size
            if (rows[i].enabled) return i
        }
        return sel
    }

    private fun panelRect(w: Float, h: Float, count: Int): FloatArray {
        val pw = min(w * 0.94f, lineH * 36f)
        val ph = (count + 2.2f) * lineH * 1.6f
        return floatArrayOf((w - pw) / 2f, (h - ph) / 2f, pw, ph)
    }

    private fun rowRect(w: Float, h: Float, i: Int, count: Int): FloatArray {
        val p = panelRect(w, h, count)
        val rh = lineH * 1.6f
        val top = p[1] + p[3] - rh * 1.4f
        return floatArrayOf(p[0] + lineH * 0.5f, top - (i + 1) * rh, p[2] - lineH, rh * 0.92f)
    }

    fun draw(
        shapes: ShapeRenderer, batch: SpriteBatch, font: BitmapFont, glyphs: GlyphLayout,
        hud: Matrix4, w: Float, h: Float, rows: List<MenuRow>,
    ) {
        if (!open) return
        lineH = font.lineHeight
        if (sel >= rows.size || rows.getOrNull(sel)?.enabled != true) sel = rows.indexOfFirst { it.enabled }.coerceAtLeast(0)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        shapes.projectionMatrix = hud
        batch.projectionMatrix = hud
        val p = panelRect(w, h, rows.size)
        shapes.begin(ShapeType.Filled)
        shapes.setColor(0f, 0f, 0f, 0.5f)
        shapes.rect(0f, 0f, w, h)
        shapes.setColor(0.05f, 0.06f, 0.09f, 0.95f)
        shapes.rect(p[0], p[1], p[2], p[3])
        for (i in rows.indices) {
            if (!rows[i].enabled) continue
            val r = rowRect(w, h, i, rows.size)
            shapes.setColor(0.4f, 0.7f, 1f, if (i == sel) 0.35f else 0.1f)
            shapes.rect(r[0], r[1], r[2], r[3])
        }
        shapes.set(ShapeType.Line)
        shapes.setColor(0.5f, 0.75f, 1f, 0.7f)
        shapes.rect(p[0], p[1], p[2], p[3])
        shapes.end()

        batch.begin()
        font.setColor(0.6f, 0.85f, 1f, 1f)
        font.draw(batch, "$title      W/S choose   E/Enter select   Esc close", p[0] + lineH * 0.6f, p[1] + p[3] - lineH * 0.6f)
        for ((i, row) in rows.withIndex()) {
            val r = rowRect(w, h, i, rows.size)
            if (row.enabled) font.setColor(1f, 1f, 1f, 1f) else font.setColor(0.7f, 0.75f, 0.8f, 0.9f)
            font.draw(batch, row.label, r[0] + lineH * 0.5f, r[1] + (r[3] + font.capHeight) / 2f)
        }
        batch.end()
    }
}

/** Splits [text] into lines of at most about [width] characters, breaking between words. */
fun wrapText(text: String, width: Int): List<String> {
    val lines = ArrayList<String>()
    var line = StringBuilder()
    for (word in text.split(" ")) {
        if (line.isNotEmpty() && line.length + 1 + word.length > width) {
            lines.add(line.toString())
            line = StringBuilder()
        }
        if (line.isNotEmpty()) line.append(' ')
        line.append(word)
    }
    if (line.isNotEmpty()) lines.add(line.toString())
    return lines
}
