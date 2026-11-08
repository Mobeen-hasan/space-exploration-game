package com.mobeen.space

import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import kotlin.math.sin
import kotlin.random.Random

/**
 * Infinite parallax starfield: several tiled layers of stars that scroll at different
 * fractions of the camera movement, plus a few faint nebula clouds in the far layer.
 */
class Starfield(seed: Long = 7L) {
    private class Layer(
        val factor: Float,
        val size: Float,
        val brightness: Float,
        count: Int,
        rnd: Random,
    ) {
        val xs = FloatArray(count) { rnd.nextFloat() * TILE }
        val ys = FloatArray(count) { rnd.nextFloat() * TILE }
        val phase = FloatArray(count) { rnd.nextFloat() * 6.28f }
        val warm = FloatArray(count) { rnd.nextFloat() }
    }

    private class Nebula(val x: Float, val y: Float, val radius: Float, val r: Float, val g: Float, val b: Float)

    private val layers: List<Layer>
    private val nebulae: List<Nebula>

    init {
        val rnd = Random(seed)
        layers = listOf(
            Layer(0.08f, 1.6f, 0.55f, 280, rnd),
            Layer(0.20f, 2.2f, 0.75f, 170, rnd),
            Layer(0.42f, 3.0f, 1.00f, 90, rnd),
        )
        val palette = listOf(
            floatArrayOf(0.45f, 0.20f, 0.75f),
            floatArrayOf(0.15f, 0.55f, 0.70f),
            floatArrayOf(0.75f, 0.25f, 0.45f),
            floatArrayOf(0.20f, 0.30f, 0.80f),
        )
        nebulae = List(7) {
            val c = palette[rnd.nextInt(palette.size)]
            Nebula(
                rnd.nextFloat() * NEBULA_TILE, rnd.nextFloat() * NEBULA_TILE,
                600f + rnd.nextFloat() * 700f, c[0], c[1], c[2],
            )
        }
    }

    /** Call between shapes.begin() and shapes.end(), with the world camera's projection. */
    fun draw(shapes: ShapeRenderer, camX: Float, camY: Float, zoom: Float, time: Float) {
        // Nebulae: stacked translucent discs approximate a soft gradient.
        for (n in nebulae) {
            val x = camX + wrap(n.x - camX * NEBULA_FACTOR, NEBULA_TILE)
            val y = camY + wrap(n.y - camY * NEBULA_FACTOR, NEBULA_TILE)
            for (k in 0 until 6) {
                shapes.setColor(n.r, n.g, n.b, 0.014f)
                shapes.circle(x, y, n.radius * (1f - k * 0.15f), 40)
            }
        }

        for (layer in layers) {
            val s = layer.size * zoom
            for (i in layer.xs.indices) {
                val x = camX + wrap(layer.xs[i] - camX * layer.factor, TILE)
                val y = camY + wrap(layer.ys[i] - camY * layer.factor, TILE)
                val twinkle = 0.75f + 0.25f * sin(time * 1.5f + layer.phase[i])
                val a = layer.brightness * twinkle
                val w = layer.warm[i]
                shapes.setColor(0.75f + 0.25f * w, 0.8f + 0.1f * w, 1f - 0.3f * w, a)
                shapes.rect(x - s / 2f, y - s / 2f, s, s)
            }
        }
    }

    /** Wraps [v] into [-tile/2, tile/2). */
    private fun wrap(v: Float, tile: Float): Float {
        var m = v % tile
        if (m < 0f) m += tile
        return m - tile / 2f
    }

    private companion object {
        const val TILE = 4096f
        const val NEBULA_TILE = 8000f
        const val NEBULA_FACTOR = 0.05f
    }
}
