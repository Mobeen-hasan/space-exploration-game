package com.mobeen.space

import kotlin.math.abs
import kotlin.math.pow

/** The on-foot character: walks, jumps and wades across [Terrain]. Gravity follows the planet. */
class SurfacePlayer(private val planet: Planet) {
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f

    /** +1 facing right, -1 facing left. */
    var facing = 1
    var grounded = false
        private set
    var walkTime = 0f
        private set

    private var jumpBuffer = 0f
    private var coyote = 0f

    /** Effective downward acceleration; low-gravity planets feel floaty, heavy ones feel stiff. */
    private val gravity = 1000f * planet.gravityG.pow(0.7f)

    fun spawn(terrain: Terrain, atX: Float) {
        x = atX
        y = terrain.height(atX)
        vx = 0f
        vy = 0f
        grounded = true
    }

    fun inLiquid(terrain: Terrain): Boolean =
        terrain.liquid != Liquid.NONE && y + 12f < terrain.liquidLevel

    fun update(c: Controls, terrain: Terrain, dt: Float) {
        val wet = inLiquid(terrain)

        // Horizontal movement
        val target = c.moveX * WALK_SPEED * (if (wet) 0.55f else 1f)
        val accel = if (grounded) 2600f else 1000f
        vx = approach(vx, target, accel * dt)
        if (c.moveX > 0.1f) facing = 1 else if (c.moveX < -0.1f) facing = -1

        // Jumping, with a short input buffer and coyote time so it feels forgiving
        jumpBuffer = if (c.jumpPressed) 0.12f else jumpBuffer - dt
        coyote = if (grounded) 0.1f else coyote - dt
        if (jumpBuffer > 0f && coyote > 0f) {
            vy = JUMP_SPEED * (if (wet) 0.8f else 1f)
            grounded = false
            jumpBuffer = 0f
            coyote = 0f
        }

        vy -= gravity * (if (wet) 0.5f else 1f) * dt
        if (wet && vy < -200f) vy = -200f

        // Move sideways; refuse to climb faces steeper than MAX_SLOPE
        val nx = x + vx * dt
        val climb = terrain.height(nx) - y
        val allowed = if (grounded) abs(vx * dt) * MAX_SLOPE + 1.5f else 3f
        if (climb > allowed) vx = 0f else x = nx

        // Move vertically and collide with the ground
        val wasGrounded = grounded
        y += vy * dt
        val ground = terrain.height(x)
        if (y <= ground || (wasGrounded && vy <= 0f && y - ground < SNAP_DOWN)) {
            y = ground
            vy = 0f
            grounded = true
        } else {
            grounded = false
        }

        if (grounded && abs(vx) > 5f) walkTime += dt * (abs(vx) / WALK_SPEED)
    }

    private fun approach(v: Float, target: Float, maxDelta: Float): Float =
        if (v < target) minOf(v + maxDelta, target) else maxOf(v - maxDelta, target)

    companion object {
        const val WALK_SPEED = 230f
        const val JUMP_SPEED = 480f
        const val MAX_SLOPE = 1.3f
        const val SNAP_DOWN = 8f
        const val HEIGHT = 36f
    }
}
