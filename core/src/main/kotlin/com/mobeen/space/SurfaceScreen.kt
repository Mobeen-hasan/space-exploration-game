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
    private val spawnX: Float = 55f,
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
    private var toast: String? = null
    private var toastTimer = 0f
    private var hurtFlash = 0f
    private var nearEntrance = false
    private val signals = Discoveries.signalsFor(planet)
    private val town = Towns.townFor(planet)
    private val townMenu = OverlayMenu("TOWN")
    private var openBuilding: Building? = null
    private var talkingTo: Townsperson? = null
    private val talkLines = HashMap<Townsperson, Int>()
    private var townNote: String? = null
    private val worldFont = BitmapFont().apply {
        region.texture.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear)
        data.setScale(0.75f)
    }
    private var scanFxTimer = 0f
    private var scanFxX = 0f
    private var lastHealth = state.stats.health

    init {
        player.spawn(terrain, spawnX)
        camX = player.x
        camY = player.y + CAMERA_RISE
        camera.zoom = ZOOM
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

    override fun render(delta: Float) {
        val dt = min(delta, 0.05f)
        time += dt
        if (toastTimer > 0f) toastTimer -= dt

        val dw = Gdx.graphics.width.toFloat()
        val dh = Gdx.graphics.height.toFloat()
        val inTown = openBuilding != null || talkingTo != null
        val paused = game.devMenu.frame(game.devMode, dw, dh) { devOptions() } ||
            game.journal.frame(dw, dh, state, blocked = inTown) ||
            townFrame(dw, dh) ||
            game.pauseMenu.frame(dw, dh, blocked = false, exitLabel = PauseMenu.SAVE_AND_EXIT) { game.exitToTitle() }
        if (paused) { // the developer menu or the pause menu is open
            draw(dw, dh, abs(terrain.padOffset(player.x)) < BOARD_RANGE)
            if (openBuilding != null || talkingTo != null) townMenu.draw(shapes, batch, font, glyphs, hudCamera.combined, dw, dh, townRows())
            game.journal.draw(state, shapes, batch, font, glyphs, hudCamera.combined, dw, dh)
            game.pauseMenu.draw(shapes, batch, font, glyphs, hudCamera.combined, dw, dh)
            game.devMenu.draw(game.devMode, shapes, batch, font, glyphs, hudCamera.combined, dw, dh, state.stats.invulnerable)
            return
        }

        state.clock += dt
        dayPhase = (0.4f + state.clock / dayDuration) % 1f
        scenery.update(dayPhase)

        val stats = state.stats
        stats.tickBody(dt)
        val submerged = terrain.liquid != Liquid.NONE && player.y < terrain.liquidLevel - 2f
        stats.tickSurface(dt, planet, terrain.liquid, submerged)
        if (stats.health < lastHealth - 0.0001f) hurtFlash = min(0.45f, hurtFlash + (lastHealth - stats.health) * 0.12f)
        lastHealth = stats.health
        hurtFlash = max(0f, hurtFlash - dt * 0.8f)
        if (stats.collapsed) {
            game.collapse()
            return
        }

        val w = Gdx.graphics.width.toFloat()
        val h = Gdx.graphics.height.toFloat()

        val nearShip = abs(terrain.padOffset(player.x)) < BOARD_RANGE
        nearEntrance = abs(terrain.entranceOffset(player.x)) < ENTER_RANGE
        val nearSignal = signals.firstOrNull { it.key !in state.scanned && abs(Discoveries.loopOffset(it.x, player.x)) < Discoveries.SCAN_RANGE }
        val nearBuilding = buildingNear()
        val nearFolk = if (nearBuilding == null) folkNear() else null
        controls.thrustLabel = "JUMP"
        controls.showBrake = false
        controls.actionEnabled = nearShip || nearEntrance || nearBuilding != null || nearFolk != null || nearSignal != null
        controls.actionLabel = when {
            nearShip -> "BOARD"
            nearEntrance || nearBuilding != null -> "ENTER"
            nearFolk != null -> "TALK"
            else -> "SCAN"
        }
        controls.update(w, h)
        if (controls.actionPressed) {
            when {
                nearShip -> { game.enterInterior(InteriorScreen.AIRLOCK_SPAWN); return }
                nearEntrance -> { game.enterDungeon(planet); return }
                nearBuilding != null -> { openBuilding = nearBuilding; townNote = null }
                nearFolk != null -> talkingTo = nearFolk
                nearSignal != null -> scan(nearSignal)
            }
        }
        if (scanFxTimer > 0f) scanFxTimer -= dt

        val walkScale = if (stats.energy < 20f) 0.7f else 1f
        accumulator += dt
        while (accumulator >= STEP) {
            player.update(controls, terrain, STEP, walkScale)
            accumulator -= STEP
        }

        // Camera: follow the player, looking a little ahead in the facing direction
        val follow = 1f - exp(-5f * dt)
        camX += (player.x + player.facing * LOOK_AHEAD - camX) * follow
        camY += (player.y + CAMERA_RISE - camY) * (1f - exp(-4f * dt))
        camera.position.set(camX, camY, 0f)
        camera.update()

        draw(w, h, nearShip)
        game.journal.draw(state, shapes, batch, font, glyphs, hudCamera.combined, w, h)
        game.pauseMenu.draw(shapes, batch, font, glyphs, hudCamera.combined, w, h)
        game.devMenu.draw(game.devMode, shapes, batch, font, glyphs, hudCamera.combined, w, h, state.stats.invulnerable)
    }

    private fun devOptions(): List<DevOption> {
        val list = ArrayList(DevMenu.common(state))
        list.add(DevOption("Teleport to the ruins entrance") { devTeleport(player.x - terrain.entranceOffset(player.x) - 20f) })
        list.add(DevOption("Teleport to the ship") { devTeleport(player.x - terrain.padOffset(player.x) + 40f) })
        if (town != null) list.add(DevOption("Teleport to the town") { devTeleport(player.x - Towns.townOffset(player.x) - 60f) })
        return list
    }

    private fun devTeleport(x: Float) {
        player.spawn(terrain, x)
        camX = player.x
        camY = player.y + CAMERA_RISE
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
        for (s in signals) { // landmarks and smaller finds, drawn wherever they are near the camera
            val sx = camX + Discoveries.loopOffset(s.x, camX)
            if (abs(sx - camX) > halfW + 320f) continue
            val scanned = s.key in state.scanned
            if (s.landmark) scenery.drawLandmark(shapes, sx, terrain.height(sx), s.shape, scanned, time)
            else scenery.drawSignal(shapes, sx, terrain.height(sx), s.style, scanned, time)
        }

        town?.let { t ->
            val townX = camX - Towns.townOffset(camX)
            if (abs(townX - camX) < halfW + Towns.HALF_WIDTH + 100f) scenery.drawTown(shapes, townX, terrain.height(townX), t, time)
        }
        val shipX = Math.round(camX / Terrain.WIDTH) * Terrain.WIDTH
        scenery.drawShip(shapes, shipX, terrain.height(shipX), nearShip)
        val entranceX = Math.round((camX - Terrain.ENTRANCE_X) / Terrain.WIDTH) * Terrain.WIDTH + Terrain.ENTRANCE_X
        scenery.drawEntrance(shapes, entranceX, terrain.height(entranceX), nearEntrance, time)
        scenery.drawPlayer(shapes, player)
        scenery.drawLiquid(shapes, camX, halfW, time)
        shapes.end()

        additive(true)
        shapes.begin(ShapeType.Filled)
        scenery.drawLiquidGlow(shapes, camX, halfW, time)
        scenery.drawEntranceBeam(shapes, entranceX, terrain.height(entranceX), time, isBountyPlanet())
        scenery.drawPlayerGlow(shapes, player)
        if (scanFxTimer > 0f) { // a scanner sweep spreading out from what you just logged
            val t = 1f - scanFxTimer
            shapes.setColor(0.4f, 1f, 0.8f, 0.5f * scanFxTimer)
            shapes.circle(scanFxX, terrain.height(scanFxX) + 20f, 10f + t * 120f, 40)
        }
        ambient.draw(shapes, camX, camY, camera.zoom, time, scenery.daylight)
        shapes.end()

        // HUD
        hudViewport.apply()
        shapes.projectionMatrix = hudCamera.combined
        batch.projectionMatrix = hudCamera.combined
        font.data.setScale(max(0.8f, h / 720f * 1.05f))
        additive(false)
        shapes.begin(ShapeType.Filled)
        if (hurtFlash > 0.01f) {
            shapes.setColor(0.9f, 0.05f, 0.05f, hurtFlash)
            shapes.rect(0f, 0f, w, h)
        }
        TouchHud.drawShapes(shapes, controls)
        val barH = font.lineHeight
        val gap = barH * 0.25f
        val barX = h * 0.03f
        val barY = h * 0.03f + barH * 3f
        StatsHud.drawShapes(shapes, state.stats, barX, barY, barH * 9f, barH, gap, showAir = true, showShip = false, time = time)
        shapes.end()

        town?.let { t -> // building names and the town's name, in the world
            val townX = camX - Towns.townOffset(camX)
            if (abs(townX - camX) < halfW + Towns.HALF_WIDTH + 100f) {
                viewport.apply()
                batch.projectionMatrix = camera.combined
                batch.begin()
                val gy = terrain.height(townX)
                worldFont.setColor(1f, 0.95f, 0.85f, 1f)
                for (b in Building.entries) {
                    glyphs.setText(worldFont, b.label.uppercase())
                    worldFont.draw(batch, b.label.uppercase(), townX + b.offset - glyphs.width / 2f, gy + 152f)
                }
                batch.end()
                hudViewport.apply()
                batch.projectionMatrix = hudCamera.combined
            }
        }
        batch.begin()
        drawText(w, h, nearShip)
        StatsHud.drawLabels(batch, font, state.stats, barX, barY, barH, gap, showAir = true, showShip = false)
        font.setColor(1f, 0.9f, 0.5f, 1f)
        font.draw(
            batch, "CREDITS ${state.stats.credits}    DAY ${state.stats.day}",
            barX, barY + StatsHud.stackHeight(barH, gap, showAir = true, showShip = false) + barH * 0.4f,
        )
        StatsHud.drawInjuries(batch, font, state.stats, barX, barY + StatsHud.stackHeight(barH, gap, showAir = true, showShip = false) + barH * 1.5f)
        drawWarnings(w, h)
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
        state.bounties.hunting?.takeIf { it.planetId == planet.id }?.let {
            font.setColor(1f, 0.82f, 0.3f, 1f)
            font.draw(batch, "BOUNTY TARGET HERE: ${it.target} - follow the gold beam to the ruins", margin, h - margin - lh * 4f)
        }

        if (nearShip) {
            val t = "E / BOARD: return to your ship"
            glyphs.setText(font, t)
            font.setColor(1f, 0.9f, 0.5f, 1f)
            font.draw(batch, t, (w - glyphs.width) / 2f, margin + lh * 3f)
        }

        town?.let { t ->
            if (abs(Towns.townOffset(player.x)) < Towns.HALF_WIDTH + 400f) {
                val banner = "${t.name.uppercase()}  -  a settlement"
                glyphs.setText(font, banner)
                font.setColor(1f, 0.85f, 0.55f, 1f)
                font.draw(batch, banner, (w - glyphs.width) / 2f, h - margin)
            }
            val b = buildingNear()
            val f = if (b == null) folkNear() else null
            val prompt = when {
                b != null -> "E / ENTER: the ${b.label.lowercase()}"
                f != null -> "E / TALK: ${f.name}, ${f.role.lowercase()}"
                else -> null
            }
            if (prompt != null && !nearShip && !nearEntrance) {
                glyphs.setText(font, prompt)
                font.setColor(1f, 0.9f, 0.5f, 1f)
                font.draw(batch, prompt, (w - glyphs.width) / 2f, margin + lh * 3f)
            }
        }
        val signalHere = signals.firstOrNull { it.key !in state.scanned && abs(Discoveries.loopOffset(it.x, player.x)) < Discoveries.SCAN_RANGE }
        if (signalHere != null && !nearShip && !nearEntrance) {
            val t = "E / SCAN: log the ${if (signalHere.landmark) signalHere.name.lowercase() else "signal"}"
            glyphs.setText(font, t)
            font.setColor(0.5f, 1f, 0.85f, 1f)
            font.draw(batch, t, (w - glyphs.width) / 2f, margin + lh * 3f)
        }
        // Where the nearest unscanned signal is, and how many are logged here.
        val next = Discoveries.nearestUnscanned(signals, state.scanned, player.x)
        val logged = signals.count { it.key in state.scanned }
        font.setColor(0.5f, 1f, 0.85f, 0.95f)
        val pointer = if (next == null) "All ${signals.size} signals on this world are logged." else {
            val d = Discoveries.loopOffset(next.x, player.x)
            val arrow = if (d >= 0f) ">>" else "<<"
            val what = if (next.landmark) "LANDMARK" else "SIGNAL"
            "$what  $arrow  ${(abs(d) / 10f).toInt()} m      Logged $logged/${signals.size}"
        }
        font.draw(batch, pointer, margin, h - margin - lh * 5f)

        if (nearEntrance && !nearShip) {
            val t = "E / ENTER: explore the ruins"
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

    // ------------------------------------------------------------------ towns

    private fun buildingNear(): Building? {
        val t = town ?: return null
        val off = Towns.townOffset(player.x)
        return Building.entries.firstOrNull { abs(off - it.offset) < Towns.USE_RANGE }
    }

    private fun folkNear(): Townsperson? {
        val t = town ?: return null
        val off = Towns.townOffset(player.x)
        return t.folk.firstOrNull { abs(off - it.offset) < 36f }
    }

    private fun closeTown() {
        openBuilding = null
        talkingTo = null
        townMenu.open = false
    }

    /** Runs the open building's menu or conversation. Returns true while one is open (the world waits). */
    private fun townFrame(w: Float, h: Float): Boolean {
        if (openBuilding == null && talkingTo == null) return false
        townMenu.open = true
        townMenu.handle(w, h, townRows())
        if (!townMenu.open) closeTown()
        return true
    }

    private fun townRows(): List<MenuRow> {
        val t = town ?: return emptyList()
        val rows = ArrayList<MenuRow>()
        val st = state.stats
        val person = talkingTo
        if (person != null) {
            rows.add(MenuRow("${person.name}, ${person.role.lowercase()}:", false))
            val line = person.lines[(talkLines[person] ?: 0) % person.lines.size]
            for (l in wrapText("\"$line\"", 66)) rows.add(MenuRow(l, false))
            rows.add(MenuRow("Ask about something else") { talkLines[person] = (talkLines[person] ?: 0) + 1 })
            rows.add(MenuRow("Goodbye") { closeTown() })
            return rows
        }
        val b = openBuilding ?: return rows
        rows.add(MenuRow("${t.name.uppercase()} ${b.label.uppercase()}      Credits: ${st.credits}", false))
        when (b) {
            Building.BAR -> {
                rows.add(MenuRow("Buy a drink   (+${TownServices.DRINK_CALM.toInt()} mind)   ${TownServices.DRINK_COST} credits", st.credits >= TownServices.DRINK_COST) {
                    if (TownServices.buyDrink(state)) townNote = "It burns all the way down. Your hands stop shaking."
                })
                rows.add(MenuRow("Pay for a rumor   ${TownServices.RUMOR_COST} credits", st.credits >= TownServices.RUMOR_COST) {
                    townNote = TownServices.hearRumor(state, planet, kotlin.random.Random.Default)
                })
            }
            Building.TRADER -> {
                val stock = TownServices.stock(planet, st.day)
                for ((i, g) in stock.withIndex()) {
                    val key = "${planet.id}:${st.day}:$i"
                    if (key in state.boughtToday) {
                        rows.add(MenuRow("Sold out: ${g.name}", false))
                        continue
                    }
                    val price = TownServices.price(g)
                    val ok = st.credits >= price && state.loadout.bag.size < Loadout.BAG_SIZE
                    rows.add(MenuRow("Buy ${g.name}  (${g.describe()})   $price credits", ok) {
                        if (TownServices.buy(state, g)) {
                            state.boughtToday.add(key)
                            townNote = "The ${g.name.lowercase()} is in your bag. Equip it from your ship's gear locker or in the ruins."
                        }
                    })
                }
                for ((i, g) in state.loadout.bag.withIndex()) {
                    rows.add(MenuRow("Sell your ${g.name.lowercase()}   +${TownServices.sellPrice(g)} credits") {
                        val paid = TownServices.sell(state, i)
                        townNote = "Sold for $paid credits."
                    })
                }
                if (state.loadout.bag.isEmpty()) rows.add(MenuRow("You have no spare gear to sell.", false))
            }
            Building.CLINIC -> {
                val cost = TownServices.treatmentCost(st)
                if (cost <= 0) rows.add(MenuRow("\"You look healthy enough. Come back when you're not.\"", false))
                else rows.add(MenuRow("Full treatment: health and injuries   $cost credits", st.credits >= cost) {
                    if (TownServices.treat(state)) townNote = "Stitched, set, cleaned and patched. You feel almost new."
                })
            }
            Building.DEPOT -> {
                val fuel = TownServices.fuelCost(st)
                val hull = TownServices.hullCost(st)
                rows.add(MenuRow(if (fuel <= 0) "Your tanks are full." else "Refuel the ship   $fuel credits to fill (or as much as you can afford)", fuel > 0 && st.credits > 0) {
                    val added = TownServices.refuel(state)
                    townNote = if (added > 0f) "Fuel tanks topped up by ${added.toInt()}%." else "You can't afford any fuel."
                })
                rows.add(MenuRow(if (hull <= 0) "Your hull is sound." else "Repair the hull   $hull credits to fix (or as much as you can afford)", hull > 0 && st.credits > 0) {
                    val added = TownServices.repair(state)
                    townNote = if (added > 0f) "Hull patched by ${added.toInt()}%." else "You can't afford any repairs."
                })
                rows.add(MenuRow("SHIP UPGRADES, fitted by the depot's mechanics:", false))
                val ups = state.upgrades
                for (u in Upgrade.entries) {
                    val lv = ups.level(u)
                    val price = ups.cost(u)
                    if (price == null) {
                        rows.add(MenuRow("${u.label}  [MAX]  - ${u.blurb}", false))
                        continue
                    }
                    rows.add(MenuRow("${u.label}  [$lv/${ShipUpgrades.MAX_LEVEL}]  - ${u.blurb}   $price credits", st.credits >= price) {
                        if (ups.buy(state, u)) townNote = "${u.label} fitted: now level ${ups.level(u)} of ${ShipUpgrades.MAX_LEVEL}."
                    })
                }
            }
        }
        townNote?.let { note -> for (l in wrapText(note, 66)) rows.add(MenuRow(l, false)) }
        rows.add(MenuRow("Leave") { closeTown() })
        return rows
    }

    /** Log a signal: it goes into the journal and the data is worth a few credits. */
    private fun scan(s: Signal) {
        if (!state.scanned.add(s.key)) return
        state.discovered.add(planet.id)
        state.stats.credits += s.reward
        scanFxTimer = 1f
        scanFxX = player.x + Discoveries.loopOffset(s.x, player.x)
        toast = "Logged: ${s.name}. (+${s.reward} credits)  Press J to read your journal."
        toastTimer = 4f
    }

    private fun isBountyPlanet(): Boolean = state.bounties.hunting?.planetId == planet.id

    private fun drawWarnings(w: Float, h: Float) {
        val lh = font.lineHeight
        val margin = h * 0.03f
        var y = h - margin - lh * 6f
        val stats = state.stats
        if (stats.suffocating) {
            font.setColor(1f, 0.3f, 0.25f, 1f)
            font.draw(batch, "SUIT AIR EMPTY - get back to the ship!", margin, y)
            y -= lh
        } else if (stats.air < 25f && Hazards.airDrain(planet.type) > 0f) {
            font.setColor(1f, 0.8f, 0.3f, 1f)
            font.draw(batch, "Suit air running low", margin, y)
            y -= lh
        }
        if (Hazards.exposureDamage(planet.temperatureC) > 0f) {
            font.setColor(1f, 0.6f, 0.3f, 1f)
            font.draw(batch, if (planet.temperatureC > 0) "Extreme heat is hurting you" else "Extreme cold is hurting you", margin, y)
        }
        val t = toast
        if (t != null && toastTimer > 0f) {
            font.setColor(1f, 0.9f, 0.5f, min(1f, toastTimer))
            glyphs.setText(font, t)
            font.draw(batch, t, (w - glyphs.width) / 2f, h - margin - lh)
        }
    }

    override fun dispose() {
        shapes.dispose()
        batch.dispose()
        font.dispose()
        worldFont.dispose()
    }

    private companion object {
        const val STEP = 1f / 120f
        const val ZOOM = 0.6f
        const val CAMERA_RISE = 70f
        const val LOOK_AHEAD = 110f
        const val BOARD_RANGE = 70f
        const val ENTER_RANGE = 60f
    }
}
