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
import com.badlogic.gdx.utils.Align
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Your notes: every world you have landed on, what you scanned there, and a star map of everything you
 * have seen on radar. Opened with J (or from the ship terminal); the game pauses while it is open.
 */
class JournalOverlay {
    enum class Tab { LOG, MAP }

    var open = false
        private set
    var tab = Tab.LOG
        private set
    var selected = 0
        private set
    private var zoom = 1

    private val planets = HashMap<Long, Planet?>()
    private fun planet(id: Long): Planet? = planets.getOrPut(id) { SaveCodec.planetFromId(id) }

    fun show(which: Tab = Tab.LOG) {
        open = true
        tab = which
    }

    fun close() {
        open = false
    }

    /** The worlds you have landed on, alphabetically. */
    fun visited(state: GameState): List<Planet> =
        state.discovered.mapNotNull { planet(it) }.sortedBy { it.name }

    /** Call near the start of a screen's render. Returns true while open: draw, but do not update. */
    fun frame(w: Float, h: Float, state: GameState, blocked: Boolean): Boolean {
        val input = Gdx.input
        if (!open) {
            if (!blocked && input.isKeyJustPressed(Input.Keys.J)) {
                show()
                return true
            }
            return false
        }
        if (input.isKeyJustPressed(Input.Keys.J) || input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            open = false
            return true
        }
        if (input.isKeyJustPressed(Input.Keys.TAB) || input.isKeyJustPressed(Input.Keys.LEFT) ||
            input.isKeyJustPressed(Input.Keys.RIGHT) || input.isKeyJustPressed(Input.Keys.A) || input.isKeyJustPressed(Input.Keys.D)
        ) {
            tab = if (tab == Tab.LOG) Tab.MAP else Tab.LOG
        }
        val count = visited(state).size
        val up = input.isKeyJustPressed(Input.Keys.UP) || input.isKeyJustPressed(Input.Keys.W)
        val down = input.isKeyJustPressed(Input.Keys.DOWN) || input.isKeyJustPressed(Input.Keys.S)
        if (tab == Tab.LOG && count > 0) {
            if (up) selected = (selected + count - 1) % count
            if (down) selected = (selected + 1) % count
        } else if (tab == Tab.MAP) {
            if (up) zoom = max(0, zoom - 1)
            if (down) zoom = min(MAP_RANGES.size - 1, zoom + 1)
        }
        if (selected >= count) selected = max(0, count - 1)
        if (input.justTouched()) {
            val tx = input.x.toFloat()
            val ty = h - input.y.toFloat()
            val p = panel(w, h)
            val tabY = p[1] + p[3] - lineH * 1.6f
            if (ty in tabY..(tabY + lineH * 1.4f)) {
                if (tx in p[0]..(p[0] + p[2] / 2f)) tab = Tab.LOG else tab = Tab.MAP
            } else if (tab == Tab.LOG) {
                val listTop = tabY - lineH * 1.2f
                val row = ((listTop - ty) / (lineH * 1.3f)).toInt()
                if (tx < p[0] + p[2] * 0.38f && row in 0 until count) selected = row
            } else {
                zoom = (zoom + 1) % MAP_RANGES.size // tap the map to change its scale
            }
        }
        return true
    }

    private var lineH = 20f

    private fun panel(w: Float, h: Float) = floatArrayOf(w * 0.05f, h * 0.06f, w * 0.9f, h * 0.88f)

    fun draw(
        state: GameState, shapes: ShapeRenderer, batch: SpriteBatch, font: BitmapFont, glyphs: GlyphLayout,
        hud: Matrix4, w: Float, h: Float,
    ) {
        if (!open) return
        lineH = font.lineHeight
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        shapes.projectionMatrix = hud
        batch.projectionMatrix = hud
        val p = panel(w, h)
        val tabY = p[1] + p[3] - lineH * 1.6f

        shapes.begin(ShapeType.Filled)
        shapes.setColor(0f, 0f, 0f, 0.6f)
        shapes.rect(0f, 0f, w, h)
        shapes.setColor(0.04f, 0.05f, 0.08f, 0.97f)
        shapes.rect(p[0], p[1], p[2], p[3])
        shapes.setColor(0.4f, 0.75f, 1f, if (tab == Tab.LOG) 0.35f else 0.1f)
        shapes.rect(p[0] + 4f, tabY, p[2] / 2f - 8f, lineH * 1.4f)
        shapes.setColor(0.4f, 0.75f, 1f, if (tab == Tab.MAP) 0.35f else 0.1f)
        shapes.rect(p[0] + p[2] / 2f + 4f, tabY, p[2] / 2f - 8f, lineH * 1.4f)
        if (tab == Tab.LOG) drawLogShapes(state, shapes, p, tabY) else drawMapShapes(state, shapes, p, tabY)
        shapes.end()

        batch.begin()
        font.setColor(1f, 1f, 1f, 1f)
        centred(batch, font, glyphs, "JOURNAL", p[0] + p[2] / 4f, tabY + lineH)
        centred(batch, font, glyphs, "STAR MAP", p[0] + p[2] * 0.75f, tabY + lineH)
        if (tab == Tab.LOG) drawLogText(state, batch, font, glyphs, p, tabY) else drawMapText(state, batch, font, glyphs, p, tabY)
        font.setColor(0.6f, 0.75f, 0.9f, 0.9f)
        val keys = if (tab == Tab.LOG) "W/S choose a world    Tab switch to the map    J or Esc close"
        else "W/S or tap: zoom    Tab switch to the journal    J or Esc close"
        font.draw(batch, keys, p[0] + lineH, p[1] + lineH * 1.2f)
        batch.end()
    }

