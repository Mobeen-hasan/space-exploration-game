package com.mobeen.space

import com.badlogic.gdx.graphics.Color
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class Liquid { NONE, WATER, LAVA, ACID }

/**
 * Side-view ground for one planet: a closed loop (the planet's circumference) of seeded,
 * periodic height data. Walk far enough in one direction and you come back to the ship.
 * The landing pad at x = 0 is perfectly flat.
 */
class Terrain(val planet: Planet) {
    private class Profile(
        val amp: Float,
        val decay: Float,
        val harmonics: Int,
        val ridged: Float,
        val liquid: Liquid,
        val liquidLevel: Float,
    )

    val liquid: Liquid
    val liquidLevel: Float
    val topColor: Color
    val soilColor: Color

    private val table = FloatArray(TABLE_SIZE + 1)

    init {
        val p = profileFor(planet.type)
        liquid = p.liquid
        liquidLevel = p.liquidLevel

        when (planet.type) {
            PlanetType.DESERT -> { topColor = Color(planet.c2); soilColor = Color(0.45f, 0.25f, 0.12f, 1f) }
            PlanetType.ICE -> { topColor = Color(planet.c2); soilColor = Color(0.45f, 0.60f, 0.75f, 1f) }
            PlanetType.OCEAN -> { topColor = Color(planet.c2); soilColor = Color(0.28f, 0.20f, 0.14f, 1f) }
            PlanetType.ROCKY -> { topColor = Color(planet.c2); soilColor = Color(planet.c0).mul(0.8f) }
            PlanetType.TOXIC -> { topColor = Color(planet.c2); soilColor = Color(0.12f, 0.16f, 0.08f, 1f) }
            PlanetType.LAVA -> { topColor = Color(0.20f, 0.11f, 0.10f, 1f); soilColor = Color(0.07f, 0.04f, 0.04f, 1f) }
        }
        topColor.a = 1f
        soilColor.a = 1f

        val rnd = Random(planet.id xor 0x5DEECE66DL)
        val omega = (2.0 * PI / WIDTH).toFloat()
        val phases = FloatArray(p.harmonics + 1) { rnd.nextFloat() * (2f * PI.toFloat()) }
        val amps = FloatArray(p.harmonics + 1) { k -> if (k == 0) 0f else p.amp / Math.pow(k.toDouble(), p.decay.toDouble()).toFloat() }

        for (i in 0..TABLE_SIZE) {
            val x = i * STEP
            var raw = 0f
            for (k in 1..p.harmonics) {
                val s = sin(omega * k * x + phases[k])
                val ridge = 1f - 2f * abs(s) + 0.27f
                raw += amps[k] * ((1f - p.ridged) * s + p.ridged * ridge)
            }
            val d = min(x, WIDTH - x) // distance to the landing pad, around the loop
            val blend = smoothstep(PAD_FLAT, PAD_BLEND, d)
            table[i] = raw * blend
        }
    }

    /** Ground height at world x. The world repeats every [WIDTH] units. */
    fun height(x: Float): Float {
        var t = x / STEP
        t -= floor(t / TABLE_SIZE) * TABLE_SIZE
        val i = min(t.toInt(), TABLE_SIZE - 1)
        val f = t - i
        val a = table[i]
        return a + (table[i + 1] - a) * f
    }

    fun slope(x: Float): Float = (height(x + 4f) - height(x - 4f)) / 8f

    /** Signed distance from x to the nearest landing pad (the ship is parked at the pad centre). */
    fun padOffset(x: Float): Float = x - Math.round(x / WIDTH) * WIDTH

    private fun profileFor(type: PlanetType): Profile = when (type) {
        // Tuned numerically: heights stay within about +-200 and slopes stay walkable (< 1.0).
        PlanetType.DESERT -> Profile(110f, 1.05f, 90, 0.0f, Liquid.NONE, 0f)
        PlanetType.ICE -> Profile(50f, 0.8f, 140, 0.65f, Liquid.NONE, 0f)
        PlanetType.OCEAN -> Profile(100f, 1.05f, 100, 0.0f, Liquid.WATER, -55f)
        PlanetType.ROCKY -> Profile(75f, 0.9f, 130, 0.4f, Liquid.NONE, 0f)
        PlanetType.TOXIC -> Profile(95f, 1.0f, 110, 0.1f, Liquid.ACID, -70f)
        PlanetType.LAVA -> Profile(65f, 0.85f, 140, 0.55f, Liquid.LAVA, -90f)
    }

    companion object {
        const val CELL = 90f
        const val CELLS = 336
        const val WIDTH = CELL * CELLS // 30,240 units around the planet
        const val STEP = 2f
        const val TABLE_SIZE = (WIDTH / STEP).toInt()
        const val PAD_FLAT = 220f
        const val PAD_BLEND = 700f
    }
}

fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
