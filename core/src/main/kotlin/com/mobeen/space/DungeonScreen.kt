package com.mobeen.space

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.ScreenAdapter
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.viewport.ExtendViewport
import com.badlogic.gdx.utils.viewport.ScreenViewport
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Turn-based exploration of a planet's ruins, seen from above. One action per turn, then every monster acts.
 * Keyboard: WASD or arrows to move or attack (two keys for a diagonal), Space to wait, 1-6 to use items.
 * Touch or mouse: tap a tile to walk there, tap a monster beside you to attack, tap an item slot to use it.
 */
class DungeonScreen(
    private val game: SpaceGame,
    private val state: GameState,
    private val run: DungeonRun,
) : ScreenAdapter() {
    private class ActiveFloat(val text: String, val kind: Int, val x: Float, val y: Float) {
        var age = 0f
    }

    /** A recent attack, drawn briefly: an acid streak for spit, and a red flash where the attacker stands. */
    private class ActiveAttack(val e: AttackEvent) {
        var age = 0f
    }

    private val planet = run.planet

    private val camera = OrthographicCamera()
    private val viewport = ExtendViewport(576f, 324f, camera)
    private val hudCamera = OrthographicCamera()
    private val hudViewport = ScreenViewport(hudCamera)

    private val shapes = ShapeRenderer().apply { setAutoShapeType(true) }
    private val batch = SpriteBatch()
    private val font = BitmapFont().apply { region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
    private val worldFont = BitmapFont().apply {
        region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        data.setScale(0.7f)
    }
    private val glyphs = GlyphLayout()

    private val floorColor = Color(planet.c0.r * 0.45f + 0.05f, planet.c0.g * 0.45f + 0.05f, planet.c0.b * 0.45f + 0.05f, 1f)
    private val wallColor = Color(planet.c1.r * 0.42f + 0.04f, planet.c1.g * 0.42f + 0.04f, planet.c1.b * 0.42f + 0.04f, 1f)

    private var time = 0f
    private var moveCooldown = 0f
    private var travelTimer = 0f
    private val travel = ArrayDeque<IntArray>()
    private var leaving = false

    private var camX = run.px * TILE + TILE / 2f
    private var camY = run.py * TILE + TILE / 2f
    private var drawPx = run.px.toFloat()
    private var drawPy = run.py.toFloat()
    private var lastDepth = run.depth
    private val floats = ArrayList<ActiveFloat>()
    private val strikes = ArrayList<ActiveAttack>()

    private var toast: String? = null
    private var toastTimer = 0f

    // HUD layout, in screen pixels, recomputed every frame
    private val slotRects = Array(DungeonRun.PACK_SIZE) { FloatArray(4) }
    private val waitCenter = Vector2()
    private var waitRadius = 0f
    private val lightCenter = Vector2()
    private val stairsCenter = Vector2()
    private var stairsRadius = 0f

    init {
        camera.position.set(camX, camY, 0f)
    }

    override fun show() {
        state.pendingNotice?.let {
            toast = it
            toastTimer = 4f
            state.pendingNotice = null
        }
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height)
        hudViewport.update(width, height, true)
    }

    // ------------------------------------------------------------------ frame

    override fun render(delta: Float) {
        val dt = min(delta, 0.05f)
        time += dt
        if (toastTimer > 0f) toastTimer -= dt

        val w = Gdx.graphics.width.toFloat()
        val h = Gdx.graphics.height.toFloat()

        if (game.devMenu.frame(game.devMode, w, h) { devOptions() }) { // paused while the developer menu is open
            layout(w, h)
            animate(dt)
            draw(w, h)
            game.devMenu.draw(game.devMode, shapes, batch, font, glyphs, hudCamera.combined, w, h, state.stats.invulnerable)
            return
        }

        if (!leaving) {
            if (run.exited) {
                leaving = true
                state.dungeon = null
                if (run.completed) state.pendingNotice = "Bounty complete! You collected ${run.reward} credits."
                game.enterSurface(planet, Terrain.ENTRANCE_X - 25f)
            } else if (run.dead || state.stats.collapsed) {
                leaving = true
                state.dungeon = null
                game.collapse()
            }
        }

        layout(w, h)
        if (!leaving) handleInput(dt, w, h)
        animate(dt)
        draw(w, h)
        game.devMenu.draw(game.devMode, shapes, batch, font, glyphs, hudCamera.combined, w, h, state.stats.invulnerable)
    }

    private fun devOptions(): List<DevOption> {
        val list = ArrayList(DevMenu.common(state))
        list.add(DevOption("Reveal this floor") { run.devRevealFloor() })
        list.add(DevOption("Remove all monsters on this floor") { run.devClearMonsters() })
        if (run.level.hasDown) list.add(DevOption("Go down to floor ${run.depth + 1}") { run.devDescend() })
        if (run.depth < run.maxDepth) list.add(DevOption("Go to the last floor") { while (run.devDescend()) Unit })
        if (run.level.monsters.any { it.type.boss }) {
            list.add(DevOption("Teleport next to the bounty target") { if (run.devTeleportToTarget()) lastDepth = -1 })
        }
        list.add(DevOption("Fill the lantern") { run.devFillLantern() })
        list.add(DevOption("Get supplies (medkits, tonics, oil, stim)") { run.devGiveSupplies() })
        list.add(DevOption("Set mind to 20 (see hallucinations)") { state.stats.mind = 20f })
        return list
    }

    private fun layout(w: Float, h: Float) {
        val s = min(w, h)
        val slot = s * 0.085f
        val gap = slot * 0.12f
        val margin = s * 0.03f
        val total = DungeonRun.PACK_SIZE * slot + (DungeonRun.PACK_SIZE - 1) * gap
        val startX = w - margin - total
        for (i in slotRects.indices) {
            slotRects[i][0] = startX + i * (slot + gap)
            slotRects[i][1] = margin
            slotRects[i][2] = slot
            slotRects[i][3] = slot
        }
        waitRadius = slot * 0.75f
        waitCenter.set(w - margin - waitRadius, margin + slot + gap * 2f + waitRadius)
        lightCenter.set(waitCenter.x - waitRadius * 2.4f, waitCenter.y)
        stairsRadius = waitRadius * 1.15f
        stairsCenter.set(waitCenter.x, waitCenter.y + waitRadius + stairsRadius + gap * 2f)
    }

    // ------------------------------------------------------------------ input

    private fun cancelTravel() = travel.clear()

    private fun handleInput(dt: Float, w: Float, h: Float) {
        moveCooldown -= dt
        travelTimer -= dt
        val input = Gdx.input

        val itemKeys = intArrayOf(Input.Keys.NUM_1, Input.Keys.NUM_2, Input.Keys.NUM_3, Input.Keys.NUM_4, Input.Keys.NUM_5, Input.Keys.NUM_6)
        for (i in itemKeys.indices) {
            if (input.isKeyJustPressed(itemKeys[i])) {
                cancelTravel()
                run.useItem(i)
            }
        }
        if (input.isKeyJustPressed(Input.Keys.SPACE) || input.isKeyJustPressed(Input.Keys.PERIOD)) {
            cancelTravel()
            run.waitTurn()
        }
        if (input.isKeyJustPressed(Input.Keys.L)) run.toggleLantern()
        if ((input.isKeyJustPressed(Input.Keys.E) || input.isKeyJustPressed(Input.Keys.ENTER)) && run.stairsHere() != null) {
            cancelTravel()
            run.useStairs()
        }

        var dx = 0
        var dy = 0
        if (input.isKeyPressed(Input.Keys.A) || input.isKeyPressed(Input.Keys.LEFT)) dx -= 1
        if (input.isKeyPressed(Input.Keys.D) || input.isKeyPressed(Input.Keys.RIGHT)) dx += 1
        if (input.isKeyPressed(Input.Keys.W) || input.isKeyPressed(Input.Keys.UP)) dy += 1
        if (input.isKeyPressed(Input.Keys.S) || input.isKeyPressed(Input.Keys.DOWN)) dy -= 1
        if ((dx != 0 || dy != 0) && moveCooldown <= 0f) {
            cancelTravel()
            var moved = run.tryMove(dx, dy)
            if (!moved && dx != 0 && dy != 0) moved = run.tryMove(dx, 0) || run.tryMove(0, dy) // slide along walls
            if (moved) moveCooldown = MOVE_DELAY
        }

        if (input.justTouched()) handleTap(input.x.toFloat(), input.y.toFloat(), h)

        if (travel.isNotEmpty() && travelTimer <= 0f && moveCooldown <= 0f) stepTravel()
    }

    private fun stepTravel() {
        if (run.visibleMonsters().any { it.state == AiState.HUNTING }) {
            cancelTravel() // something is after you: stop and let the player decide
            return
        }
        val next = travel.removeFirst()
        val dx = next[0] - run.px
        val dy = next[1] - run.py
        if (abs(dx) > 1 || abs(dy) > 1) {
            cancelTravel()
            return
        }
        val seenBefore = run.visibleMonsters().size
        val hpBefore = state.stats.health
        if (!run.tryMove(dx, dy)) {
            cancelTravel()
            return
        }
        travelTimer = TRAVEL_DELAY
        if (run.visibleMonsters().size > seenBefore || state.stats.health < hpBefore) cancelTravel()
    }

    private fun handleTap(sx: Float, sy: Float, h: Float) {
        val ty = h - sy // HUD coordinates have y pointing up
        for (i in slotRects.indices) {
            val r = slotRects[i]
            if (sx >= r[0] && sx <= r[0] + r[2] && ty >= r[1] && ty <= r[1] + r[3]) {
                cancelTravel()
                run.useItem(i)
                return
            }
        }
        if (run.stairsHere() != null && hypot(sx - stairsCenter.x, ty - stairsCenter.y) <= stairsRadius) {
            cancelTravel()
            run.useStairs()
            return
        }
        if (hypot(sx - lightCenter.x, ty - lightCenter.y) <= waitRadius) {
            run.toggleLantern()
            return
        }
        if (hypot(sx - waitCenter.x, ty - waitCenter.y) <= waitRadius) {
            cancelTravel()
            run.waitTurn()
            return
        }

        val world = viewport.unproject(Vector2(sx, sy))
        val tx = floor(world.x / TILE).toInt()
        val tileY = floor(world.y / TILE).toInt()
        val lv = run.level
        if (!lv.inBounds(tx, tileY) || !lv.explored[lv.index(tx, tileY)]) return

        cancelTravel()
        if (tx == run.px && tileY == run.py) {
            run.waitTurn()
            return
        }
        val monster = lv.monsterAt(tx, tileY)?.takeIf { lv.visible[lv.index(tx, tileY)] }
        if (monster != null && max(abs(tx - run.px), abs(tileY - run.py)) == 1) {
            run.tryMove(tx - run.px, tileY - run.py)
            return
        }
        val route = run.pathTo(tx, tileY)
        if (route == null || route.isEmpty()) {
            toast = "You can't get there."
            toastTimer = 1.5f
            return
        }
        travel.addAll(route)
    }

    // ------------------------------------------------------------------ animation

    private fun animate(dt: Float) {
        if (run.depth != lastDepth) { // new floor: snap instead of sliding across the map
            lastDepth = run.depth
            drawPx = run.px.toFloat()
            drawPy = run.py.toFloat()
            camX = run.px * TILE + TILE / 2f
            camY = run.py * TILE + TILE / 2f
            floats.clear()
            strikes.clear()
            cancelTravel()
        }
        val k = 1f - exp(-18f * dt)
        drawPx += (run.px - drawPx) * k
        drawPy += (run.py - drawPy) * k
        for (m in run.level.monsters) {
            m.drawX += (m.x - m.drawX) * k
            m.drawY += (m.y - m.drawY) * k
            m.flash = max(0f, m.flash - dt)
        }

        for (p in run.phantoms) {
            p.drawX += (p.x - p.drawX) * k * 0.5f
            p.drawY += (p.y - p.drawY) * k * 0.5f
        }

        val follow = 1f - exp(-9f * dt)
        camX += (drawPx * TILE + TILE / 2f - camX) * follow
        camY += (drawPy * TILE + TILE / 2f - camY) * follow
        val fray = ((MIND_SHAKE - state.stats.mind) / MIND_SHAKE).coerceIn(0f, 1f)
        val shake = fray * 2.5f
        camera.position.set(camX + sin(time * 31f) * shake, camY + sin(time * 23f + 1f) * shake, 0f)

        for (f in run.floatTexts) floats.add(ActiveFloat(f.text, f.kind, f.x * TILE + TILE / 2f, f.y * TILE + TILE))
        run.floatTexts.clear()
        for (a in run.attacks) strikes.add(ActiveAttack(a))
        run.attacks.clear()
        strikes.removeAll { a ->
            a.age += dt
            a.age > STRIKE_TIME
        }
        val it = floats.iterator()
        while (it.hasNext()) {
            val f = it.next()
            f.age += dt
            if (f.age > 0.9f) it.remove()
        }
    }

    // ------------------------------------------------------------------ drawing

    private fun additive(on: Boolean) {
        Gdx.gl.glEnable(GL20.GL_BLEND)
        if (on) Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE)
        else Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private fun draw(w: Float, h: Float) {
        Gdx.gl.glClearColor(0.01f, 0.012f, 0.02f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        additive(false)

        viewport.apply()
        camera.update()
        shapes.projectionMatrix = camera.combined
        shapes.begin(ShapeType.Filled)
        drawTiles()
        drawItems()
        drawMonsters()
        drawPhantoms()
        drawPlayer()
        shapes.end()

        additive(true)
        shapes.begin(ShapeType.Filled)
        drawGlow()
        drawStrikes()
        shapes.end()
        additive(false)

        batch.projectionMatrix = camera.combined
        batch.begin()
        drawWorldText()
        batch.end()

        hudViewport.apply()
        shapes.projectionMatrix = hudCamera.combined
        batch.projectionMatrix = hudCamera.combined
        font.data.setScale(max(0.8f, h / 720f * 1.05f))
        additive(false)
        shapes.begin(ShapeType.Filled)
        drawHudShapes(w, h)
        shapes.end()
        batch.begin()
        drawHudText(w, h)
        batch.end()
    }

    private fun drawTiles() {
        val lv = run.level
        val halfW = camera.viewportWidth * camera.zoom / 2f
        val halfH = camera.viewportHeight * camera.zoom / 2f
        val minX = max(0, floor((camera.position.x - halfW) / TILE).toInt() - 1)
        val maxX = min(lv.width - 1, floor((camera.position.x + halfW) / TILE).toInt() + 1)
        val minY = max(0, floor((camera.position.y - halfH) / TILE).toInt() - 1)
        val maxY = min(lv.height - 1, floor((camera.position.y + halfH) / TILE).toInt() + 1)

        for (ty in minY..maxY) {
            for (tx in minX..maxX) {
                val i = lv.index(tx, ty)
                if (!lv.explored[i]) continue
                val visible = lv.visible[i]
                val light = tileLight(i, tx, ty, visible)
                val x = tx * TILE
                val y = ty * TILE
                when (lv.tiles[i]) {
                    Tile.WALL -> {
                        tinted(wallColor, light)
                        shapes.rect(x, y, TILE, TILE)
                        tinted(wallColor, light * 1.35f)
                        shapes.rect(x, y + TILE - 4f, TILE, 4f)
                        tinted(wallColor, light * 0.6f)
                        shapes.rect(x, y, TILE, 4f)
                    }
                    else -> {
                        val checker = if ((tx + ty) % 2 == 0) 1f else 0.93f
                        tinted(floorColor, light * checker)
                        shapes.rect(x, y, TILE, TILE)
                        drawSpecialTile(lv.tiles[i], x, y, light)
                    }
                }
            }
        }
    }

    /** How bright a tile is drawn: warm and steady by a brazier, flickering and fading by lantern, dim in memory. */
    private fun tileLight(i: Int, tx: Int, ty: Int, visible: Boolean): Float {
        if (!visible) return 0.16f
        val lv = run.level
        if (lv.lit[i]) return 0.9f + 0.05f * sin(time * 6f + tx)
        val reach = run.sightRadius + 1f
        val d = hypot((tx - run.px).toFloat(), (ty - run.py).toFloat())
        val flicker = 0.94f + 0.06f * sin(time * 13f) * sin(time * 7.3f + 1f)
        return max(0.12f, (1f - min(1f, d / reach) * 0.8f) * flicker)
    }

    private fun drawSpecialTile(tile: Tile, x: Float, y: Float, light: Float) {
        when (tile) {
            Tile.BRAZIER -> {
                shapes.setColor(0.25f * light, 0.22f * light, 0.2f * light, 1f)
                shapes.rect(x + 7f, y + 4f, TILE - 14f, 6f)
                shapes.rect(x + 10f, y + 10f, TILE - 20f, 8f)
                val f = 0.5f + 0.5f * sin(time * 9f + x)
                shapes.setColor(1f, 0.55f + 0.2f * f, 0.15f, 1f)
                shapes.triangle(x + 9f, y + 18f, x + TILE - 9f, y + 18f, x + TILE / 2f + sin(time * 7f) * 2f, y + 30f + f * 2f)
                shapes.setColor(1f, 0.9f, 0.5f, 1f)
                shapes.triangle(x + 12f, y + 18f, x + TILE - 12f, y + 18f, x + TILE / 2f, y + 25f + f * 2f)
            }
            Tile.STAIRS_DOWN -> {
                shapes.setColor(0.08f * light, 0.08f * light, 0.1f * light, 1f)
                shapes.rect(x + 3f, y + 3f, TILE - 6f, TILE - 6f)
                shapes.setColor(0.95f * light, 0.8f * light, 0.3f * light, 1f)
                shapes.triangle(x + 8f, y + 20f, x + TILE - 8f, y + 20f, x + TILE / 2f, y + 8f)
                shapes.rect(x + 12f, y + 22f, TILE - 24f, 3f)
            }
            Tile.STAIRS_UP -> {
                shapes.setColor(0.12f * light, 0.2f * light, 0.18f * light, 1f)
                shapes.rect(x + 3f, y + 3f, TILE - 6f, TILE - 6f)
                shapes.setColor(0.5f * light, 0.95f * light, 0.7f * light, 1f)
                shapes.triangle(x + 8f, y + 12f, x + TILE - 8f, y + 12f, x + TILE / 2f, y + 24f)
                shapes.rect(x + 12f, y + 7f, TILE - 24f, 3f)
            }
            Tile.LAVA -> {
                val p = 0.5f + 0.5f * sin(time * 3f + x * 0.1f)
                shapes.setColor((0.9f + 0.1f * p) * light, (0.35f + 0.2f * p) * light, 0.08f * light, 1f)
                shapes.rect(x, y, TILE, TILE)
            }
            Tile.ACID -> {
                val p = 0.5f + 0.5f * sin(time * 2.5f + y * 0.1f)
                shapes.setColor(0.35f * light, (0.75f + 0.15f * p) * light, 0.15f * light, 1f)
                shapes.rect(x, y, TILE, TILE)
            }
            Tile.WATER -> {
                val p = 0.5f + 0.5f * sin(time * 2f + x * 0.08f)
                shapes.setColor(0.12f * light, (0.38f + 0.1f * p) * light, 0.7f * light, 1f)
                shapes.rect(x, y, TILE, TILE)
            }
            else -> {}
        }
    }

    private fun tinted(c: Color, k: Float) = shapes.setColor(min(1f, c.r * k), min(1f, c.g * k), min(1f, c.b * k), 1f)

    private fun drawItems() {
        val lv = run.level
        for (it in lv.items) {
            if (!lv.visible[lv.index(it.x, it.y)]) continue
            val cx = it.x * TILE + TILE / 2f
            val cy = it.y * TILE + TILE / 2f + sin(time * 3f + it.x) * 1.5f
            val c = itemColor(it.type)
            shapes.setColor(c[0], c[1], c[2], 0.25f)
            shapes.circle(cx, cy, 11f, 14)
            shapes.setColor(c[0], c[1], c[2], 1f)
            shapes.circle(cx, cy, 6f, 12)
            shapes.setColor(1f, 1f, 1f, 0.8f)
            shapes.circle(cx - 2f, cy + 2f, 1.8f, 6)
        }
    }

    private fun itemColor(t: ItemType): FloatArray = when (t) {
        ItemType.MEDKIT -> floatArrayOf(0.95f, 0.3f, 0.3f)
        ItemType.RATION -> floatArrayOf(0.95f, 0.6f, 0.2f)
        ItemType.STIM -> floatArrayOf(0.95f, 0.9f, 0.3f)
        ItemType.CREDITS -> floatArrayOf(1f, 0.85f, 0.25f)
        ItemType.BATON_MOD -> floatArrayOf(0.35f, 0.85f, 1f)
        ItemType.ARMOR_PLATE -> floatArrayOf(0.65f, 0.72f, 0.9f)
        ItemType.OIL_FLASK -> floatArrayOf(0.95f, 0.72f, 0.3f)
        ItemType.TONIC -> floatArrayOf(0.72f, 0.5f, 1f)
    }

    private fun drawMonsters() {
        val lv = run.level
        for (m in lv.monsters) {
            if (!lv.visible[lv.index(m.x, m.y)]) continue
            val cx = m.drawX * TILE + TILE / 2f
            val cy = m.drawY * TILE + TILE / 2f
            val body: FloatArray
            val r: Float
            when (m.type) {
                MonsterType.SCUTTLER -> { body = floatArrayOf(0.75f, 0.35f, 0.2f); r = 8f }
                MonsterType.HUSK -> { body = floatArrayOf(0.55f, 0.6f, 0.35f); r = 11f }
                MonsterType.SPITTER -> { body = floatArrayOf(0.4f, 0.8f, 0.5f); r = 10f }
                MonsterType.BRUTE -> { body = floatArrayOf(0.6f, 0.25f, 0.3f); r = 14f }
                MonsterType.FUGITIVE -> { body = floatArrayOf(0.65f, 0.25f, 0.8f); r = 13f }
            }
            shapes.setColor(0f, 0f, 0f, 0.35f)
            shapes.ellipse(cx - r, cy - r - 2f, r * 2f, r * 0.7f)
            val flash = if (m.flash > 0f) 0.6f else 0f
            shapes.setColor(min(1f, body[0] + flash), min(1f, body[1] + flash), min(1f, body[2] + flash), 1f)
            shapes.circle(cx, cy, r, 16)
            if (m.type == MonsterType.BRUTE) {
                shapes.triangle(cx - r * 0.8f, cy + r * 0.5f, cx - r * 0.3f, cy + r * 0.9f, cx - r * 1.1f, cy + r * 1.3f)
                shapes.triangle(cx + r * 0.8f, cy + r * 0.5f, cx + r * 0.3f, cy + r * 0.9f, cx + r * 1.1f, cy + r * 1.3f)
            }
            if (m.type == MonsterType.FUGITIVE) { // wears a hat: the one you are hunting
                shapes.setColor(0.12f, 0.1f, 0.12f, 1f)
                shapes.rect(cx - r * 0.9f, cy + r * 0.55f, r * 1.8f, 3f)
                shapes.rect(cx - r * 0.5f, cy + r * 0.55f, r, r * 0.6f)
            }
            if (m.state == AiState.SLEEPING) {
                shapes.setColor(0.05f, 0.05f, 0.05f, 1f)
                shapes.rect(cx - r * 0.55f, cy + 1f, r * 0.4f, 1.5f)
                shapes.rect(cx + r * 0.15f, cy + 1f, r * 0.4f, 1.5f)
            } else {
                shapes.setColor(1f, 1f, 1f, 1f)
                shapes.circle(cx - r * 0.35f, cy + 2f, r * 0.25f, 8)
                shapes.circle(cx + r * 0.35f, cy + 2f, r * 0.25f, 8)
                shapes.setColor(0.05f, 0.05f, 0.05f, 1f)
                shapes.circle(cx - r * 0.35f, cy + 2f, r * 0.11f, 6)
                shapes.circle(cx + r * 0.35f, cy + 2f, r * 0.11f, 6)
            }
            if (m.hp < m.maxHp) {
                val bw = r * 2f
                shapes.setColor(0f, 0f, 0f, 0.7f)
                shapes.rect(cx - r, cy + r + 4f, bw, 3f)
                shapes.setColor(0.9f, 0.25f, 0.25f, 1f)
                shapes.rect(cx - r, cy + r + 4f, bw * m.hp.toFloat() / m.maxHp, 3f)
            }
        }
    }

    /** Imagined shapes: dark silhouettes with pale eyes that never quite hold still. */
    private fun drawPhantoms() {
        val lv = run.level
        for (p in run.phantoms) {
            if (!lv.explored[lv.index(p.x, p.y)]) continue
            val cx = p.drawX * TILE + TILE / 2f + sin(time * 17f + p.size) * 1.5f
            val cy = p.drawY * TILE + TILE / 2f
            val a = 0.55f + 0.3f * sin(time * 5f + p.size)
            shapes.setColor(0.06f, 0.02f, 0.08f, a)
            shapes.circle(cx, cy, p.size, 16)
            shapes.setColor(0.9f, 0.9f, 1f, a)
            shapes.circle(cx - p.size * 0.35f, cy + 2f, 1.6f, 6)
            shapes.circle(cx + p.size * 0.35f, cy + 2f, 1.6f, 6)
        }
    }

    private fun drawStrikes() {
        for (a in strikes) {
            val t = 1f - a.age / STRIKE_TIME
            val fx = a.e.fromX * TILE + TILE / 2f
            val fy = a.e.fromY * TILE + TILE / 2f
            val tx = a.e.toX * TILE + TILE / 2f
            val ty = a.e.toY * TILE + TILE / 2f
            if (a.e.ranged) {
                shapes.setColor(0.5f, 1f, 0.3f, 0.8f * t)
                shapes.rectLine(fx, fy, tx, ty, 3f)
                shapes.setColor(0.8f, 1f, 0.6f, 0.9f * t)
                shapes.circle(fx + (tx - fx) * (1f - t), fy + (ty - fy) * (1f - t), 4f, 10)
            }
            // Mark where the blow came from, even if the attacker is hidden in the dark.
            shapes.setColor(1f, 0.15f, 0.1f, 0.55f * t)
            shapes.circle(fx, fy, TILE * 0.45f, 16)
            if (a.e.hidden) {
                shapes.setColor(1f, 0.3f, 0.2f, 0.9f * t)
                shapes.circle(fx - 4f, fy + 2f, 2f, 6)
                shapes.circle(fx + 4f, fy + 2f, 2f, 6)
            }
        }
    }

    /** Light sources, drawn additively: brazier glows and your lantern's halo. */
    private fun drawGlow() {
        val lv = run.level
        val halfW = camera.viewportWidth * camera.zoom / 2f + TILE * 6f
        val halfH = camera.viewportHeight * camera.zoom / 2f + TILE * 6f
        for (y in 0 until lv.height) for (x in 0 until lv.width) {
            if (lv.tiles[lv.index(x, y)] != Tile.BRAZIER || !lv.explored[lv.index(x, y)]) continue
            val cx = x * TILE + TILE / 2f
            val cy = y * TILE + TILE / 2f
            if (abs(cx - camera.position.x) > halfW || abs(cy - camera.position.y) > halfH) continue
            val f = 0.85f + 0.15f * sin(time * 5f + x)
            for (k in 0 until 5) {
                shapes.setColor(1f, 0.55f, 0.2f, 0.035f * f)
                shapes.circle(cx, cy + 6f, TILE * (0.6f + k * 0.8f), 24)
            }
        }
        if (run.lanternLit) {
            val cx = drawPx * TILE + TILE / 2f
            val cy = drawPy * TILE + TILE / 2f
            val f = 0.9f + 0.1f * sin(time * 13f)
            for (k in 0 until 4) {
                shapes.setColor(1f, 0.78f, 0.45f, 0.03f * f)
                shapes.circle(cx, cy, TILE * (1f + k * run.sightRadius * 0.3f), 28)
            }
        }
    }

    private fun drawPlayer() {
        val cx = drawPx * TILE + TILE / 2f
        val cy = drawPy * TILE + TILE / 2f
        shapes.setColor(0f, 0f, 0f, 0.35f)
        shapes.ellipse(cx - 10f, cy - 13f, 20f, 7f)
        shapes.setColor(0.72f, 0.26f, 0.2f, 1f)
        shapes.circle(cx, cy - 2f, 10f, 16)
        shapes.setColor(0.95f, 0.75f, 0.2f, 1f)
        shapes.rect(cx - 5f, cy + 2f, 10f, 3f)
        shapes.setColor(0.92f, 0.74f, 0.62f, 1f)
        shapes.circle(cx, cy + 6f, 5.5f, 12)
        shapes.setColor(0.14f, 0.11f, 0.1f, 1f)
        shapes.rect(cx - 9f, cy + 9f, 18f, 2.5f)
        shapes.rect(cx - 4.5f, cy + 10f, 9f, 5f)
        shapes.setColor(0.35f, 0.85f, 1f, 1f) // the stun baton
        shapes.rectLine(cx + run.facingX * 8f, cy - 2f + run.facingY * 8f, cx + run.facingX * 17f, cy - 2f + run.facingY * 17f, 2.5f)
    }

    private fun drawWorldText() {
        val lv = run.level
        for (m in lv.monsters) {
            if (!lv.visible[lv.index(m.x, m.y)]) continue
            val cx = m.drawX * TILE + TILE / 2f
            val cy = m.drawY * TILE + TILE / 2f
            if (m.state == AiState.SLEEPING) {
                worldFont.setColor(0.8f, 0.9f, 1f, 0.8f)
                worldFont.draw(batch, "z", cx + 6f, cy + 22f)
            } else if (m.state == AiState.HUNTING) {
                worldFont.setColor(1f, 0.3f, 0.25f, 1f)
                worldFont.draw(batch, "!", cx + 6f, cy + 24f)
            }
        }
        for (f in floats) {
            val c = when (f.kind) {
                FloatText.HIT_MONSTER -> floatArrayOf(1f, 0.9f, 0.4f)
                FloatText.HIT_PLAYER -> floatArrayOf(1f, 0.3f, 0.3f)
                FloatText.HEAL -> floatArrayOf(0.4f, 1f, 0.5f)
                else -> floatArrayOf(0.9f, 0.9f, 1f)
            }
            worldFont.setColor(c[0], c[1], c[2], 1f - f.age / 0.9f)
            glyphs.setText(worldFont, f.text)
            worldFont.draw(batch, f.text, f.x - glyphs.width / 2f, f.y + f.age * 24f)
        }
    }

    private fun drawHudShapes(w: Float, h: Float) {
        val barH = font.lineHeight
        val gap = barH * 0.25f
        drawMindVignette(w, h)
        StatsHud.drawShapes(
            shapes, state.stats, h * 0.03f, h * 0.03f + barH * 3f, barH * 9f, barH, gap,
            showAir = false, showShip = false, time = time, showMind = true,
        )
        shapes.setColor(1f, 0.75f, 0.3f, if (run.lanternLit) 0.28f else 0.08f)
        shapes.circle(lightCenter.x, lightCenter.y, waitRadius, 28)

        // Item slots
        for (i in slotRects.indices) {
            val r = slotRects[i]
            shapes.setColor(0.03f, 0.05f, 0.09f, 0.75f)
            shapes.rect(r[0], r[1], r[2], r[3])
            val item = run.pack.getOrNull(i)
            if (item != null) {
                val c = itemColor(item)
                shapes.setColor(c[0], c[1], c[2], 1f)
                shapes.circle(r[0] + r[2] / 2f, r[1] + r[3] / 2f, r[2] * 0.25f, 14)
            }
        }
        shapes.setColor(0.4f, 0.8f, 1f, if (waitCenter.x > 0) 0.18f else 0f)
        shapes.circle(waitCenter.x, waitCenter.y, waitRadius, 28)
        val stairs = run.stairsHere()
        if (stairs != null) {
            shapes.setColor(0.5f, 1f, 0.6f, 0.3f)
            shapes.circle(stairsCenter.x, stairsCenter.y, stairsRadius, 32)
        }

        shapes.set(ShapeType.Line)
        for (r in slotRects) {
            shapes.setColor(1f, 1f, 1f, 0.3f)
            shapes.rect(r[0], r[1], r[2], r[3])
        }
        shapes.setColor(0.4f, 0.8f, 1f, 0.6f)
        shapes.circle(waitCenter.x, waitCenter.y, waitRadius, 28)
        if (stairs != null) {
            shapes.setColor(0.5f, 1f, 0.6f, 0.8f)
            shapes.circle(stairsCenter.x, stairsCenter.y, stairsRadius, 32)
        }
        shapes.set(ShapeType.Filled)
    }

    private fun drawHudText(w: Float, h: Float) {
        val lh = font.lineHeight
        val margin = h * 0.03f
        val stats = state.stats

        val barH = font.lineHeight
        val gap = barH * 0.25f
        val barX = margin
        val barY = margin + barH * 3f
        StatsHud.drawLabels(batch, font, stats, barX, barY, barH, gap, showAir = false, showShip = false, showMind = true)
        font.setColor(1f, 0.9f, 0.5f, 1f)
        val stackTop = barY + StatsHud.stackHeight(barH, gap, showAir = false, showShip = false, showMind = true)
        font.draw(batch, "CREDITS ${stats.credits}    BATON +${run.weaponBonus}   ARMOR ${run.armor}", barX, stackTop + barH * 0.4f)
        val lantern = when {
            run.lanternLit -> "LANTERN  oil ${run.oil.toInt()}"
            run.oil <= 0f -> "LANTERN  empty"
            else -> "LANTERN  off  (oil ${run.oil.toInt()})"
        }
        font.setColor(1f, 0.78f, 0.45f, 1f)
        font.draw(batch, lantern, barX, stackTop + barH * 1.5f)
        mindState()?.let {
            font.setColor(0.8f, 0.6f, 1f, 1f)
            font.draw(batch, it, barX, stackTop + barH * 2.6f)
        }

        font.setColor(0.9f, 0.95f, 1f, 1f)
        font.draw(batch, "${planet.name} ruins  -  floor ${run.depth} of ${run.maxDepth}", margin, h - margin)
        font.setColor(0.75f, 0.9f, 1f, 0.9f)
        val goal = when {
            run.completed -> "Bounty complete. Head back up to the surface."
            run.contract != null -> "Bounty: bring in ${run.contract.target} on floor ${run.maxDepth}."
            else -> "No bounty here. Explore and loot."
        }
        font.draw(batch, goal, margin, h - margin - lh)

        // Item slot numbers
        font.setColor(1f, 1f, 1f, 0.9f)
        for (i in slotRects.indices) {
            val r = slotRects[i]
            val label = run.pack.getOrNull(i)?.label?.take(3) ?: ""
            font.draw(batch, "${i + 1}", r[0] + 3f, r[1] + r[3] - 2f)
            if (label.isNotEmpty()) font.draw(batch, label, r[0] + 3f, r[1] + lh)
        }
        glyphs.setText(font, "WAIT")
        font.draw(batch, "WAIT", waitCenter.x - glyphs.width / 2f, waitCenter.y + glyphs.height / 2f)
        font.setColor(1f, 0.85f, 0.6f, 1f)
        glyphs.setText(font, "LIGHT")
        font.draw(batch, "LIGHT", lightCenter.x - glyphs.width / 2f, lightCenter.y + glyphs.height / 2f)

        val stairs = run.stairsHere()
        if (stairs != null) {
            val label = when {
                stairs == Tile.STAIRS_DOWN -> "DESCEND"
                run.depth == 1 -> "LEAVE"
                else -> "CLIMB"
            }
            font.setColor(1f, 1f, 1f, 0.95f)
            glyphs.setText(font, label)
            font.draw(batch, label, stairsCenter.x - glyphs.width / 2f, stairsCenter.y + glyphs.height / 2f)

            val prompt = when {
                stairs == Tile.STAIRS_DOWN -> "E / DESCEND: go down to floor ${run.depth + 1}"
                run.depth == 1 -> "E / LEAVE: climb out of the ruins"
                else -> "E / CLIMB: go up to floor ${run.depth - 1}"
            }
            font.setColor(1f, 0.9f, 0.5f, 1f)
            glyphs.setText(font, prompt)
            font.draw(batch, prompt, (w - glyphs.width) / 2f, margin + lh * 6.4f)
        }

        // Message log
        val shown = run.messages.takeLast(4)
        for ((i, line) in shown.withIndex()) {
            val age = shown.size - 1 - i
            font.setColor(1f, 1f, 1f, 1f - age * 0.22f)
            glyphs.setText(font, line)
            font.draw(batch, line, (w - glyphs.width) / 2f, margin + lh * (1.6f + age))
        }

        val t = toast
        if (t != null && toastTimer > 0f) {
            font.setColor(1f, 0.9f, 0.5f, min(1f, toastTimer))
            glyphs.setText(font, t)
            font.draw(batch, t, (w - glyphs.width) / 2f, h - margin - lh * 3f)
        }

        if (time < 12f) {
            font.setColor(0.8f, 0.9f, 1f, min(1f, (12f - time) / 2f))
            val hint = "WASD / arrows: move and attack    Space: wait    L: lantern    1-6: use item    E: stairs"
            glyphs.setText(font, hint)
            font.draw(batch, hint, (w - glyphs.width) / 2f, h - margin - lh * 2f)
        }
    }

    private fun mindState(): String? {
        val mind = state.stats.mind
        return when {
            mind < DungeonRun.PANIC_MIND -> "PANICKING - your aim is shaking"
            mind < DungeonRun.HALLUCINATE_MIND -> "Seeing things that are not there"
            mind < DungeonRun.WHISPER_MIND -> "Uneasy"
            else -> null
        }
    }

    /** As the mind frays, the edges of the screen darken and bruise purple. */
    private fun drawMindVignette(w: Float, h: Float) {
        val fray = ((DungeonRun.WHISPER_MIND - state.stats.mind) / DungeonRun.WHISPER_MIND).coerceIn(0f, 1f)
        if (fray <= 0f) return
        val pulse = 0.85f + 0.15f * sin(time * 2.2f)
        val a = 0.55f * fray * pulse
        val edge = Color(0.12f, 0f, 0.15f, a)
        val clear = Color(0.12f, 0f, 0.15f, 0f)
        val band = min(w, h) * (0.12f + 0.18f * fray)
        shapes.rect(0f, 0f, band, h, edge, clear, clear, edge)
        shapes.rect(w - band, 0f, band, h, clear, edge, edge, clear)
        shapes.rect(0f, 0f, w, band, edge, edge, clear, clear)
        shapes.rect(0f, h - band, w, band, clear, clear, edge, edge)
    }

    override fun dispose() {
        shapes.dispose()
        batch.dispose()
        font.dispose()
        worldFont.dispose()
    }

    private companion object {
        const val TILE = 32f
        const val MOVE_DELAY = 0.13f
        const val TRAVEL_DELAY = 0.09f
        const val MIND_SHAKE = 30f
        const val STRIKE_TIME = 0.45f
    }
}
