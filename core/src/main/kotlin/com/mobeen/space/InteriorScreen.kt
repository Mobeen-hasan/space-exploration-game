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
import com.badlogic.gdx.utils.viewport.ExtendViewport
import com.badlogic.gdx.utils.viewport.ScreenViewport
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The inside of the player's ship: a single walkable room with a bunk (sleep and save), a galley
 * (eat), a terminal (refuel, repair, bounties), a cockpit (back to flight) and an airlock.
 * The world is paused while you are in here, except for hunger and tiredness.
 */
class InteriorScreen(
    private val game: SpaceGame,
    private val state: GameState,
    spawnX: Float,
) : ScreenAdapter() {
    private enum class Station(val x: Float, val title: String) {
        AIRLOCK(60f, "AIRLOCK"),
        BUNK(215f, "BUNK"),
        GALLEY(370f, "GALLEY"),
        TERMINAL(525f, "TERMINAL"),
        COCKPIT(690f, "COCKPIT"),
    }

    private enum class MenuPage { MAIN, BOUNTIES }

    private class Option(val label: String, val enabled: Boolean, val detail: String? = null, val run: () -> Unit)

    private val camera = OrthographicCamera()
    private val viewport = ExtendViewport(820f, 460f, camera)
    private val hudCamera = OrthographicCamera()
    private val hudViewport = ScreenViewport(hudCamera)

    private val shapes = ShapeRenderer().apply { setAutoShapeType(true) }
    private val batch = SpriteBatch()
    private val font = BitmapFont().apply { region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear) }
    private val labelFont = BitmapFont().apply {
        region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        data.setScale(0.6f)
    }
    private val glyphs = GlyphLayout()
    private val controls = Controls()

    private var px = spawnX
    private var facing = 1
    private var walkTime = 0f
    private var time = 0f

    private var toast: String? = null
    private var toastTimer = 0f

    private var menuOpen = false
    private var menuSel = 0
    private var menuPage = MenuPage.MAIN

    private var sleepTimer = -1f
    private var sleepApplied = false
    private val sleeping get() = sleepTimer >= 0f

    private val white = Color(1f, 1f, 1f, 1f)

    init {
        camera.position.set(390f, 100f, 0f)
        state.stats.refillAir()
    }

    override fun show() {
        state.pendingNotice?.let {
            showToast(it, 5f)
            state.pendingNotice = null
        }
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height)
        camera.position.set(390f, 100f, 0f)
        hudViewport.update(width, height, true)
    }

    private fun showToast(text: String, seconds: Float = 2.5f) {
        toast = text
        toastTimer = seconds
    }

    // ------------------------------------------------------------------ logic

    private fun nearestStation(): Station? =
        Station.entries.minByOrNull { abs(it.x - px) }?.takeIf { abs(it.x - px) < STATION_RANGE }

    private fun buttonLabel(s: Station): String? = when (s) {
        Station.AIRLOCK -> if (state.ship.landedOn != null) "EXIT" else null
        Station.BUNK -> "SLEEP"
        Station.GALLEY -> "EAT"
        Station.TERMINAL -> "USE"
        Station.COCKPIT -> "LAUNCH"
    }

    private fun promptFor(s: Station): String = when (s) {
        Station.AIRLOCK -> state.ship.landedOn?.let { "E: step outside onto ${it.name}" }
            ?: "The airlock is sealed - you are in open space."
        Station.BUNK -> "E: sleep and save the game"
        Station.GALLEY -> "E: eat a ration (${Stats.RATION_COST} credits)"
        Station.TERMINAL -> "E: use the ship terminal"
        Station.COCKPIT -> "E: take the helm and return to flight"
    }

    override fun render(delta: Float) {
        val dt = min(delta, 0.05f)
        time += dt
        if (toastTimer > 0f) toastTimer -= dt

        val w = Gdx.graphics.width.toFloat()
        val h = Gdx.graphics.height.toFloat()
        val stats = state.stats

        updateSleep(dt)
        if (!sleeping) {
            state.clock += dt
            stats.tickBody(dt)
            if (stats.collapsed) {
                game.collapse()
                return
            }
        }

        val station = nearestStation()
        controls.showThrust = false
        controls.showBrake = false
        controls.thrustLabel = ""
        val label = station?.let { buttonLabel(it) }
        controls.actionEnabled = label != null && !menuOpen && !sleeping
        controls.actionLabel = label ?: ""
        controls.update(w, h)

        if (menuOpen) {
            handleMenuInput(w, h)
        } else if (!sleeping) {
            walk(dt)
            if (controls.actionPressed && station != null) useStation(station)
        }

        draw(w, h, station)
    }

    private fun walk(dt: Float) {
        val m = controls.moveX
        px = (px + m * WALK_SPEED * dt).coerceIn(30f, 770f)
        if (m > 0.1f) facing = 1 else if (m < -0.1f) facing = -1
        if (abs(m) > 0.1f) walkTime += dt * abs(m)
    }

    private fun useStation(s: Station) {
        val stats = state.stats
        when (s) {
            Station.AIRLOCK -> state.ship.landedOn?.let { game.enterSurface(it) }
            Station.BUNK -> {
                sleepTimer = 0f
                sleepApplied = false
            }
            Station.GALLEY -> when {
                stats.food >= Stats.MAX -> showToast("You are not hungry.")
                stats.credits < Stats.RATION_COST -> showToast("Not enough credits for a ration.")
                else -> {
                    stats.eat()
                    showToast("You ate a ration. (-${Stats.RATION_COST} credits)")
                }
            }
            Station.TERMINAL -> {
                menuOpen = true
                menuSel = 0
                menuPage = MenuPage.MAIN
            }
            Station.COCKPIT -> game.returnToSpace()
        }
    }

    private fun updateSleep(dt: Float) {
        if (!sleeping) return
        sleepTimer += dt
        if (!sleepApplied && sleepTimer >= 1.5f) {
            state.stats.sleep()
            state.clock += SLEEP_CLOCK_JUMP
            SaveStore.save(state)
            sleepApplied = true
            showToast("Day ${state.stats.day} begins. Game saved.", 4f)
        }
        if (sleepTimer >= 3f) sleepTimer = -1f
    }

    private fun closeMenu() {
        menuOpen = false
        menuPage = MenuPage.MAIN
    }

    private fun mainOptions(): List<Option> {
        val st = state.stats
        val active = state.bounties.active
        return listOf(
            Option("Refuel +25%   (${Stats.FUEL_COST} credits)", st.credits >= Stats.FUEL_COST && st.fuel < Stats.MAX) {
                if (st.refuel()) showToast("Refuelled. (-${Stats.FUEL_COST} credits)")
            },
            Option("Repair hull +25%   (${Stats.REPAIR_COST} credits)", st.credits >= Stats.REPAIR_COST && st.hull < Stats.MAX) {
                if (st.repair()) showToast("Hull repaired. (-${Stats.REPAIR_COST} credits)")
            },
            Option("Emergency fuel   (free, only when almost empty)", st.fuel < Stats.EMERGENCY_THRESHOLD) {
                if (st.emergencyFuel()) showToast("Reserve tank released.")
            },
            Option(
                if (active != null) "Bounty board   (contract active)" else "Bounty board   (${state.bounties.available.size} listed)",
                true,
            ) {
                state.bounties.refreshIfNeeded(state)
                menuPage = MenuPage.BOUNTIES
                menuSel = 0
            },
            Option("Close", true) { closeMenu() },
        )
    }

    /** Two lines of facts about a contract: the crime, then the world and how dangerous the trip is. */
    private fun contractDetail(c: Contract): String {
        val planet = SaveCodec.planetFromId(c.planetId)
        val world = if (planet != null) "${c.planetType.label} world, ${planet.temperatureC} C" else "${c.planetType.label} world"
        return "Wanted for: ${c.crime}.\n$world, ${"%.1f".format(c.distance / 1000f)}k away.  " +
            "Danger: ${c.danger} (${BountyGenerator.dangerNote(c.planetType)})"
    }

    private fun bountyOptions(): List<Option> {
        val board = state.bounties
        val active = board.active
        val list = ArrayList<Option>()
        if (active != null) {
            list.add(Option("ACTIVE: ${active.summary()}", false, contractDetail(active)) {})
            list.add(Option("Abandon this contract", true, "You will not get it back, and the reward is lost.") {
                board.abandon()
                showToast("Contract abandoned.")
                menuSel = 0
            })
        } else if (board.available.isEmpty()) {
            list.add(Option("No contracts in range. Fly somewhere with planets nearby.", false) {})
        } else {
            for (c in board.available.toList()) {
                list.add(Option(c.summary(), true, contractDetail(c)) {
                    if (board.accept(c)) {
                        showToast("Contract accepted: bring in ${c.target}.", 4f)
                        menuSel = 0
                    }
                })
            }
        }
        list.add(Option("Back", true) {
            menuPage = MenuPage.MAIN
            menuSel = 0
        })
        return list
    }

    private fun options(): List<Option> = if (menuPage == MenuPage.MAIN) mainOptions() else bountyOptions()

    private fun handleMenuInput(w: Float, h: Float) {
        val options = options()
        if (menuSel >= options.size) menuSel = options.size - 1
        val input = Gdx.input
        if (input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            if (menuPage == MenuPage.BOUNTIES) {
                menuPage = MenuPage.MAIN
                menuSel = 0
            } else {
                closeMenu()
            }
            return
        }
        if (input.isKeyJustPressed(Input.Keys.UP) || input.isKeyJustPressed(Input.Keys.W)) menuSel = (menuSel + options.size - 1) % options.size
        if (input.isKeyJustPressed(Input.Keys.DOWN) || input.isKeyJustPressed(Input.Keys.S)) menuSel = (menuSel + 1) % options.size
        if (input.isKeyJustPressed(Input.Keys.ENTER) || input.isKeyJustPressed(Input.Keys.E)) {
            options[menuSel].takeIf { it.enabled }?.run?.invoke()
            return
        }
        if (input.justTouched()) {
            val tx = input.x.toFloat()
            val ty = h - input.y.toFloat()
            for (i in options.indices) {
                val r = menuRow(w, h, i, options.size)
                if (tx >= r[0] && tx <= r[0] + r[2] && ty >= r[1] && ty <= r[1] + r[3]) {
                    menuSel = i
                    options[i].takeIf { it.enabled }?.run?.invoke()
                    return
                }
            }
        }
    }

    /** The menu panel as (x, y, width, height), sized for [count] rows. */
    private fun menuPanel(w: Float, h: Float, count: Int): FloatArray {
        val lh = font.lineHeight
        val rh = lh * 1.9f
        val mw = min(w * 0.94f, lh * 34f)
        val ph = rh * count + lh * 6.6f
        return floatArrayOf((w - mw) / 2f, h / 2f - ph / 2f, mw, ph)
    }

    /** Screen rectangle (x, y, width, height) of menu row [i]. */
    private fun menuRow(w: Float, h: Float, i: Int, count: Int): FloatArray {
        val lh = font.lineHeight
        val rh = lh * 1.9f
        val p = menuPanel(w, h, count)
        val top = p[1] + p[3] - lh * 2.2f
        return floatArrayOf(p[0] + lh, top - (i + 1) * rh + rh * 0.05f, p[2] - lh * 2f, rh * 0.9f)
    }

    // ------------------------------------------------------------------ drawing

    private fun draw(w: Float, h: Float, station: Station?) {
        Gdx.gl.glClearColor(0.02f, 0.025f, 0.04f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)

        viewport.apply()
        camera.update()
        shapes.projectionMatrix = camera.combined
        shapes.begin(ShapeType.Filled)
        drawRoom()
        drawStations(station)
        CharacterArt.draw(shapes, px, 0f, facing, walkTime, true, white)
        shapes.end()

        batch.projectionMatrix = camera.combined
        batch.begin()
        labelFont.setColor(0.75f, 0.85f, 1f, 0.9f)
        for (s in Station.entries) {
            glyphs.setText(labelFont, s.title)
            labelFont.draw(batch, s.title, s.x - glyphs.width / 2f, 192f)
        }
        batch.end()

        hudViewport.apply()
        shapes.projectionMatrix = hudCamera.combined
        batch.projectionMatrix = hudCamera.combined
        font.data.setScale(max(0.8f, h / 720f * 1.05f))

        val barH = font.lineHeight
        val gap = barH * 0.25f
        val barX = h * 0.03f
        val barY = h * 0.03f + barH * 3f
        shapes.begin(ShapeType.Filled)
        TouchHud.drawShapes(shapes, controls)
        StatsHud.drawShapes(shapes, state.stats, barX, barY, barH * 9f, barH, gap, showAir = false, showShip = true, time = time, showMind = true)
        if (menuOpen) drawMenuBackground(w, h)
        if (sleeping) {
            shapes.setColor(0f, 0f, 0f, sleepAlpha())
            shapes.rect(0f, 0f, w, h)
        }
        shapes.end()

        batch.begin()
        drawHudText(w, h, station, barX, barY, barH, gap)
        batch.end()
    }

    private fun sleepAlpha(): Float = when {
        sleepTimer < 1.2f -> sleepTimer / 1.2f
        sleepTimer < 1.8f -> 1f
        else -> max(0f, 1f - (sleepTimer - 1.8f) / 1.2f)
    }

    private fun drawHudText(w: Float, h: Float, station: Station?, barX: Float, barY: Float, barH: Float, gap: Float) {
        val lh = font.lineHeight
        val margin = h * 0.03f

        StatsHud.drawLabels(batch, font, state.stats, barX, barY, barH, gap, showAir = false, showShip = true, showMind = true)
        TouchHud.drawLabels(batch, font, glyphs, controls)
        font.setColor(1f, 0.9f, 0.5f, 1f)
        font.draw(
            batch, "CREDITS ${state.stats.credits}    DAY ${state.stats.day}",
            barX, barY + StatsHud.stackHeight(barH, gap, showAir = false, showShip = true, showMind = true) + barH * 0.4f,
        )
        font.setColor(0.8f, 0.9f, 1f, 0.9f)
        font.draw(batch, "THE SHIP", margin, h - margin)
        font.draw(batch, "A / D: walk    E: use    Touch: left stick walks, green button uses", margin, h - margin - lh)
        state.bounties.active?.let {
            font.setColor(1f, 0.82f, 0.3f, 1f)
            font.draw(batch, "BOUNTY: ${it.target} on ${it.planetName} (${it.reward} cr)", margin, h - margin - lh * 2f)
        }

        if (!menuOpen && !sleeping && station != null) {
            val t = promptFor(station)
            glyphs.setText(font, t)
            font.setColor(1f, 0.9f, 0.5f, 1f)
            font.draw(batch, t, (w - glyphs.width) / 2f, margin + lh * 3.2f)
        }

        if (menuOpen) drawMenuText(w, h)

        val t = toast
        if (t != null && toastTimer > 0f && !sleeping) {
            font.setColor(1f, 0.9f, 0.5f, min(1f, toastTimer))
            glyphs.setText(font, t)
            font.draw(batch, t, (w - glyphs.width) / 2f, h - margin - lh * 3f)
        }
        if (sleeping && sleepTimer in 1.5f..2.4f) {
            font.setColor(0.8f, 0.9f, 1f, 1f)
            val z = "Zzz..."
            glyphs.setText(font, z)
            font.draw(batch, z, (w - glyphs.width) / 2f, h / 2f)
        }
    }

    private fun drawMenuBackground(w: Float, h: Float) {
        val options = options()
        val p = menuPanel(w, h, options.size)
        shapes.setColor(0f, 0f, 0f, 0.55f)
        shapes.rect(0f, 0f, w, h)
        shapes.setColor(0.04f, 0.07f, 0.10f, 0.95f)
        shapes.rect(p[0], p[1], p[2], p[3])
        for (i in options.indices) {
            val r = menuRow(w, h, i, options.size)
            val selected = i == menuSel
            shapes.setColor(0.2f, 0.7f, 0.4f, if (selected) 0.35f else 0.1f)
            shapes.rect(r[0], r[1], r[2], r[3])
        }
        shapes.set(ShapeType.Line)
        shapes.setColor(0.4f, 0.9f, 0.6f, 0.7f)
        shapes.rect(p[0], p[1], p[2], p[3])
        shapes.set(ShapeType.Filled)
    }

    private fun drawMenuText(w: Float, h: Float) {
        val lh = font.lineHeight
        val options = options()
        val p = menuPanel(w, h, options.size)
        font.setColor(0.5f, 1f, 0.7f, 1f)
        val title = if (menuPage == MenuPage.MAIN) "SHIP TERMINAL" else "BOUNTY BOARD"
        font.draw(batch, "$title      Credits: ${state.stats.credits}", p[0] + lh, p[1] + p[3] - lh * 0.8f)
        for ((i, o) in options.withIndex()) {
            val r = menuRow(w, h, i, options.size)
            if (o.enabled) font.setColor(0.9f, 1f, 0.95f, 1f) else font.setColor(0.5f, 0.55f, 0.55f, 1f)
            font.draw(batch, o.label, r[0] + lh * 0.6f, r[1] + (r[3] + font.capHeight) / 2f)
        }
        options.getOrNull(menuSel)?.detail?.let {
            font.setColor(1f, 0.9f, 0.55f, 1f)
            font.draw(batch, it, p[0] + lh, p[1] + lh * 3.7f)
        }
        font.setColor(0.6f, 0.8f, 0.7f, 0.9f)
        font.draw(batch, "W/S or arrows: choose    E / Enter: select    Esc: back", p[0] + lh, p[1] + lh * 1.2f)
    }

    private fun outsideColor(out: Color): Color {
        val planet = state.ship.landedOn
        if (planet != null) {
            out.set(planet.atmoColor.r * 0.55f, planet.atmoColor.g * 0.55f, planet.atmoColor.b * 0.55f, 1f)
        } else {
            out.set(0.01f, 0.015f, 0.03f, 1f)
        }
        return out
    }

    private val outside = Color()

    private fun porthole(cx: Float, cy: Float, r: Float) {
        shapes.setColor(0.07f, 0.08f, 0.11f, 1f)
        shapes.circle(cx, cy, r + 5f, 24)
        val c = outsideColor(outside)
        shapes.setColor(c.r, c.g, c.b, 1f)
        shapes.circle(cx, cy, r, 24)
        if (state.ship.landedOn == null) {
            shapes.setColor(1f, 1f, 1f, 0.8f)
            shapes.circle(cx - r * 0.4f, cy + r * 0.3f, 1.2f, 6)
            shapes.circle(cx + r * 0.2f, cy - r * 0.2f, 1f, 6)
            shapes.circle(cx + r * 0.5f, cy + r * 0.5f, 1.4f, 6)
        }
    }

    private fun drawRoom() {
        shapes.setColor(0.03f, 0.04f, 0.06f, 1f)
        shapes.rect(-300f, -300f, 1400f, 700f)

        val bottom = Color(0.16f, 0.19f, 0.25f, 1f)
        val top = Color(0.08f, 0.10f, 0.15f, 1f)
        shapes.rect(0f, 0f, 780f, 200f, bottom, bottom, top, top)
        shapes.setColor(1f, 1f, 1f, 0.04f)
        var x = 0f
        while (x <= 780f) {
            shapes.rect(x, 0f, 2f, 200f)
            x += 60f
        }
        shapes.setColor(0.20f, 0.22f, 0.28f, 1f)
        shapes.rect(0f, -24f, 780f, 24f)
        shapes.setColor(0.36f, 0.40f, 0.48f, 1f)
        shapes.rect(0f, -3f, 780f, 3f)
        shapes.setColor(0.06f, 0.07f, 0.10f, 1f)
        shapes.rect(0f, 200f, 780f, 20f)

        porthole(300f, 140f, 26f)
        porthole(450f, 140f, 26f)
    }

    private fun drawStations(near: Station?) {
        // Airlock
        shapes.setColor(0.24f, 0.26f, 0.32f, 1f)
        shapes.rect(30f, 0f, 60f, 104f)
        shapes.setColor(0.15f, 0.16f, 0.20f, 1f)
        shapes.rect(36f, 6f, 48f, 92f)
        var sx = 30f
        var dark = false
        while (sx < 90f) {
            if (dark) shapes.setColor(0.1f, 0.1f, 0.1f, 1f) else shapes.setColor(0.95f, 0.78f, 0.15f, 1f)
            shapes.rect(sx, 104f, 10f, 8f)
            dark = !dark
            sx += 10f
        }
        val landed = state.ship.landedOn != null
        if (landed) shapes.setColor(0.3f, 1f, 0.45f, 1f) else shapes.setColor(1f, 0.3f, 0.25f, 1f)
        shapes.circle(60f, 124f, 5f, 12)

        // Bunk
        shapes.setColor(0.18f, 0.2f, 0.26f, 1f)
        shapes.rect(160f, 12f, 110f, 10f)
        shapes.rect(160f, 0f, 6f, 22f)
        shapes.rect(264f, 0f, 6f, 22f)
        shapes.setColor(0.86f, 0.87f, 0.92f, 1f)
        shapes.rect(164f, 22f, 102f, 12f)
        shapes.setColor(0.55f, 0.2f, 0.22f, 1f)
        shapes.rect(190f, 22f, 76f, 15f)
        shapes.setColor(0.95f, 0.95f, 1f, 1f)
        shapes.rect(166f, 34f, 26f, 7f)

        // Galley
        shapes.setColor(0.30f, 0.32f, 0.38f, 1f)
        shapes.rect(320f, 0f, 100f, 34f)
        shapes.setColor(0.62f, 0.64f, 0.70f, 1f)
        shapes.rect(320f, 34f, 100f, 4f)
        shapes.setColor(0.70f, 0.70f, 0.76f, 1f)
        shapes.rect(345f, 38f, 22f, 14f)
        shapes.setColor(0.22f, 0.24f, 0.30f, 1f)
        shapes.rect(325f, 100f, 90f, 36f)
        shapes.setColor(1f, 1f, 1f, 0.12f + 0.08f * sin(time * 3f))
        shapes.circle(356f + sin(time * 2f) * 3f, 62f + (time * 12f) % 14f, 3f, 8)

        // Terminal
        shapes.setColor(0.24f, 0.26f, 0.32f, 1f)
        shapes.rect(485f, 0f, 80f, 28f)
        shapes.setColor(0.07f, 0.08f, 0.10f, 1f)
        shapes.rect(493f, 38f, 64f, 48f)
        shapes.setColor(0.08f, 0.32f, 0.18f, 1f)
        shapes.rect(497f, 42f, 56f, 40f)
        shapes.setColor(0.35f, 0.95f, 0.55f, 0.28f)
        for (i in 0 until 6) shapes.rect(497f, 44f + i * 6.5f, 56f, 2f)
        if ((time * 2f).toInt() % 2 == 0) {
            shapes.setColor(0.6f, 1f, 0.75f, 0.9f)
            shapes.rect(502f, 48f, 6f, 8f)
        }

        // Cockpit
        shapes.setColor(0.07f, 0.08f, 0.11f, 1f)
        shapes.rect(636f, 96f, 108f, 68f)
        val c = outsideColor(outside)
        shapes.setColor(c.r, c.g, c.b, 1f)
        shapes.rect(641f, 101f, 98f, 58f)
        shapes.setColor(0.28f, 0.1f, 0.1f, 1f)
        shapes.rect(662f, 0f, 30f, 28f)
        shapes.rect(662f, 28f, 9f, 42f)
        shapes.setColor(0.22f, 0.24f, 0.30f, 1f)
        shapes.rect(705f, 0f, 60f, 26f)
        val blink = (time * 1.5f).toInt() % 2 == 0
        shapes.setColor(0.3f, 1f, 0.45f, 1f)
        shapes.circle(715f, 20f, 3f, 8)
        if (blink) shapes.setColor(1f, 0.75f, 0.2f, 1f) else shapes.setColor(0.35f, 0.25f, 0.08f, 1f)
        shapes.circle(728f, 20f, 3f, 8)
        shapes.setColor(1f, 0.3f, 0.25f, if (state.stats.fuel < 20f && blink) 1f else 0.3f)
        shapes.circle(741f, 20f, 3f, 8)

        // Marker above the station you can use
        if (near != null && buttonLabel(near) != null && !menuOpen) {
            val bounce = sin(time * 5f) * 3f
            shapes.setColor(1f, 0.9f, 0.5f, 1f)
            shapes.triangle(near.x - 6f, 168f + bounce, near.x + 6f, 168f + bounce, near.x, 158f + bounce)
        }
    }

    override fun dispose() {
        shapes.dispose()
        batch.dispose()
        font.dispose()
        labelFont.dispose()
    }

    companion object {
        const val AIRLOCK_SPAWN = 110f
        const val BUNK_SPAWN = 215f
        const val COCKPIT_SPAWN = 650f
        private const val WALK_SPEED = 190f
        private const val STATION_RANGE = 42f
        private const val SLEEP_CLOCK_JUMP = 90f
    }
}
