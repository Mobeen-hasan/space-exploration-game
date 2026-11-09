package com.mobeen.space

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Everything you see on a planet surface except the terrain data itself: sky and day/night
 * lighting, sun and moon, parallax mountains, ground, decorations, liquids, the parked ship
 * and the player figure. All colours come from the planet, so every world looks different.
 */
class SurfaceScenery(private val planet: Planet, private val terrain: Terrain) {
    /** Multiply world colours by this to light them (dark blue at night, warm at dusk). */
    val tint = Color(1f, 1f, 1f, 1f)
    val skyTop = Color(0f, 0f, 0f, 1f)
    val skyHorizon = Color(0f, 0f, 0f, 1f)

    /** 0 = night, 1 = full day. */
    var daylight = 1f
        private set
    private var dusk = 0f
    private var sunAngle = 0f

    private val dayTop = Color(planet.atmoColor.r * 0.65f, planet.atmoColor.g * 0.65f, planet.atmoColor.b * 0.65f, 1f)
    private val dayHorizon = Color(
        min(1f, planet.atmoColor.r * 0.9f + 0.1f),
        min(1f, planet.atmoColor.g * 0.9f + 0.1f),
        min(1f, planet.atmoColor.b * 0.9f + 0.1f),
        1f,
    )
    private val nightTop = Color(0.012f, 0.016f, 0.045f, 1f)
    private val nightHorizon = Color(0.05f, 0.065f, 0.14f, 1f)
    private val warm = Color(1f, 0.5f, 0.25f, 1f)
    private val nightTint = Color(0.26f, 0.30f, 0.50f, 1f)
    private val warmTint = Color(1f, 0.82f, 0.62f, 1f)

    private val seed = (planet.id xor (planet.id ushr 32)).toInt()
    private val jagged = planet.type == PlanetType.ICE || planet.type == PlanetType.ROCKY || planet.type == PlanetType.LAVA
    private val layerPhase = Array(3) { FloatArray(4) }

    private val cTop = Color()
    private val cMid = Color()
    private val cSoil = Color()
    private val cLip = Color()
    private val cA = Color()
    private val cB = Color()

    init {
        val rnd = Random(planet.id xor 0xBEEFL)
        for (l in layerPhase) for (i in l.indices) l[i] = rnd.nextFloat() * 2f * PI.toFloat()
    }

    fun update(dayPhase: Float) {
        sunAngle = 2f * PI.toFloat() * (dayPhase - 0.25f)
        val sh = sin(sunAngle)
        daylight = smoothstep(-0.12f, 0.28f, sh)
        val k = 1f - min(abs(sh) / 0.4f, 1f)
        dusk = k * k
        skyTop.set(nightTop).lerp(dayTop, daylight)
        skyHorizon.set(nightHorizon).lerp(dayHorizon, daylight).lerp(warm, dusk * 0.5f)
        tint.set(nightTint).lerp(Color.WHITE, daylight).lerp(warmTint, dusk * 0.3f)
        tint.a = 1f
    }

    // ------------------------------------------------------------------ sky

    /** Screen-space gradient. Use the HUD projection. */
    fun drawSky(shapes: ShapeRenderer, w: Float, h: Float) {
        shapes.rect(0f, 0f, w, h, skyHorizon, skyHorizon, skyTop, skyTop)
    }

    /** Sun and moon, screen space, additive blending. They set behind the mountains. */
    fun drawCelestials(shapes: ShapeRenderer, w: Float, h: Float) {
        val sunScale = if (planet.type == PlanetType.LAVA) 1.8f else 1f
        val sun = sunColor()
        val sr = sun[0]; val sg = sun[1]; val sb = sun[2]
        val warmMix = dusk * 0.6f
        body(
            shapes, w, h, sunAngle, h * 0.055f * sunScale,
            sr + (warm.r - sr) * warmMix, sg + (warm.g - sg) * warmMix, sb + (warm.b - sb) * warmMix, 1f,
        )
        body(shapes, w, h, sunAngle + PI.toFloat(), h * 0.035f, 0.85f, 0.9f, 1f, 0.9f * (1f - daylight * 0.85f))
    }

