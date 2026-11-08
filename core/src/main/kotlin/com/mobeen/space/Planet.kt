package com.mobeen.space

import com.badlogic.gdx.graphics.Color
import kotlin.math.floor
import kotlin.random.Random

enum class PlanetType(
    val label: String,
    val c0: FloatArray,
    val c1: FloatArray,
    val c2: FloatArray,
    val atmo: FloatArray,
    val tempMin: Int,
    val tempMax: Int,
    val atmospheres: List<String>,
) {
    ROCKY(
        "Rocky",
        floatArrayOf(0.25f, 0.22f, 0.20f), floatArrayOf(0.50f, 0.42f, 0.35f), floatArrayOf(0.75f, 0.68f, 0.60f),
        floatArrayOf(0.60f, 0.70f, 0.90f), -60, 40, listOf("Thin nitrogen", "Trace argon"),
    ),
    DESERT(
        "Desert",
        floatArrayOf(0.55f, 0.30f, 0.12f), floatArrayOf(0.82f, 0.55f, 0.25f), floatArrayOf(0.95f, 0.80f, 0.50f),
        floatArrayOf(1.00f, 0.70f, 0.40f), 40, 110, listOf("Dry carbon dioxide", "Thin and dusty"),
    ),
    OCEAN(
        "Ocean",
        floatArrayOf(0.05f, 0.20f, 0.45f), floatArrayOf(0.10f, 0.45f, 0.75f), floatArrayOf(0.30f, 0.60f, 0.30f),
        floatArrayOf(0.40f, 0.70f, 1.00f), 0, 35, listOf("Breathable nitrogen-oxygen", "Humid and dense"),
    ),
    ICE(
        "Ice",
        floatArrayOf(0.60f, 0.75f, 0.85f), floatArrayOf(0.85f, 0.92f, 0.97f), floatArrayOf(1.00f, 1.00f, 1.00f),
        floatArrayOf(0.70f, 0.90f, 1.00f), -150, -40, listOf("Thin methane haze", "Almost none"),
    ),
    TOXIC(
        "Toxic",
        floatArrayOf(0.15f, 0.30f, 0.10f), floatArrayOf(0.40f, 0.60f, 0.15f), floatArrayOf(0.75f, 0.85f, 0.30f),
        floatArrayOf(0.60f, 1.00f, 0.30f), 20, 90, listOf("Sulfurous and toxic", "Corrosive chlorine mix"),
    ),
    LAVA(
        "Lava",
        floatArrayOf(0.10f, 0.05f, 0.05f), floatArrayOf(0.45f, 0.10f, 0.05f), floatArrayOf(1.00f, 0.50f, 0.10f),
        floatArrayOf(1.00f, 0.35f, 0.15f), 300, 900, listOf("Ash and sulfur dioxide", "Superheated and dense"),
    ),
}

class Planet(
    val id: Long,
    val name: String,
    val type: PlanetType,
    val x: Float,
    val y: Float,
    val radius: Float,
    val gravityG: Float,
    val temperatureC: Int,
    val atmosphere: String,
    val dayHours: Float,
    val c0: Color,
    val c1: Color,
    val c2: Color,
    val atmoColor: Color,
    val noiseSeed: Float,
    val spin: Float,
) {
    /** Acceleration (world units / s^2) at the surface. */
    val surfaceGravity: Float get() = gravityG * 180f

    /** Diameter shown to the player, in km. */
    val diameterKm: Int get() = (radius * 2f * 40f).toInt()
}

/**
 * Deterministic planet generation: the world is split into square chunks, and each
 * chunk may contain one planet. The same chunk always produces the same planet.
 */
object PlanetGenerator {
    const val CHUNK = 2400f
    private const val WORLD_SEED = 1337L
    private const val MARGIN = 0.2f // keeps planets away from chunk edges so they never overlap

