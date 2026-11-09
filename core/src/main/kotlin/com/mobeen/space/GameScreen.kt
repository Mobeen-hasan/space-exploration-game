package com.mobeen.space

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType
import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.utils.viewport.ExtendViewport
import com.badlogic.gdx.utils.viewport.ScreenViewport
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class GameScreen(private val game: SpaceGame, private val state: GameState) : ScreenAdapter() {
    private val camera = OrthographicCamera()
    private val viewport = ExtendViewport(1280f, 720f, camera)
    private val hudCamera = OrthographicCamera()
    private val hudViewport = ScreenViewport(hudCamera)

    private val shapes = ShapeRenderer().apply { setAutoShapeType(true) }
    private val batch = SpriteBatch()
    private val font = BitmapFont().apply { region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
    private val glyphs = GlyphLayout()

    private val ship = state.ship
    private val controls = Controls()
    private val chase = ChaseCamera(camera)
    private val starfield = Starfield()
    private val particles = Particles()
    private val planetRenderer = PlanetRenderer()
    private val field = state.field

    private val nearby = ArrayList<Planet>()
    private val discovered = state.discovered

    private var time = 0f
    private var accumulator = 0f
    private var emitAccumulator = 0f
    private var toast: String? = null
    private var toastTimer = 0f

    init {
        chase.snapTo(ship)
    }

    override fun show() {
        state.pendingNotice?.let {
            showToast(it)
            state.pendingNotice = null
        }
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height)
        hudViewport.update(width, height, true)
    }

    override fun render(delta: Float) {
        val dt = min(delta, 0.05f)
        time += dt
        if (toastTimer > 0f) toastTimer -= dt

        val w = Gdx.graphics.width.toFloat()
        val h = Gdx.graphics.height.toFloat()

        state.clock += dt
        state.stats.tickBody(dt)
        if (state.stats.collapsed) {
            game.collapse()
            return
        }

        controls.thrustLabel = "THRUST"
        controls.showBrake = true
        controls.showThrust = true
        controls.actionEnabled = true
        controls.actionLabel = if (ship.landedOn != null) "EXIT" else "INSIDE"
        controls.update(w, h)
        val landedPlanet = ship.landedOn
        if (controls.actionPressed) {
            if (landedPlanet != null) game.enterSurface(landedPlanet) else game.enterInterior(InteriorScreen.COCKPIT_SPAWN)
            return
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.I)) {
            game.enterInterior(InteriorScreen.COCKPIT_SPAWN)
            return
        }
        field.near(ship.pos.x, ship.pos.y, RADAR_RANGE, nearby)

        accumulator += dt
        while (accumulator >= STEP) {
            step(STEP)
            accumulator -= STEP
        }
        chase.update(ship, dt)

        Gdx.gl.glClearColor(0.012f, 0.015f, 0.03f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        viewport.apply()
        drawWorld()

        hudViewport.apply()
        drawHud(w, h)
    }

    // ---------------------------------------------------------------- simulation

    private fun step(dt: Float) {
        if (ship.landedOn == null) {
            for (p in nearby) {
                val dx = p.x - ship.pos.x
                val dy = p.y - ship.pos.y
                val d = hypot(dx, dy)
                if (d < p.radius * 5f && d > 1f) {
                    val ratio = p.radius / max(d, p.radius)
                    val a = p.surfaceGravity * ratio * ratio
                    ship.vel.add(dx / d * a * dt, dy / d * a * dt)
                }
            }
        }

        val stats = state.stats
        val canThrust = stats.fuel > 0f && stats.hull > 0f
        if (!canThrust && controls.thrust > 0f && toastTimer <= 0f) {
            showToast(
                if (stats.fuel <= 0f) "Out of fuel! Press I to open the ship terminal."
                else "Hull wrecked! Repair it at the ship terminal (press I).",
            )
        }
        ship.update(controls, dt, canThrust)
        if (ship.thrusting > 0f) stats.fuel = max(0f, stats.fuel - Stats.FUEL_BURN * ship.thrusting * dt)
        emitExhaust(dt)
        particles.update(dt)

        if (ship.landedOn == null) {
            for (p in nearby) {
                val dx = ship.pos.x - p.x
                val dy = ship.pos.y - p.y
                val d = hypot(dx, dy)
                val minD = p.radius + Ship.RADIUS * 0.8f
                if (d >= minD || d < 0.001f) continue
                val nx = dx / d
                val ny = dy / d
                val speed = ship.vel.len()
                if (ship.noLandTimer <= 0f && speed < Ship.LAND_SPEED) {
                    ship.land(p, nx, ny)
                    onLanded(p)
                    break
                }
                val vn = ship.vel.x * nx + ship.vel.y * ny
                if (vn < 0f) {
                    ship.vel.x -= 1.45f * vn * nx
                    ship.vel.y -= 1.45f * vn * ny
                    if (speed >= Ship.LAND_SPEED) {
                        val damage = (speed - Ship.LAND_SPEED) * HULL_DAMAGE_PER_SPEED
                        state.stats.hull = max(0f, state.stats.hull - damage)
                        showToast("Hull damage! Brake below ${Ship.LAND_SPEED.toInt()} to land.")
                    }
                }
                ship.pos.set(p.x + nx * minD, p.y + ny * minD)
            }
        }
    }

    private fun emitExhaust(dt: Float) {
        if (ship.thrusting <= 0f) {
            emitAccumulator = 0f
            return
        }
        emitAccumulator += EXHAUST_RATE * ship.thrusting * dt
        while (emitAccumulator >= 1f) {
            emitAccumulator -= 1f
            val hx = ship.headingX
            val hy = ship.headingY
            val side = (MathUtils.random() - 0.5f) * 8f
            val ex = ship.pos.x - hx * 14f - hy * side
            val ey = ship.pos.y - hy * 14f + hx * side
            val a = ship.angle + MathUtils.PI + (MathUtils.random() - 0.5f) * 0.5f
            val speed = 160f + MathUtils.random() * 120f
            particles.emit(
                ex, ey,
                ship.vel.x + cos(a) * speed, ship.vel.y + sin(a) * speed,
                0.35f + MathUtils.random() * 0.35f, 3f + MathUtils.random() * 3f,
                1f, 0.45f, 0.1f,
            )
        }
    }

    private fun onLanded(p: Planet) {
        if (discovered.add(p.id)) showToast("New discovery: ${p.name}")
    }

    private var cachedTargetId = -1L
    private var cachedTarget: Planet? = null

    /** The planet a contract points at, rebuilt only when the contract changes. */
    private fun targetPlanet(c: Contract): Planet? {
        if (cachedTargetId != c.planetId) {
            cachedTargetId = c.planetId
            cachedTarget = SaveCodec.planetFromId(c.planetId)
        }
        return cachedTarget
    }

    private fun showToast(text: String) {
        toast = text
        toastTimer = 2.5f
    }

    // ---------------------------------------------------------------- world drawing

    private fun additive(on: Boolean) {
        Gdx.gl.glEnable(GL20.GL_BLEND)
        if (on) Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE)
        else Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun drawWorld() {
        // Stars and nebulae
        additive(true)
        shapes.projectionMatrix = camera.combined
        shapes.begin(ShapeType.Filled)
        starfield.draw(shapes, camera.position.x, camera.position.y, camera.zoom, time)
        shapes.end()

        // Planets
        additive(false)
        batch.projectionMatrix = camera.combined
        batch.begin()
        val halfW = camera.viewportWidth * camera.zoom / 2f
        val halfH = camera.viewportHeight * camera.zoom / 2f
        for (p in nearby) {
            val extent = p.radius * PlanetRenderer.QUAD
            if (abs(p.x - camera.position.x) > halfW + extent || abs(p.y - camera.position.y) > halfH + extent) continue
            planetRenderer.draw(batch, p, time, LIGHT_X, LIGHT_Y)
        }
        batch.end()
        batch.shader = null

        // Exhaust and engine flame (additive glow)
        additive(true)
        shapes.begin(ShapeType.Filled)
        particles.draw(shapes)
        if (ship.landedOn == null && ship.thrusting > 0f) drawFlame()
        shapes.end()

        // Ship
        additive(false)
        shapes.begin(ShapeType.Filled)
        drawShip()
        shapes.end()
    }

    private var shipCos = 1f
    private var shipSin = 0f
    private fun sx(lx: Float, ly: Float) = ship.pos.x + lx * shipCos - ly * shipSin
    private fun sy(lx: Float, ly: Float) = ship.pos.y + lx * shipSin + ly * shipCos

    private fun drawFlame() {
        val len = (16f + MathUtils.random() * 10f) * ship.thrusting
        shapes.setColor(1f, 0.55f, 0.15f, 0.9f)
        shapes.triangle(sx(-9f, 6f), sy(-9f, 6f), sx(-9f, -6f), sy(-9f, -6f), sx(-9f - len, 0f), sy(-9f - len, 0f))
        shapes.setColor(1f, 0.95f, 0.7f, 0.9f)
        shapes.triangle(sx(-9f, 3f), sy(-9f, 3f), sx(-9f, -3f), sy(-9f, -3f), sx(-9f - len * 0.55f, 0f), sy(-9f - len * 0.55f, 0f))
    }

    private fun drawShip() {
        shipCos = cos(ship.angle)
        shipSin = sin(ship.angle)
        // Two shaded halves
        shapes.setColor(0.80f, 0.86f, 0.95f, 1f)
        shapes.triangle(sx(26f, 0f), sy(26f, 0f), sx(-16f, 15f), sy(-16f, 15f), sx(-9f, 0f), sy(-9f, 0f))
        shapes.setColor(0.52f, 0.60f, 0.74f, 1f)
        shapes.triangle(sx(26f, 0f), sy(26f, 0f), sx(-9f, 0f), sy(-9f, 0f), sx(-16f, -15f), sy(-16f, -15f))
        // Cockpit
        shapes.setColor(0.35f, 0.85f, 1f, 1f)
        shapes.circle(sx(6f, 0f), sy(6f, 0f), 4f, 12)
        // Outline
        shapes.set(ShapeType.Line)
        shapes.setColor(1f, 1f, 1f, 0.55f)
        shapes.line(sx(26f, 0f), sy(26f, 0f), sx(-16f, 15f), sy(-16f, 15f))
        shapes.line(sx(-16f, 15f), sy(-16f, 15f), sx(-9f, 0f), sy(-9f, 0f))
        shapes.line(sx(-9f, 0f), sy(-9f, 0f), sx(-16f, -15f), sy(-16f, -15f))
        shapes.line(sx(-16f, -15f), sy(-16f, -15f), sx(26f, 0f), sy(26f, 0f))
        shapes.set(ShapeType.Filled)
    }

    // ---------------------------------------------------------------- HUD

    private fun drawHud(w: Float, h: Float) {
        additive(false)
        shapes.projectionMatrix = hudCamera.combined
        batch.projectionMatrix = hudCamera.combined
        val scale = h / 720f
        font.data.setScale(max(0.8f, scale * 1.05f))

        val landed = ship.landedOn
        val panelLines = landed?.let { planetLines(it) }

        shapes.begin(ShapeType.Filled)
        drawRadar(w, h)
        TouchHud.drawShapes(shapes, controls)
        panelLines?.let { drawPanelBackground(it, h) }
        val barH = font.lineHeight
        val gap = barH * 0.25f
        val barX = h * 0.03f
        val barY = h * 0.03f + barH * 3f
        StatsHud.drawShapes(shapes, state.stats, barX, barY, barH * 9f, barH, gap, showAir = false, showShip = true, time = time)
        shapes.end()

        batch.begin()
        drawHudText(w, h, panelLines)
        StatsHud.drawLabels(batch, font, state.stats, barX, barY, barH, gap, showAir = false, showShip = true)
        font.setColor(1f, 0.9f, 0.5f, 1f)
        font.draw(
            batch, "CREDITS ${state.stats.credits}    DAY ${state.stats.day}",
            barX, barY + StatsHud.stackHeight(barH, gap, showAir = false, showShip = true) + barH * 0.4f,
        )
        batch.end()
    }

    private fun drawRadar(w: Float, h: Float) {
        val s = min(w, h)
        val radius = s * 0.14f
        val margin = s * 0.03f
        val cx = w - radius - margin
        val cy = h - radius - margin
        val scale = radius / RADAR_RANGE
        val targetId = state.bounties.active?.planetId

        shapes.setColor(0.03f, 0.07f, 0.12f, 0.6f)
        shapes.circle(cx, cy, radius, 48)

        for (p in nearby) {
            val dx = (p.x - ship.pos.x) * scale
            val dy = (p.y - ship.pos.y) * scale
            val d = hypot(dx, dy)
            val edge = radius - 4f
            val clamped = d > edge
            val px = if (clamped) cx + dx / d * edge else cx + dx
            val py = if (clamped) cy + dy / d * edge else cy + dy
            val a = if (clamped) 0.45f else 1f
            if (p.id == targetId) shapes.setColor(1f, 0.8f, 0.25f, a) else shapes.setColor(p.c1.r, p.c1.g, p.c1.b, a)
            shapes.circle(px, py, if (clamped) 3.5f else max(3f, p.radius * scale), 16)
        }

        // Ship marker, pointing along the heading
        val hx = ship.headingX
        val hy = ship.headingY
        val m = radius * 0.09f
        shapes.setColor(1f, 1f, 1f, 1f)
        shapes.triangle(
            cx + hx * m * 1.6f, cy + hy * m * 1.6f,
            cx - hx * m - hy * m, cy - hy * m + hx * m,
            cx - hx * m + hy * m, cy - hy * m - hx * m,
        )

        shapes.set(ShapeType.Line)
        shapes.setColor(0.4f, 0.8f, 1f, 0.5f)
        shapes.circle(cx, cy, radius, 48)
        shapes.setColor(0.4f, 0.8f, 1f, 0.2f)
        shapes.circle(cx, cy, radius * 0.5f, 32)
        for (p in nearby) {
            if (p.id !in discovered) continue
            val dx = (p.x - ship.pos.x) * scale
            val dy = (p.y - ship.pos.y) * scale
            val d = hypot(dx, dy)
            if (d > radius - 4f) continue
            shapes.setColor(1f, 1f, 1f, 0.9f)
            shapes.circle(cx + dx, cy + dy, max(2.5f, p.radius * scale) + 3f, 16)
        }
        if (targetId != null) { // a gold ring marks the bounty target, even at the edge of the radar
            for (p in nearby) {
                if (p.id != targetId) continue
                val dx = (p.x - ship.pos.x) * scale
                val dy = (p.y - ship.pos.y) * scale
                val d = hypot(dx, dy)
                val edge = radius - 4f
                val rx = if (d > edge) cx + dx / d * edge else cx + dx
                val ry = if (d > edge) cy + dy / d * edge else cy + dy
                shapes.setColor(1f, 0.8f, 0.25f, 0.95f)
                shapes.circle(rx, ry, (if (d > edge) 3.5f else max(3f, p.radius * scale)) + 4f, 16)
            }
        }
        shapes.set(ShapeType.Filled)
    }

    private fun planetLines(p: Planet): List<String> = listOf(
        if (p.id in discovered) "LANDED  -  ${p.name}" else "LANDED  -  ${p.name}",
        "Type: ${p.type.label}",
        "Diameter: ${"%,d".format(p.diameterKm)} km",
        "Gravity: ${"%.2f".format(p.gravityG)} g",
        "Temperature: ${p.temperatureC} C",
        "Atmosphere: ${p.atmosphere}",
        "Day length: ${"%.1f".format(p.dayHours)} h",
        "E / EXIT: explore the surface    Hold THRUST: take off",
    )

    private fun panelMetrics(lines: List<String>, h: Float): FloatArray {
        val pad = font.lineHeight * 0.6f
        var maxW = 0f
        for (l in lines) {
            glyphs.setText(font, l)
            maxW = max(maxW, glyphs.width)
        }
        val margin = h * 0.03f
        val pw = maxW + pad * 2f
        val ph = lines.size * font.lineHeight + pad * 2f
        return floatArrayOf(margin, h - margin - ph - font.lineHeight * 4.4f, pw, ph, pad)
    }

    private fun drawPanelBackground(lines: List<String>, h: Float) {
        val m = panelMetrics(lines, h)
        shapes.setColor(0.04f, 0.06f, 0.12f, 0.78f)
        shapes.rect(m[0], m[1], m[2], m[3])
        shapes.set(ShapeType.Line)
        shapes.setColor(0.4f, 0.8f, 1f, 0.6f)
        shapes.rect(m[0], m[1], m[2], m[3])
        shapes.set(ShapeType.Filled)
    }

    private fun drawHudText(w: Float, h: Float, panelLines: List<String>?) {
        val margin = h * 0.03f
        val lh = font.lineHeight

        // Top-left status
        font.setColor(0.75f, 0.9f, 1f, 1f)
        font.draw(batch, "SPEED  ${ship.vel.len().toInt()}", margin, h - margin)
        font.draw(batch, "DISCOVERED  ${discovered.size}", margin, h - margin - lh)
        font.draw(batch, "POS  ${(ship.pos.x / 10f).toInt()}, ${(ship.pos.y / 10f).toInt()}", margin, h - margin - lh * 2f)
        state.bounties.active?.let { c ->
            val target = targetPlanet(c)
            val away = if (target != null) hypot(target.x - ship.pos.x, target.y - ship.pos.y) else 0f
            font.setColor(1f, 0.82f, 0.3f, 1f)
            font.draw(batch, "BOUNTY: ${c.target} on ${c.planetName}  (${"%.1f".format(away / 1000f)}k away)", margin, h - margin - lh * 3f)
        }

        // Landing panel
        if (panelLines != null) {
            val m = panelMetrics(panelLines, h)
            var y = m[1] + m[3] - m[4]
            for ((i, line) in panelLines.withIndex()) {
                if (i == 0) font.setColor(1f, 0.9f, 0.5f, 1f)
                else if (i == panelLines.lastIndex) font.setColor(0.6f, 0.8f, 1f, 0.9f)
                else font.setColor(1f, 1f, 1f, 1f)
                font.draw(batch, line, m[0] + m[4], y)
                y -= lh
            }
        }

        TouchHud.drawLabels(batch, font, glyphs, controls)

        // Toast
        val t = toast
        if (t != null && toastTimer > 0f) {
            font.setColor(1f, 0.9f, 0.5f, min(1f, toastTimer))
            glyphs.setText(font, t)
            font.draw(batch, t, (w - glyphs.width) / 2f, h - margin - lh)
        }

        // Controls hint at the start
        if (time < 10f) {
            font.setColor(0.8f, 0.9f, 1f, min(1f, (10f - time) / 2f))
            val hint = "W / arrows: fly   A / D: turn   S / Space: brake      Touch: left stick aims, THRUST button flies"
            glyphs.setText(font, hint)
            font.draw(batch, hint, (w - glyphs.width) / 2f, margin + lh * 1.5f)
        }
    }

    override fun dispose() {
        shapes.dispose()
        batch.dispose()
        font.dispose()
        planetRenderer.dispose()
    }

    private companion object {
        const val STEP = 1f / 120f
        const val RADAR_RANGE = 5000f
        const val EXHAUST_RATE = 160f
        const val HULL_DAMAGE_PER_SPEED = 0.08f
        const val LIGHT_X = -0.55f
        const val LIGHT_Y = 0.75f
    }
}
