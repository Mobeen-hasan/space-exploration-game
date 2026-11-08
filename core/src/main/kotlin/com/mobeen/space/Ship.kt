package com.mobeen.space

import com.badlogic.gdx.math.MathUtils
import com.badlogic.gdx.math.Vector2
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** The player's rocket: Newtonian drift (no air drag), thrust along the heading, optional brake. */
class Ship {
    val pos = Vector2()
    val vel = Vector2()

    /** Heading in radians; 0 points along +x. */
    var angle = MathUtils.PI / 2f

    var landedOn: Planet? = null
        private set

    /** Current engine power 0..1, used for the exhaust effect. */
    var thrusting = 0f
        private set

    /** While > 0 the ship will not auto-land (just after take-off). */
    var noLandTimer = 0f

    private var landAngle = 0f
    private var thrustReleasedSinceLanding = false

    val headingX get() = cos(angle)
    val headingY get() = sin(angle)

    fun update(c: Controls, dt: Float) {
        if (noLandTimer > 0f) noLandTimer -= dt

        val planet = landedOn
        if (planet != null) {
            thrusting = 0f
            angle = landAngle
            if (c.thrust < 0.05f) thrustReleasedSinceLanding = true
            if (thrustReleasedSinceLanding && c.thrust > 0.1f) takeOff(planet)
            return
        }

        // Steering
        val target = c.targetAngle
        if (target != null) {
            val diff = atan2(sin(target - angle), cos(target - angle)) // shortest signed difference
            val step = TURN_RATE * dt
            angle += diff.coerceIn(-step, step)
        } else {
            angle += c.turn * TURN_RATE * dt
        }

        // Engine
        thrusting = c.thrust
        if (thrusting > 0f) {
            vel.add(headingX * THRUST * thrusting * dt, headingY * THRUST * thrusting * dt)
        }
        if (c.brake) {
            vel.scl(exp(-BRAKE_DAMPING * dt))
        }
        if (vel.len2() > MAX_SPEED * MAX_SPEED) vel.setLength(MAX_SPEED)

        pos.add(vel.x * dt, vel.y * dt)
    }

    fun land(planet: Planet, nx: Float, ny: Float) {
        landedOn = planet
        vel.setZero()
        landAngle = atan2(ny, nx)
        angle = landAngle
        pos.set(planet.x + nx * (planet.radius + RADIUS * 0.7f), planet.y + ny * (planet.radius + RADIUS * 0.7f))
        thrustReleasedSinceLanding = false
    }

    private fun takeOff(planet: Planet) {
        val nx = cos(landAngle)
        val ny = sin(landAngle)
        landedOn = null
        pos.set(planet.x + nx * (planet.radius + RADIUS * 1.3f), planet.y + ny * (planet.radius + RADIUS * 1.3f))
        vel.set(nx * 80f, ny * 80f)
        noLandTimer = 1f
    }

    companion object {
        const val RADIUS = 18f
        const val MAX_SPEED = 700f
        const val THRUST = 420f
        const val TURN_RATE = 3.6f // rad/s
        const val BRAKE_DAMPING = 1.6f
        const val LAND_SPEED = 180f
    }
}
