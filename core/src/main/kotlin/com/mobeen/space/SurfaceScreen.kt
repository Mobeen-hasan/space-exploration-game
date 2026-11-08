package com.mobeen.space

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType
import com.badlogic.gdx.utils.viewport.ExtendViewport
import com.badlogic.gdx.utils.viewport.ScreenViewport
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.max

/** Side-view exploration of one planet's surface, starting beside the parked ship. */
class SurfaceScreen(
    private val game: SpaceGame,
    private val state: GameState,
    private val planet: Planet,
) : ScreenAdapter() {
    private val camera = OrthographicCamera()
    private val viewport = ExtendViewport(1280f, 720f, camera)
    private val hudCamera = OrthographicCamera()
    private val hudViewport = ScreenViewport(hudCamera)

    private val shapes = ShapeRenderer().apply { setAutoShapeType(true) }
    private val batch = SpriteBatch()
    private val font = BitmapFont().apply { region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
    private val glyphs = GlyphLayout()

    private val terrain = Terrain(planet)
    private val scenery = SurfaceScenery(planet, terrain)
    private val ambient = Ambient(planet.type)
    private val starfield = Starfield(planet.id)
    private val player = SurfacePlayer(planet)
    private val controls = Controls()

    private var time = 0f
    private var accumulator = 0f
    private var camX = 0f
    private var camY = 0f

    /** Real seconds for one in-game day on this planet. */
    private val dayDuration = 90f + planet.dayHours * 3f
    private var dayPhase = 0.4f // late morning

    init {
        player.spawn(terrain, 70f)
        camX = player.x
        camY = player.y + CAMERA_RISE
        camera.zoom = ZOOM
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height)
        hudViewport.update(width, height, true)
    }

    override fun render(delta: Float) {
        val dt = min(delta, 0.05f)
        time += dt
        dayPhase = (dayPhase + dt / dayDuration) % 1f
        scenery.update(dayPhase)

        val w = Gdx.graphics.width.toFloat()
        val h = Gdx.graphics.height.toFloat()

        val nearShip = abs(terrain.padOffset(player.x)) < BOARD_RANGE
        controls.thrustLabel = "JUMP"
        controls.showBrake = false
        controls.actionEnabled = nearShip
        controls.actionLabel = "BOARD"
        controls.update(w, h)
        if (controls.actionPressed && nearShip) {
            game.returnToSpace()
            return
        }

        accumulator += dt
        while (accumulator >= STEP) {
            player.update(controls, terrain, STEP)
            accumulator -= STEP
        }

        // Camera: follow the player, looking a little ahead in the facing direction
        val follow = 1f - exp(-5f * dt)
        camX += (player.x + player.facing * LOOK_AHEAD - camX) * follow
        camY += (player.y + CAMERA_RISE - camY) * (1f - exp(-4f * dt))
        camera.position.set(camX, camY, 0f)
        camera.update()

        draw(w, h, nearShip)
    }

    private fun additive(on: Boolean) {
        Gdx.gl.glEnable(GL20.GL_BLEND)
        if (on) Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE)
        else Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun draw(w: Float, h: Float, nearShip: Boolean) {
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        // Sky, sun and moon (screen space)
        hudViewport.apply()
        shapes.projectionMatrix = hudCamera.combined
        additive(false)
        shapes.begin(ShapeType.Filled)
        scenery.drawSky(shapes, w, h)
        shapes.end()
        additive(true)
        shapes.begin(ShapeType.Filled)
        scenery.drawCelestials(shapes, w, h)
        shapes.end()

        // World
        viewport.apply()
        shapes.projectionMatrix = camera.combined
        val halfW = camera.viewportWidth * camera.zoom / 2f
        val halfH = camera.viewportHeight * camera.zoom / 2f
        val bottomY = camY - halfH - 40f

        val night = 1f - scenery.daylight
        if (night > 0.02f) {
            additive(true)
            shapes.begin(ShapeType.Filled)
            starfield.draw(shapes, camX, camY, camera.zoom, time, night)
            shapes.end()
        }

        additive(false)
        shapes.begin(ShapeType.Filled)
        scenery.drawMountains(shapes, camX, camY, halfW, bottomY)
        scenery.drawGround(shapes, camX, halfW, bottomY)
        scenery.drawDecor(shapes, camX, halfW)

        val shipX = Math.round(camX / Terrain.WIDTH) * Terrain.WIDTH
        scenery.drawShip(shapes, shipX, terrain.height(shipX), nearShip)
        scenery.drawPlayer(shapes, player)
        scenery.drawLiquid(shapes, camX, halfW, time)
        shapes.end()

        additive(true)
        shapes.begin(ShapeType.Filled)
        scenery.drawLiquidGlow(shapes, camX, halfW, time)
        scenery.drawPlayerGlow(shapes, player)
        ambient.draw(shapes, camX, camY, camera.zoom, time, scenery.daylight)
        shapes.end()

        // HUD
        hudViewport.apply()
        shapes.projectionMatrix = hudCamera.combined
        batch.projectionMatrix = hudCamera.combined
        font.data.setScale(max(0.8f, h / 720f * 1.05f))
        additive(false)
        shapes.begin(ShapeType.Filled)
        TouchHud.drawShapes(shapes, controls)
        shapes.end()

        batch.begin()
        drawText(w, h, nearShip)
        batch.end()
    }

    private fun drawText(w: Float, h: Float, nearShip: Boolean) {
        val margin = h * 0.03f
        val lh = font.lineHeight

        font.setColor(0.9f, 0.95f, 1f, 1f)
        font.draw(batch, planet.name, margin, h - margin)
        font.setColor(0.75f, 0.9f, 1f, 0.9f)
        val hours = (dayPhase * 24f).toInt()
        val minutes = ((dayPhase * 24f - hours) * 60f).toInt()
        font.draw(batch, "%02d:%02d   %s world".format(hours, minutes, planet.type.label), margin, h - margin - lh)
        font.draw(batch, "Gravity ${"%.2f".format(planet.gravityG)} g    ${planet.temperatureC} C", margin, h - margin - lh * 2f)
        font.draw(batch, "Atmosphere: ${planet.atmosphere}", margin, h - margin - lh * 3f)

        if (nearShip) {
            val t = "E / BOARD: return to your ship"
            glyphs.setText(font, t)
            font.setColor(1f, 0.9f, 0.5f, 1f)
            font.draw(batch, t, (w - glyphs.width) / 2f, margin + lh * 3f)
        }

        TouchHud.drawLabels(batch, font, glyphs, controls)

        if (time < 10f) {
            font.setColor(0.8f, 0.9f, 1f, min(1f, (10f - time) / 2f))
            val hint = "A / D: walk    W / Space: jump    Touch: left stick walks, JUMP button jumps"
            glyphs.setText(font, hint)
            font.draw(batch, hint, (w - glyphs.width) / 2f, margin + lh * 1.5f)
        }
    }

    override fun dispose() {
        shapes.dispose()
        batch.dispose()
        font.dispose()
    }

    private companion object {
        const val STEP = 1f / 120f
        const val ZOOM = 0.6f
        const val CAMERA_RISE = 70f
        const val LOOK_AHEAD = 110f
        const val BOARD_RANGE = 70f
    }
}