    private fun sunColor(): FloatArray = when (planet.type) {
        PlanetType.DESERT -> floatArrayOf(1f, 0.9f, 0.7f)
        PlanetType.ICE -> floatArrayOf(0.8f, 0.9f, 1f)
        PlanetType.OCEAN -> floatArrayOf(1f, 0.97f, 0.85f)
        PlanetType.TOXIC -> floatArrayOf(0.9f, 1f, 0.6f)
        PlanetType.LAVA -> floatArrayOf(1f, 0.5f, 0.3f)
        PlanetType.ROCKY -> floatArrayOf(1f, 0.95f, 0.85f)
    }

    private fun body(shapes: ShapeRenderer, w: Float, h: Float, angle: Float, radius: Float, r: Float, g: Float, b: Float, alpha: Float) {
        val s = sin(angle)
        if (s < -0.15f) return
        val cx = w * 0.5f - cos(angle) * w * 0.42f
        val cy = h * 0.30f + s * h * 0.55f
        for (k in 0 until 6) {
            shapes.setColor(r, g, b, alpha * 0.05f)
            shapes.circle(cx, cy, radius * (1f + (5 - k) * 0.55f), 32)
        }
        shapes.setColor(r, g, b, alpha)
        shapes.circle(cx, cy, radius, 32)
    }

    // ------------------------------------------------------------------ mountains

    private fun layerShape(layer: Int, s: Float): Float {
        val ph = layerPhase[layer]
        var v = 0f
        var norm = 0f
        var amp = 1f
        var wl = LAYER_WAVELENGTH[layer]
        for (o in 0 until 4) {
            val a = sin(s * 2f * PI.toFloat() / wl + ph[o])
            val shape = if (jagged) 1f - abs(a) else 0.5f + 0.5f * a
            v += shape * amp
            norm += amp
            amp *= 0.5f
            wl *= 0.5f
        }
        return v / norm
    }

    fun drawMountains(shapes: ShapeRenderer, camX: Float, camY: Float, halfW: Float, bottomY: Float) {
        val dark = Color(planet.c0.r * 0.55f, planet.c0.g * 0.55f, planet.c0.b * 0.55f, 1f)
        for (layer in 0 until 3) {
            val f = LAYER_FACTOR[layer]
            val base = camY * 0.8f + LAYER_BASE[layer]
            val amp = LAYER_AMP[layer]
            cA.set(dark.r * tint.r, dark.g * tint.g, dark.b * tint.b, 1f)
            cA.set(skyHorizon).lerp(cA, LAYER_DEPTH[layer]) // top colour, hazed toward the sky
            cB.set(cA).lerp(skyHorizon, 0.4f)

            var x = camX - halfW - 20f
            var s = camX * f + (x - camX)
            var y0 = base + amp * layerShape(layer, s)
            val end = camX + halfW + 20f
            while (x < end) {
                val x1 = x + 14f
                val y1 = base + amp * layerShape(layer, camX * f + (x1 - camX))
                shapes.triangle(x, y0, x1, y1, x, bottomY, cA, cA, cB)
                shapes.triangle(x1, y1, x1, bottomY, x, bottomY, cA, cB, cB)
                x = x1
                y0 = y1
            }
        }
    }

    // ------------------------------------------------------------------ ground

