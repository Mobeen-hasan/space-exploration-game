package com.mobeen.space

import com.badlogic.gdx.graphics.OrthographicCamera
import kotlin.math.exp
import kotlin.math.min

/**
 * Follows the ship, looks ahead along its velocity, and zooms out as it speeds up.
 * Smoothing is frame-rate independent.
 */
class ChaseCamera(private val camera: OrthographicCamera) {
    fun snapTo(ship: Ship) {
        camera.position.set(ship.pos.x, ship.pos.y, 0f)
        camera.zoom = 1f
        camera.update()
    }

    fun update(ship: Ship, dt: Float) {
        val landed = ship.landedOn != null

        // Look ahead: aim a fraction of a second in front of the ship, capped.
        var lookX = ship.vel.x * LOOK_AHEAD_SECONDS
        var lookY = ship.vel.y * LOOK_AHEAD_SECONDS
        val len = kotlin.math.hypot(lookX, lookY)
        if (len > MAX_LOOK_AHEAD) {
            lookX *= MAX_LOOK_AHEAD / len
            lookY *= MAX_LOOK_AHEAD / len
        }
        val targetX = ship.pos.x + lookX
        val targetY = ship.pos.y + lookY

        val follow = 1f - exp(-FOLLOW_SPEED * dt)
        camera.position.x += (targetX - camera.position.x) * follow
        camera.position.y += (targetY - camera.position.y) * follow

        // Zoom out with speed, zoom in a little when landed.
        val speedFraction = min(ship.vel.len() / Ship.MAX_SPEED, 1f)
        val targetZoom = if (landed) LANDED_ZOOM else 1f + speedFraction * MAX_EXTRA_ZOOM
        camera.zoom += (targetZoom - camera.zoom) * (1f - exp(-ZOOM_SPEED * dt))

        camera.update()
    }

    private companion object {
        const val LOOK_AHEAD_SECONDS = 0.55f
        const val MAX_LOOK_AHEAD = 380f
        const val FOLLOW_SPEED = 4f
        const val ZOOM_SPEED = 1.8f
        const val MAX_EXTRA_ZOOM = 0.75f
        const val LANDED_ZOOM = 0.75f
    }
}
