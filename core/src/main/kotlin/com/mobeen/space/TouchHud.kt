package com.mobeen.space

import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.graphics.glutils.ShapeRenderer.ShapeType
import kotlin.math.hypot

/** Draws the on-screen touch controls for a [Controls]. Shared by every screen. */
object TouchHud {
    /** Call inside shapes.begin(Filled) with the HUD projection. Leaves the shape type as Filled. */
    fun drawShapes(shapes: ShapeRenderer, c: Controls) {
        if (c.showThrust) {
            shapes.setColor(1f, 0.6f, 0.2f, if (c.thrustHeld) 0.45f else 0.16f)
            shapes.circle(c.thrustCenter.x, c.thrustCenter.y, c.thrustRadius, 40)
        }
        if (c.showBrake) {
            shapes.setColor(0.4f, 0.8f, 1f, if (c.brakeHeld) 0.45f else 0.16f)
            shapes.circle(c.brakeCenter.x, c.brakeCenter.y, c.brakeRadius, 32)
        }
        if (c.actionEnabled) {
            shapes.setColor(0.5f, 1f, 0.6f, if (c.actionHeld) 0.5f else 0.22f)
            shapes.circle(c.actionCenter.x, c.actionCenter.y, c.actionRadius, 32)
        }
        if (c.joyActive) {
            shapes.setColor(1f, 1f, 1f, 0.12f)
            shapes.circle(c.joyOrigin.x, c.joyOrigin.y, c.joyRadius, 40)
            val dx = c.joyPos.x - c.joyOrigin.x
            val dy = c.joyPos.y - c.joyOrigin.y
            val d = hypot(dx, dy)
            val k = if (d > c.joyRadius) c.joyRadius / d else 1f
            shapes.setColor(1f, 1f, 1f, 0.35f)
            shapes.circle(c.joyOrigin.x + dx * k, c.joyOrigin.y + dy * k, c.joyRadius * 0.4f, 24)
        }
        shapes.set(ShapeType.Line)
        if (c.showThrust) {
            shapes.setColor(1f, 0.6f, 0.2f, 0.5f)
            shapes.circle(c.thrustCenter.x, c.thrustCenter.y, c.thrustRadius, 40)
        }
        if (c.showBrake) {
            shapes.setColor(0.4f, 0.8f, 1f, 0.5f)
            shapes.circle(c.brakeCenter.x, c.brakeCenter.y, c.brakeRadius, 32)
        }
        if (c.actionEnabled) {
            shapes.setColor(0.5f, 1f, 0.6f, 0.6f)
            shapes.circle(c.actionCenter.x, c.actionCenter.y, c.actionRadius, 32)
        }
        shapes.set(ShapeType.Filled)
    }

    /** Call inside batch.begin() with the HUD projection. */
    fun drawLabels(batch: SpriteBatch, font: BitmapFont, glyphs: GlyphLayout, c: Controls) {
        font.setColor(1f, 1f, 1f, 0.8f)
        if (c.showThrust) label(batch, font, glyphs, c.thrustLabel, c.thrustCenter.x, c.thrustCenter.y)
        if (c.showBrake) label(batch, font, glyphs, "BRAKE", c.brakeCenter.x, c.brakeCenter.y)
        if (c.actionEnabled) label(batch, font, glyphs, c.actionLabel, c.actionCenter.x, c.actionCenter.y)
    }

    private fun label(batch: SpriteBatch, font: BitmapFont, glyphs: GlyphLayout, text: String, cx: Float, cy: Float) {
        glyphs.setText(font, text)
        font.draw(batch, text, cx - glyphs.width / 2f, cy + glyphs.height / 2f)
    }
}