    fun drawGround(shapes: ShapeRenderer, camX: Float, halfW: Float, bottomY: Float) {
        val top = terrain.topColor
        val soil = terrain.soilColor
        cTop.set(top.r * tint.r, top.g * tint.g, top.b * tint.b, 1f)
        cMid.set(top).lerp(soil, 0.45f).also { it.set(it.r * tint.r, it.g * tint.g, it.b * tint.b, 1f) }
        cSoil.set(soil.r * tint.r * 0.8f, soil.g * tint.g * 0.8f, soil.b * tint.b * 0.8f, 1f)
        cLip.set(min(1f, top.r + 0.12f) * tint.r, min(1f, top.g + 0.12f) * tint.g, min(1f, top.b + 0.12f) * tint.b, 1f)

        var x = floor((camX - halfW - 12f) / 6f) * 6f
        var h0 = terrain.height(x)
        val end = camX + halfW + 12f
        while (x < end) {
            val x1 = x + 6f
            val h1 = terrain.height(x1)
            // surface band: top colour fading to mid colour
            shapes.triangle(x, h0, x1, h1, x, h0 - 70f, cTop, cTop, cMid)
            shapes.triangle(x1, h1, x1, h1 - 70f, x, h0 - 70f, cTop, cMid, cMid)
            // deep band: mid colour fading to dark soil
            shapes.triangle(x, h0 - 70f, x1, h1 - 70f, x, bottomY, cMid, cMid, cSoil)
            shapes.triangle(x1, h1 - 70f, x1, bottomY, x, bottomY, cMid, cSoil, cSoil)
            // bright lip along the surface
            shapes.triangle(x, h0, x1, h1, x, h0 - 5f, cLip, cLip, cTop)
            shapes.triangle(x1, h1, x1, h1 - 5f, x, h0 - 5f, cLip, cTop, cTop)
            x = x1
            h0 = h1
        }
    }

    // ------------------------------------------------------------------ decorations

    private fun rand(a: Int, salt: Int): Float {
        var x = a.toLong() * -7046029254386353131L + salt.toLong() * -4658895280553007687L
        x = x xor (x ushr 29)
        x *= -4658895280553007687L
        x = x xor (x ushr 32)
        return ((x ushr 40) and 0xFFFFFFL).toInt() / 16777216f
    }

    private fun density(): Float = when (planet.type) {
        PlanetType.DESERT -> 0.30f
        PlanetType.ICE -> 0.45f
        PlanetType.OCEAN -> 0.45f
        PlanetType.ROCKY -> 0.50f
        PlanetType.TOXIC -> 0.60f
        PlanetType.LAVA -> 0.40f
    }

    fun drawDecor(shapes: ShapeRenderer, camX: Float, halfW: Float) {
        val first = floor((camX - halfW - 120f) / Terrain.CELL).toInt()
        val last = floor((camX + halfW + 120f) / Terrain.CELL).toInt()
        val dens = density()
        for (c in first..last) {
            val key = Math.floorMod(c, Terrain.CELLS) * 7 + seed
            for (slot in 0..1) {
                val salt = slot * 10
                if (rand(key, salt + 1) > dens) continue
                val x = (c + rand(key, salt + 2)) * Terrain.CELL
                if (abs(terrain.padOffset(x)) < Terrain.PAD_BLEND * 0.45f) continue // keep the landing pad clear
                if (abs(terrain.entranceOffset(x)) < Terrain.PAD_BLEND * 0.45f) continue // and the ruins entrance
                val y = terrain.height(x)
                if (terrain.liquid != Liquid.NONE && y < terrain.liquidLevel - 6f) continue
                val s = 0.7f + rand(key, salt + 3) * 0.9f
                val variant = rand(key, salt + 4)
                drawOne(shapes, x, y, s, variant)
            }
        }
    }

    private fun col(shapes: ShapeRenderer, r: Float, g: Float, b: Float, a: Float = 1f) =
        shapes.setColor(r * tint.r, g * tint.g, b * tint.b, a)

    private fun dome(shapes: ShapeRenderer, x: Float, y: Float, w: Float, h: Float, segs: Int = 8) {
        var px = x - w / 2f
        var py = y
        for (i in 1..segs) {
            val t = i / segs.toFloat()
            val nx = x - w / 2f + w * t
            val u = t * 2f - 1f
            val ny = y + h * sqrt(max(0f, 1f - u * u))
            shapes.triangle(x, y, px, py, nx, ny)
            px = nx
            py = ny
        }
    }