    private fun centred(batch: SpriteBatch, font: BitmapFont, glyphs: GlyphLayout, text: String, cx: Float, y: Float) {
        glyphs.setText(font, text)
        font.draw(batch, text, cx - glyphs.width / 2f, y)
    }

    // ------------------------------------------------------------------ the log

    private fun drawLogShapes(state: GameState, shapes: ShapeRenderer, p: FloatArray, tabY: Float) {
        val worlds = visited(state)
        val listTop = tabY - lineH * 1.2f
        for (i in worlds.indices) {
            if (i != selected) continue
            shapes.setColor(0.4f, 0.75f, 1f, 0.25f)
            shapes.rect(p[0] + lineH * 0.5f, listTop - (i + 1) * lineH * 1.3f + lineH * 0.2f, p[2] * 0.36f, lineH * 1.2f)
        }
        val divider = p[0] + p[2] * 0.39f
        shapes.setColor(0.4f, 0.75f, 1f, 0.3f)
        shapes.rect(divider, p[1] + lineH * 2f, 2f, tabY - p[1] - lineH * 2.5f)
    }

    private fun drawLogText(state: GameState, batch: SpriteBatch, font: BitmapFont, glyphs: GlyphLayout, p: FloatArray, tabY: Float) {
        val worlds = visited(state)
        val listTop = tabY - lineH * 1.2f
        if (worlds.isEmpty()) {
            font.setColor(0.8f, 0.85f, 0.9f, 1f)
            font.draw(batch, "No worlds logged yet. Land on a planet and its entry appears here.", p[0] + lineH, listTop)
            return
        }
        for ((i, planet) in worlds.withIndex()) {
            val signals = Discoveries.signalsFor(planet)
            val logged = signals.count { it.key in state.scanned }
            if (i == selected) font.setColor(1f, 1f, 1f, 1f) else font.setColor(0.75f, 0.8f, 0.9f, 1f)
            font.draw(batch, "${planet.name}   $logged/${signals.size}", p[0] + lineH, listTop - i * lineH * 1.3f)
        }

        val planet = worlds.getOrNull(selected) ?: return
        val x = p[0] + p[2] * 0.42f
        val width = p[2] * 0.55f
        var y = listTop
        font.setColor(1f, 0.85f, 0.5f, 1f)
        font.draw(batch, "${planet.name}  -  ${planet.type.label} world", x, y)
        y -= lineH * 1.4f
        font.setColor(0.85f, 0.9f, 1f, 1f)
        val facts = "Diameter ${"%,d".format(planet.diameterKm)} km    Gravity ${"%.2f".format(planet.gravityG)} g    " +
            "${planet.temperatureC} C    Day ${"%.1f".format(planet.dayHours)} h\nAtmosphere: ${planet.atmosphere}"
        glyphs.setText(font, facts, font.color, width, Align.left, true)
        font.draw(batch, glyphs, x, y)
        y -= glyphs.height + lineH * 1.2f

        if (state.bounties.hunting?.planetId == planet.id) {
            font.setColor(1f, 0.82f, 0.3f, 1f)
            font.draw(batch, "Your bounty target is hiding in the ruins here.", x, y)
            y -= lineH * 1.4f
        }
        val signals = Discoveries.signalsFor(planet)
        for (s in signals) {
            val known = s.key in state.scanned
            if (known) font.setColor(0.5f, 1f, 0.85f, 1f) else font.setColor(0.5f, 0.55f, 0.6f, 1f)
            val title = if (known) (if (s.landmark) "LANDMARK: " else "") + s.name else if (s.landmark) "Unscanned landmark" else "Unscanned signal"
            font.draw(batch, title, x, y)
            y -= lineH * 1.1f
            if (known) {
                font.setColor(0.8f, 0.82f, 0.85f, 0.95f)
                glyphs.setText(font, s.lore, font.color, width, Align.left, true)
                font.draw(batch, glyphs, x + lineH, y)
                y -= glyphs.height + lineH * 0.6f
            }
            if (y < p[1] + lineH * 3f) break
        }
    }

