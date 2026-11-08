package com.mobeen.space

import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType
import kotlin.math.sin

/** Survival meters drawn as a stack of labelled bars. Shared by every screen. */
object StatsHud {
    private class Row(val label: String, val value: Float, val r: Float, val g: Float, val b: Float)

    // Listed bottom to top.
    private fun rows(s: Stats, showAir: Boolean, showShip: Boolean): List<Row> {
        val list = ArrayList<Row>(6)
        if (showShip) {
            list.add(Row("HULL", s.hull, 0.65f, 0.75f, 0.90f))
            list.add(Row("FUEL", s.fuel, 0.40f, 0.90f, 0.40f))
        }
        if (showAir) list.add(Row("AIR", s.air, 0.40f, 0.85f, 1.00f))
        list.add(Row("FOOD", s.food, 0.90f, 0.55f, 0.20f))
        list.add(Row("ENERGY", s.energy, 0.95f, 0.80f, 0.25f))
        list.add(Row("HEALTH", s.health, 0.90f, 0.25f, 0.25f))
        return list
    }

    /** Height of the whole stack, so screens can place text above it. */
    fun stackHeight(barH: Float, gap: Float, showAir: Boolean, showShip: Boolean): Float {
        val n = 3 + (if (showAir) 1 else 0) + (if (showShip) 2 else 0)
        return n * (barH + gap)
    }

    /** (x, y) is the bottom-left corner of the stack. Call inside shapes.begin(Filled), HUD projection. */
    fun drawShapes(
        shapes: ShapeRenderer, s: Stats, x: Float, y: Float, barW: Float, barH: Float, gap: Float,
        showAir: Boolean, showShip: Boolean, time: Float,
    ) {
        for ((i, row) in rows(s, showAir, showShip).withIndex()) {
            val ry = y + i * (barH + gap)
            val frac = (row.value / Stats.MAX).coerceIn(0f, 1f)
            val low = frac < 0.2f
            shapes.setColor(0.03f, 0.05f, 0.09f, 0.7f)
            shapes.rect(x, ry, barW, barH)
            val pulse = if (low) 0.55f + 0.45f * sin(time * 8f) else 1f
            if (low) shapes.setColor(1f, 0.25f, 0.2f, 0.85f * pulse) else shapes.setColor(row.r, row.g, row.b, 0.85f)
            shapes.rect(x, ry, barW * frac, barH)
        }
        shapes.set(ShapeType.Line)
        for ((i, _) in rows(s, showAir, showShip).withIndex()) {
            shapes.setColor(1f, 1f, 1f, 0.25f)
            shapes.rect(x, y + i * (barH + gap), barW, barH)
        }
        shapes.set(ShapeType.Filled)
    }

    /** Call inside batch.begin(), HUD projection, with the same geometry as [drawShapes]. */
    fun drawLabels(
        batch: SpriteBatch, font: BitmapFont, s: Stats, x: Float, y: Float, barH: Float, gap: Float,
        showAir: Boolean, showShip: Boolean,
    ) {
        font.setColor(1f, 1f, 1f, 0.95f)
        for ((i, row) in rows(s, showAir, showShip).withIndex()) {
            val ry = y + i * (barH + gap)
            font.draw(batch, "${row.label}  ${row.value.toInt()}", x + barH * 0.3f, ry + (barH + font.capHeight) / 2f)
        }
    }
}