    private fun drawOne(shapes: ShapeRenderer, x: Float, y: Float, s: Float, v: Float) {
        when (planet.type) {
            PlanetType.DESERT -> if (v < 0.6f) cactus(shapes, x, y, s) else rock(shapes, x, y, s, 0.55f, 0.38f, 0.22f)
            PlanetType.ICE -> if (v < 0.55f) crystals(shapes, x, y, s) else {
                col(shapes, 0.9f, 0.95f, 1f)
                dome(shapes, x, y - 2f, 70f * s, 22f * s)
            }
            PlanetType.OCEAN -> if (v < 0.5f) palm(shapes, x, y, s, v) else reeds(shapes, x, y, s)
            PlanetType.ROCKY -> if (v < 0.5f) rock(shapes, x, y, s * 1.2f, 0.45f, 0.42f, 0.40f) else deadTree(shapes, x, y, s)
            PlanetType.TOXIC -> if (v < 0.55f) mushroom(shapes, x, y, s) else spikes(shapes, x, y, s)
            PlanetType.LAVA -> if (v < 0.6f) spire(shapes, x, y, s) else rock(shapes, x, y, s, 0.18f, 0.1f, 0.09f)
        }
    }

    private fun cactus(shapes: ShapeRenderer, x: Float, y: Float, s: Float) {
        col(shapes, 0.27f, 0.50f, 0.24f)
        shapes.rect(x - 5f * s, y - 2f, 10f * s, 46f * s)
        shapes.circle(x, y + 44f * s, 5f * s, 10)
        shapes.rect(x - 17f * s, y + 20f * s, 13f * s, 5f * s)
        shapes.rect(x - 17f * s, y + 20f * s, 5f * s, 17f * s)
        shapes.circle(x - 14.5f * s, y + 37f * s, 2.6f * s, 8)
        shapes.rect(x + 4f * s, y + 28f * s, 13f * s, 5f * s)
        shapes.rect(x + 12f * s, y + 28f * s, 5f * s, 14f * s)
        shapes.circle(x + 14.5f * s, y + 42f * s, 2.6f * s, 8)
    }

    private fun rock(shapes: ShapeRenderer, x: Float, y: Float, s: Float, r: Float, g: Float, b: Float) {
        col(shapes, r, g, b)
        shapes.triangle(x - 18f * s, y - 2f, x + 20f * s, y - 2f, x + 4f * s, y + 22f * s)
        col(shapes, r * 1.35f, g * 1.35f, b * 1.35f)
        shapes.triangle(x - 18f * s, y - 2f, x + 4f * s, y + 22f * s, x - 8f * s, y + 14f * s)
    }

    private fun crystals(shapes: ShapeRenderer, x: Float, y: Float, s: Float) {
        shapes.setColor(0.65f, 0.9f, 1f, 0.95f)
        shapes.triangle(x - 12f * s, y - 2f, x - 3f * s, y - 2f, x - 9f * s, y + 42f * s)
        shapes.setColor(0.8f, 0.96f, 1f, 0.95f)
        shapes.triangle(x - 4f * s, y - 2f, x + 6f * s, y - 2f, x + 2f * s, y + 58f * s)
        shapes.setColor(0.55f, 0.82f, 1f, 0.95f)
        shapes.triangle(x + 5f * s, y - 2f, x + 14f * s, y - 2f, x + 13f * s, y + 34f * s)
    }

    private fun palm(shapes: ShapeRenderer, x: Float, y: Float, s: Float, v: Float) {
        val lean = (v * 10f - 2.5f) * s
        val topX = x + lean
        val topY = y + 62f * s
        col(shapes, 0.42f, 0.30f, 0.18f)
        shapes.triangle(x - 3.5f * s, y - 2f, x + 3.5f * s, y - 2f, topX - 1.5f * s, topY)
        shapes.triangle(x + 3.5f * s, y - 2f, topX + 1.5f * s, topY, topX - 1.5f * s, topY)
        col(shapes, 0.22f, 0.55f, 0.25f)
        val dx = floatArrayOf(-32f, -20f, 0f, 20f, 32f)
        val dy = floatArrayOf(-16f, 2f, 14f, 2f, -16f)
        for (i in 0 until 5) {
            shapes.triangle(topX, topY + 3f * s, topX, topY - 3f * s, topX + dx[i] * s, topY + dy[i] * s)
        }
    }

