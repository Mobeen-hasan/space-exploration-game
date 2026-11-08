package com.mobeen.space

import com.badlogic.gdx.graphics.glutils.ShapeRenderer

/** Small fixed-size particle pool (ring buffer) for engine exhaust. */
class Particles(private val capacity: Int = 800) {
    private val x = FloatArray(capacity)
    private val y = FloatArray(capacity)
    private val vx = FloatArray(capacity)
    private val vy = FloatArray(capacity)
    private val life = FloatArray(capacity)
    private val maxLife = FloatArray(capacity)
    private val size = FloatArray(capacity)
    private val r = FloatArray(capacity)
    private val g = FloatArray(capacity)
    private val b = FloatArray(capacity)
    private var next = 0

    fun emit(
        px: Float, py: Float, pvx: Float, pvy: Float,
        lifetime: Float, particleSize: Float, cr: Float, cg: Float, cb: Float,
    ) {
        val i = next
        next = (next + 1) % capacity
        x[i] = px; y[i] = py; vx[i] = pvx; vy[i] = pvy
        life[i] = lifetime; maxLife[i] = lifetime; size[i] = particleSize
        r[i] = cr; g[i] = cg; b[i] = cb
    }

    fun update(dt: Float) {
        for (i in 0 until capacity) {
            if (life[i] <= 0f) continue
            life[i] -= dt
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
        }
    }

    /** Call between shapes.begin() and shapes.end(), with additive blending enabled. */
    fun draw(shapes: ShapeRenderer) {
        for (i in 0 until capacity) {
            if (life[i] <= 0f) continue
            val t = life[i] / maxLife[i]
            // Hot (yellow-white) at birth, cooling to the particle's own colour.
            val hot = t * t
            shapes.setColor(
                r[i] + (1f - r[i]) * hot * 0.5f,
                g[i] + (1f - g[i]) * hot * 0.5f,
                b[i] + (1f - b[i]) * hot * 0.3f,
                t * 0.8f,
            )
            shapes.circle(x[i], y[i], size[i] * (0.3f + 0.7f * t), 8)
        }
    }
}