    // ------------------------------------------------------------------ the star map

    private fun mapGeometry(p: FloatArray, tabY: Float): FloatArray {
        val cx = p[0] + p[2] / 2f
        val cy = (p[1] + lineH * 2f + tabY) / 2f
        val radius = min(p[2], tabY - p[1] - lineH * 2f) * 0.46f
        return floatArrayOf(cx, cy, radius)
    }

    /** Every planet that has been on your radar or that you have visited, with its id. */
    private fun mapped(state: GameState): List<Planet> = (state.charted + state.discovered).mapNotNull { planet(it) }

    private fun drawMapShapes(state: GameState, shapes: ShapeRenderer, p: FloatArray, tabY: Float) {
        val g = mapGeometry(p, tabY)
        val cx = g[0]
        val cy = g[1]
        val radius = g[2]
        val range = MAP_RANGES[zoom]
        val scale = radius / range
        val ship = state.ship.pos
        shapes.setColor(0.03f, 0.06f, 0.1f, 1f)
        shapes.circle(cx, cy, radius, 64)
        val target = state.bounties.hunting?.planetId
        for (planet in mapped(state)) {
            val dx = (planet.x - ship.x) * scale
            val dy = (planet.y - ship.y) * scale
            if (hypot(dx, dy) > radius - 4f) continue
            val visited = planet.id in state.discovered
            val r = max(2.5f, planet.radius * scale)
            if (visited) shapes.setColor(planet.c1.r, planet.c1.g, planet.c1.b, 1f) else shapes.setColor(0.5f, 0.55f, 0.65f, 0.6f)
            shapes.circle(cx + dx, cy + dy, r, 16)
        }
        shapes.set(ShapeType.Line)
        shapes.setColor(0.4f, 0.75f, 1f, 0.35f)
        shapes.circle(cx, cy, radius, 64)
        shapes.circle(cx, cy, radius * 0.5f, 48)
        if (target != null) planet(target)?.let { t ->
            val dx = (t.x - ship.x) * scale
            val dy = (t.y - ship.y) * scale
            val d = hypot(dx, dy)
            val k = if (d > radius - 6f) (radius - 6f) / d else 1f
            shapes.setColor(1f, 0.8f, 0.25f, 1f)
            shapes.circle(cx + dx * k, cy + dy * k, max(4f, t.radius * scale) + 5f, 20)
        }
        shapes.set(ShapeType.Filled)
        val a = state.ship.angle
        val m = 8f
        shapes.setColor(1f, 1f, 1f, 1f)
        shapes.triangle(
            cx + cos(a) * m * 1.6f, cy + sin(a) * m * 1.6f,
            cx - cos(a) * m - sin(a) * m, cy - sin(a) * m + cos(a) * m,
            cx - cos(a) * m + sin(a) * m, cy - sin(a) * m - cos(a) * m,
        )
    }

    private fun drawMapText(state: GameState, batch: SpriteBatch, font: BitmapFont, glyphs: GlyphLayout, p: FloatArray, tabY: Float) {
        val g = mapGeometry(p, tabY)
        val radius = g[2]
        val range = MAP_RANGES[zoom]
        val scale = radius / range
        val ship = state.ship.pos
        for (planet in mapped(state)) {
            if (planet.id !in state.discovered) continue
            val dx = (planet.x - ship.x) * scale
            val dy = (planet.y - ship.y) * scale
            if (hypot(dx, dy) > radius - 20f) continue
            font.setColor(0.9f, 0.95f, 1f, 0.9f)
            glyphs.setText(font, planet.name)
            font.draw(batch, planet.name, g[0] + dx - glyphs.width / 2f, g[1] + dy - max(4f, planet.radius * scale) - 4f)
        }
        font.setColor(0.75f, 0.85f, 1f, 1f)
        val mapped = mapped(state).size
        font.draw(
            batch,
            "Worlds charted: $mapped    Visited: ${state.discovered.size}    Signals logged: ${state.scanned.size}    " +
                "Bounties brought in: ${state.bounties.completedCount}",
            p[0] + lineH, tabY - lineH * 0.6f,
        )
        font.setColor(0.6f, 0.7f, 0.85f, 0.9f)
        font.draw(batch, "Scale: ${(range / 1000).toInt()}k across the radius    Bright = visited, grey = seen on radar, gold ring = bounty target", p[0] + lineH, p[1] + lineH * 2.4f)
    }

    companion object {
        val MAP_RANGES = floatArrayOf(8000f, 16000f, 32000f, 64000f)
    }
}