    private fun reeds(shapes: ShapeRenderer, x: Float, y: Float, s: Float) {
        col(shapes, 0.30f, 0.58f, 0.28f)
        val lean = floatArrayOf(-9f, -3f, 4f, 10f)
        val len = floatArrayOf(22f, 30f, 27f, 20f)
        for (i in 0 until 4) {
            val bx = x + (i - 1.5f) * 4f * s
            shapes.triangle(bx - 1.6f * s, y - 2f, bx + 1.6f * s, y - 2f, bx + lean[i] * s, y + len[i] * s)
        }
    }

    private fun deadTree(shapes: ShapeRenderer, x: Float, y: Float, s: Float) {
        col(shapes, 0.25f, 0.2f, 0.17f)
        shapes.triangle(x - 4f * s, y - 2f, x + 4f * s, y - 2f, x, y + 56f * s)
        shapes.triangle(x, y + 30f * s, x + 1.5f * s, y + 28f * s, x + 22f * s, y + 48f * s)
        shapes.triangle(x, y + 40f * s, x - 1.5f * s, y + 38f * s, x - 18f * s, y + 56f * s)
        shapes.triangle(x, y + 20f * s, x + 1.5f * s, y + 18f * s, x - 15f * s, y + 32f * s)
    }

    private fun mushroom(shapes: ShapeRenderer, x: Float, y: Float, s: Float) {
        col(shapes, 0.75f, 0.8f, 0.65f)
        shapes.rect(x - 3f * s, y - 2f, 6f * s, 22f * s)
        shapes.setColor(0.55f, 0.95f, 0.3f, 1f) // glows: not darkened by night
        dome(shapes, x, y + 18f * s, 30f * s, 15f * s)
        shapes.setColor(0.9f, 1f, 0.7f, 1f)
        shapes.circle(x - 6f * s, y + 24f * s, 1.8f * s, 6)
        shapes.circle(x + 5f * s, y + 27f * s, 1.5f * s, 6)
    }

    private fun spikes(shapes: ShapeRenderer, x: Float, y: Float, s: Float) {
        col(shapes, 0.30f, 0.62f, 0.20f)
        val lean = floatArrayOf(-16f, -8f, 0f, 8f, 16f)
        val len = floatArrayOf(22f, 34f, 42f, 34f, 22f)
        for (i in 0 until 5) {
            val bx = x + (i - 2) * 3f * s
            shapes.triangle(bx - 2f * s, y - 2f, bx + 2f * s, y - 2f, bx + lean[i] * s, y + len[i] * s)
        }
    }

    private fun spire(shapes: ShapeRenderer, x: Float, y: Float, s: Float) {
        col(shapes, 0.14f, 0.08f, 0.08f)
        shapes.triangle(x - 11f * s, y - 2f, x + 11f * s, y - 2f, x + 2f * s, y + 78f * s)
        shapes.setColor(1f, 0.45f, 0.1f, 0.85f) // glowing edge
        shapes.triangle(x + 4f * s, y - 2f, x + 11f * s, y - 2f, x + 2.5f * s, y + 74f * s)
    }

    // ------------------------------------------------------------------ liquids

