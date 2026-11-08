package com.mobeen.space

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import kotlin.math.max
import kotlin.math.sin

/** The on-foot character figure, shared by the planet surface and the ship interior. */
object CharacterArt {
    /** A small space-cowboy: coat, scarf, hat. Feet rest at (x, y). Call inside shapes.begin(Filled). */
    fun draw(shapes: ShapeRenderer, x: Float, y: Float, facing: Int, walkTime: Float, grounded: Boolean, tint: Color) {
        fun col(r: Float, g: Float, b: Float) = shapes.setColor(r * tint.r, g * tint.g, b * tint.b, 1f)
        val f = facing.toFloat()
        val swing = if (grounded) sin(walkTime * 12f) * 6f else 4f
        val hipY = y + 17f

        col(0.10f, 0.10f, 0.16f)
        shapes.rectLine(x, hipY, x + swing, y + max(0f, -swing * 0.3f), 4.2f)
        shapes.rectLine(x, hipY, x - swing, y + max(0f, swing * 0.3f), 4.2f)

        col(0.72f, 0.26f, 0.20f)
        shapes.triangle(x - 6f, y + 31f, x + 6f, y + 31f, x - 8f, y + 10f)
        shapes.triangle(x + 6f, y + 31f, x + 8f, y + 10f, x - 8f, y + 10f)

        col(0.62f, 0.20f, 0.16f)
        shapes.rectLine(x, y + 29f, x + f * 4f + swing * 0.5f, y + 19f, 3.4f)

        col(0.95f, 0.75f, 0.20f)
        shapes.rectLine(x - 5f, y + 30.5f, x + 5f, y + 30.5f, 2.6f)

        col(0.92f, 0.74f, 0.62f)
        shapes.circle(x + f * 0.5f, y + 34.5f, 5.2f, 12)

        col(0.14f, 0.11f, 0.10f)
        shapes.rectLine(x - 8.5f, y + 38.5f, x + 8.5f, y + 38.5f, 2.2f)
        shapes.rect(x - 4.5f, y + 38.5f, 9f, 6f)
    }
}