    private val pre = listOf("Ka", "Zor", "Vel", "Tra", "Or", "Mi", "Nex", "Sol", "Ar", "Eth", "Quo", "Lun", "Pho", "Ry", "Dra", "Cel", "Ium", "Bel", "Tar", "Xan")
    private val mid = listOf("", "ta", "ri", "mo", "ven", "lu", "dar", "ix", "on", "sa")
    private val suf = listOf("a", "is", "us", "on", "ar", "ex", "ia", "or", "ae", "um")
    private val roman = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX")

    fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)

    fun forChunk(cx: Int, cy: Int): Planet? {
        val k = key(cx, cy)
        val rnd = Random(k * 6364136223846793005L + WORLD_SEED)
        val forced = cx == 0 && cy == 0 // always have a planet near the start
        if (!forced && rnd.nextFloat() > 0.6f) return null

        val type = PlanetType.entries[rnd.nextInt(PlanetType.entries.size)]
        val px = (cx + MARGIN + rnd.nextFloat() * (1f - 2f * MARGIN)) * CHUNK
        val py = (cy + MARGIN + rnd.nextFloat() * (1f - 2f * MARGIN)) * CHUNK
        val radius = 90f + rnd.nextFloat() * 240f
        val gravity = 0.3f + rnd.nextFloat() * 1.5f
        val temp = type.tempMin + (rnd.nextFloat() * (type.tempMax - type.tempMin)).toInt()
        val atmosphere = type.atmospheres[rnd.nextInt(type.atmospheres.size)]
        val day = 6f + rnd.nextFloat() * 54f
        val name = pre[rnd.nextInt(pre.size)] + mid[rnd.nextInt(mid.size)] + suf[rnd.nextInt(suf.size)] +
            " " + roman[rnd.nextInt(roman.size)]

        fun jitter(c: FloatArray): Color {
            val j = { (rnd.nextFloat() - 0.5f) * 0.12f }
            return Color((c[0] + j()).coerceIn(0f, 1f), (c[1] + j()).coerceIn(0f, 1f), (c[2] + j()).coerceIn(0f, 1f), 1f)
        }

        return Planet(
            id = k,
            name = name.replaceFirstChar { it.uppercase() },
            type = type,
            x = px,
            y = py,
            radius = radius,
            gravityG = gravity,
            temperatureC = temp,
            atmosphere = atmosphere,
            dayHours = day,
            c0 = jitter(type.c0),
            c1 = jitter(type.c1),
            c2 = jitter(type.c2),
            atmoColor = Color(type.atmo[0], type.atmo[1], type.atmo[2], 1f),
            noiseSeed = rnd.nextFloat() * 100f,
            spin = 0.02f + rnd.nextFloat() * 0.06f,
        )
    }
}

/** Lazily generates and caches planets around a point. */
class PlanetField {
    private val cache = HashMap<Long, Planet?>()

    private fun get(cx: Int, cy: Int): Planet? {
        val k = PlanetGenerator.key(cx, cy)
        if (cache.containsKey(k)) return cache[k]
        val p = PlanetGenerator.forChunk(cx, cy)
        cache[k] = p
        return p
    }

    /** Fills [out] with every planet whose disc is within [range] of (x, y). */
    fun near(x: Float, y: Float, range: Float, out: MutableList<Planet>) {
        out.clear()
        if (cache.size > 600) cache.clear() // cheap to regenerate, keeps memory bounded
        val c = PlanetGenerator.CHUNK
        val minCx = floor((x - range) / c).toInt()
        val maxCx = floor((x + range) / c).toInt()
        val minCy = floor((y - range) / c).toInt()
        val maxCy = floor((y + range) / c).toInt()
        for (cx in minCx..maxCx) {
            for (cy in minCy..maxCy) {
                val p = get(cx, cy) ?: continue
                val dx = p.x - x
                val dy = p.y - y
                val r = range + p.radius
                if (dx * dx + dy * dy <= r * r) out.add(p)
            }
        }
    }
}