    fun drawLiquid(shapes: ShapeRenderer, camX: Float, halfW: Float, time: Float) {
        if (terrain.liquid == Liquid.NONE) return
        val level = terrain.liquidLevel
        val lit = terrain.liquid != Liquid.LAVA
        val tr: Float; val tg: Float; val tb: Float; val ta: Float
        val br: Float; val bg: Float; val bb: Float; val ba: Float
        when (terrain.liquid) {
            Liquid.WATER -> { tr = 0.25f; tg = 0.6f; tb = 0.85f; ta = 0.6f; br = 0.04f; bg = 0.18f; bb = 0.4f; ba = 0.9f }
            Liquid.ACID -> { tr = 0.5f; tg = 0.9f; tb = 0.2f; ta = 0.7f; br = 0.12f; bg = 0.3f; bb = 0.05f; ba = 0.92f }
            else -> { tr = 1f; tg = 0.55f; tb = 0.12f; ta = 0.97f; br = 0.6f; bg = 0.1f; bb = 0.02f; ba = 0.98f }
        }
        val k = if (lit) 1f else 0f
        cA.set(tr * (1f - k + k * tint.r), tg * (1f - k + k * tint.g), tb * (1f - k + k * tint.b), ta)
        cB.set(br * (1f - k + k * tint.r), bg * (1f - k + k * tint.g), bb * (1f - k + k * tint.b), ba)

        var x = floor((camX - halfW - 12f) / 12f) * 12f
        val end = camX + halfW + 12f
        while (x < end) {
            val x1 = x + 12f
            val h0 = terrain.height(x)
            val h1 = terrain.height(x1)
            if (h0 < level || h1 < level) {
                val t0 = level + wave(x, time)
                val t1 = level + wave(x1, time)
                val b0 = min(h0, t0)
                val b1 = min(h1, t1)
                shapes.triangle(x, t0, x1, t1, x, b0, cA, cA, cB)
                shapes.triangle(x1, t1, x1, b1, x, b0, cA, cB, cB)
                // bright foam / crust line on the surface
                shapes.setColor(min(1f, tr + 0.25f) * (1f - k + k * tint.r), min(1f, tg + 0.25f) * (1f - k + k * tint.g), min(1f, tb + 0.25f) * (1f - k + k * tint.b), 0.9f)
                shapes.triangle(x, t0, x1, t1, x, t0 - 2.5f)
                shapes.triangle(x1, t1, x1, t1 - 2.5f, x, t0 - 2.5f)
            }
            x = x1
        }
    }

    /** Extra glow above lava and acid. Call with additive blending. */
    fun drawLiquidGlow(shapes: ShapeRenderer, camX: Float, halfW: Float, time: Float) {
        if (terrain.liquid != Liquid.LAVA && terrain.liquid != Liquid.ACID) return
        val level = terrain.liquidLevel
        val lava = terrain.liquid == Liquid.LAVA
        cA.set(if (lava) 1f else 0.5f, if (lava) 0.45f else 0.9f, if (lava) 0.1f else 0.2f, 0f)
        cB.set(cA.r, cA.g, cA.b, if (lava) 0.35f else 0.22f)
        var x = floor((camX - halfW - 12f) / 12f) * 12f
        val end = camX + halfW + 12f
        while (x < end) {
            val x1 = x + 12f
            if (terrain.height(x) < level || terrain.height(x1) < level) {
                val t0 = level + wave(x, time)
                val t1 = level + wave(x1, time)
                shapes.triangle(x, t0 + 55f, x1, t1 + 55f, x, t0, cA, cA, cB)
                shapes.triangle(x1, t1 + 55f, x1, t1, x, t0, cA, cB, cB)
            }
            x = x1
        }
    }

    private fun wave(x: Float, time: Float): Float =
        sin(x * 0.04f + time * 1.4f) * 2.2f + sin(x * 0.013f + time * 0.7f) * 1.5f

    // ------------------------------------------------------------------ ship and player

    /** The parked rocket, standing on the landing pad at (sx, gy). */
    fun drawShip(shapes: ShapeRenderer, sx: Float, gy: Float, near: Boolean) {
        col(shapes, 0.45f, 0.48f, 0.55f)
        shapes.rectLine(sx - 14f, gy + 22f, sx - 34f, gy, 4f)
        shapes.rectLine(sx + 14f, gy + 22f, sx + 34f, gy, 4f)
        shapes.circle(sx - 35f, gy + 1f, 4f, 8)
        shapes.circle(sx + 35f, gy + 1f, 4f, 8)

        col(shapes, 0.80f, 0.28f, 0.22f)
        shapes.triangle(sx - 22f, gy + 20f, sx - 44f, gy + 3f, sx - 22f, gy + 58f)
        shapes.triangle(sx + 22f, gy + 20f, sx + 44f, gy + 3f, sx + 22f, gy + 58f)

        col(shapes, 0.86f, 0.89f, 0.96f)
        shapes.rect(sx - 22f, gy + 16f, 44f, 74f)
        shapes.triangle(sx - 22f, gy + 90f, sx + 22f, gy + 90f, sx, gy + 130f)
        col(shapes, 0.68f, 0.72f, 0.84f)
        shapes.rect(sx + 6f, gy + 16f, 16f, 74f)
        shapes.triangle(sx + 6f, gy + 90f, sx + 22f, gy + 90f, sx, gy + 130f)

        col(shapes, 0.80f, 0.28f, 0.22f)
        shapes.rect(sx - 22f, gy + 78f, 44f, 6f)
        col(shapes, 0.30f, 0.32f, 0.38f)
        shapes.rect(sx - 10f, gy + 8f, 20f, 9f)

        shapes.setColor(0.35f, 0.85f, 1f, 1f)
        shapes.circle(sx, gy + 62f, 8f, 16)
        shapes.setColor(0.8f, 0.97f, 1f, 1f)
        shapes.circle(sx - 2.5f, gy + 64.5f, 2.6f, 8)

        col(shapes, 0.22f, 0.25f, 0.32f)
        shapes.rect(sx - 8f, gy + 20f, 16f, 28f)
        shapes.setColor(1f, 0.85f, 0.5f, if (near) 1f else 0.65f)
        shapes.rect(sx - 6f, gy + 22f, 12f, 24f)
    }

    /** The stone archway down into the planet's ruins, standing on its flat pad at (ex, gy). */
    fun drawEntrance(shapes: ShapeRenderer, ex: Float, gy: Float, near: Boolean, time: Float) {
        val a = planet.atmoColor
        col(shapes, 0.30f, 0.30f, 0.34f)
        shapes.rect(ex - 38f, gy - 2f, 14f, 78f)
        shapes.rect(ex + 24f, gy - 2f, 14f, 78f)
        shapes.rect(ex - 44f, gy + 72f, 88f, 14f)
        col(shapes, 0.22f, 0.22f, 0.26f)
        shapes.rect(ex - 48f, gy + 84f, 96f, 8f)
        col(shapes, 0.26f, 0.26f, 0.30f)
        shapes.rect(ex - 34f, gy - 2f, 68f, 5f)
        shapes.setColor(0.02f, 0.02f, 0.04f, 1f)
        shapes.rect(ex - 24f, gy - 2f, 48f, 74f)
        // glowing runes on the pillars
        val pulse = 0.55f + 0.45f * sin(time * 2f)
        shapes.setColor(a.r, a.g, a.b, (if (near) 1f else 0.7f) * pulse)
        for (i in 0 until 3) {
            shapes.rect(ex - 35f, gy + 14f + i * 17f, 8f, 4f)
            shapes.rect(ex + 27f, gy + 14f + i * 17f, 8f, 4f)
        }
    }

    /** A pale beam of light rising from the entrance, so it can be seen from far away. Additive blending. */
    fun drawEntranceBeam(shapes: ShapeRenderer, ex: Float, gy: Float, time: Float, bountyTarget: Boolean = false) {
        val a = if (bountyTarget) Color(1f, 0.78f, 0.3f, 1f) else planet.atmoColor // gold marks your bounty
        val strength = (if (bountyTarget) 0.34f else 0.20f) + 0.06f * sin(time * 1.5f)
        cA.set(a.r, a.g, a.b, strength)
        cB.set(a.r, a.g, a.b, 0f)
        shapes.rect(ex - 14f, gy + 90f, 28f, 900f, cA, cA, cB, cB)
    }

    /** The player figure. Feet rest at (p.x, p.y). */
    fun drawPlayer(shapes: ShapeRenderer, p: SurfacePlayer) {
        CharacterArt.draw(shapes, p.x, p.y, p.facing, p.walkTime, p.grounded, tint)
    }

    /** Soft lantern glow around the player at night. Call with additive blending. */
    fun drawPlayerGlow(shapes: ShapeRenderer, p: SurfacePlayer) {
        val night = 1f - daylight
        if (night < 0.05f) return
        for (k in 0 until 6) {
            shapes.setColor(1f, 0.8f, 0.5f, 0.035f * night)
            shapes.circle(p.x, p.y + 20f, 22f + k * 14f, 24)
        }
    }

    private companion object {
        val LAYER_FACTOR = floatArrayOf(0.12f, 0.30f, 0.55f)
        val LAYER_BASE = floatArrayOf(110f, 70f, 30f)
        val LAYER_AMP = floatArrayOf(120f, 90f, 60f)
        val LAYER_DEPTH = floatArrayOf(0.35f, 0.60f, 0.85f)
        val LAYER_WAVELENGTH = floatArrayOf(3200f, 2200f, 1500f)
    }
}

/** Drifting particles near the camera: dust, snow, spores, embers. */
class Ambient(private val type: PlanetType) {
    private val count: Int
    private val xs: FloatArray
    private val ys: FloatArray
    private val phase: FloatArray
    private val r: Float
    private val g: Float
    private val b: Float
    private val alpha: Float
    private val size: Float
    private val wind: Float
    private val fall: Float

    init {
        when (type) {
            PlanetType.DESERT -> { count = 70; r = 0.95f; g = 0.8f; b = 0.55f; alpha = 0.25f; size = 1.6f; wind = 60f; fall = 5f }
            PlanetType.ICE -> { count = 110; r = 1f; g = 1f; b = 1f; alpha = 0.7f; size = 2f; wind = 20f; fall = 40f }
            PlanetType.OCEAN -> { count = 40; r = 0.8f; g = 0.95f; b = 1f; alpha = 0.25f; size = 1.5f; wind = 25f; fall = -6f }
            PlanetType.ROCKY -> { count = 50; r = 0.8f; g = 0.75f; b = 0.7f; alpha = 0.2f; size = 1.6f; wind = 35f; fall = 4f }
            PlanetType.TOXIC -> { count = 70; r = 0.6f; g = 1f; b = 0.3f; alpha = 0.5f; size = 2.2f; wind = 15f; fall = -15f }
            PlanetType.LAVA -> { count = 70; r = 1f; g = 0.5f; b = 0.15f; alpha = 0.8f; size = 2f; wind = 20f; fall = -45f }
        }
        val rnd = Random(type.ordinal + 99L)
        xs = FloatArray(count) { rnd.nextFloat() * TILE_X }
        ys = FloatArray(count) { rnd.nextFloat() * TILE_Y }
        phase = FloatArray(count) { rnd.nextFloat() * 6.28f }
    }

    /** Call between shapes.begin() and shapes.end() with additive blending. */
    fun draw(shapes: ShapeRenderer, camX: Float, camY: Float, zoom: Float, time: Float, daylight: Float) {
        val a = alpha * (if (type == PlanetType.TOXIC || type == PlanetType.LAVA) 1f else 0.4f + 0.6f * daylight)
        val s = size * zoom * 1.6f
        for (i in 0 until count) {
            val x = camX + wrap(xs[i] + time * wind - camX * 0.7f, TILE_X) + sin(time * 0.7f + phase[i]) * 10f
            val y = camY + wrap(ys[i] - time * fall - camY * 0.7f, TILE_Y)
            shapes.setColor(r, g, b, a)
            shapes.rect(x - s / 2f, y - s / 2f, s, s)
        }
    }

    private fun wrap(v: Float, tile: Float): Float {
        var m = v % tile
        if (m < 0f) m += tile
        return m - tile / 2f
    }

    private companion object {
        const val TILE_X = 1600f
        const val TILE_Y = 1000f
    }
}
