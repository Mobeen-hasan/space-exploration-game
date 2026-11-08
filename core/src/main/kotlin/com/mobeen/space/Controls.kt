package com.mobeen.space

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.math.Vector2
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Unified input: keyboard on desktop, floating joystick + buttons on touch screens
 * (a mouse acts like a finger, so the touch controls can be tested on desktop).
 *
 *  - Left side of the screen: floating joystick (aims the ship in space, walks on a surface).
 *  - Bottom-right: main button (THRUST / JUMP), a smaller BRAKE button, and a context ACTION button.
 *
 * Screens configure labels and which buttons exist ([thrustLabel], [showBrake],
 * [actionEnabled], [actionLabel]) before calling [update].
 */
class Controls {
    /** +1 = turn left, -1 = turn right (keyboard, space flight). */
    var turn = 0f
        private set

    /** -1..1 horizontal walking input (keyboard or joystick), positive = right. */
    var moveX = 0f
        private set

    /** 0..1 engine power. */
    var thrust = 0f
        private set
    var brake = false
        private set

    var jumpHeld = false
        private set
    var jumpPressed = false
        private set

    /** True for exactly one update when the action button / E key is pressed. */
    var actionPressed = false
        private set

    /** Desired heading in radians when the joystick is held, otherwise null. */
    var targetAngle: Float? = null
        private set

    // Per-screen configuration.
    var thrustLabel = "THRUST"
    var showBrake = true
    var showThrust = true
    var actionEnabled = false
    var actionLabel = ""

    // Layout and state, read by TouchHud to draw the on-screen controls.
    val thrustCenter = Vector2()
    var thrustRadius = 0f
        private set
    val brakeCenter = Vector2()
    var brakeRadius = 0f
        private set
    val actionCenter = Vector2()
    var actionRadius = 0f
        private set
    var joyRadius = 0f
        private set
    val joyOrigin = Vector2()
    val joyPos = Vector2()
    var joyActive = false
        private set
    var thrustHeld = false
        private set
    var brakeHeld = false
        private set
    var actionHeld = false
        private set

    private val owner = IntArray(MAX_POINTERS)
    private var prevJump = false
    private var prevActionHeld = false

    fun update(w: Float, h: Float) {
        layout(w, h)

        // Keyboard
        val input = Gdx.input
        var kTurn = 0f
        if (input.isKeyPressed(Input.Keys.A) || input.isKeyPressed(Input.Keys.LEFT)) kTurn += 1f
        if (input.isKeyPressed(Input.Keys.D) || input.isKeyPressed(Input.Keys.RIGHT)) kTurn -= 1f
        val kThrust = input.isKeyPressed(Input.Keys.W) || input.isKeyPressed(Input.Keys.UP)
        val kBrake = input.isKeyPressed(Input.Keys.S) || input.isKeyPressed(Input.Keys.DOWN) ||
            input.isKeyPressed(Input.Keys.SPACE)
        val kJump = kThrust || input.isKeyPressed(Input.Keys.SPACE)

        // Touch / mouse
        thrustHeld = false
        brakeHeld = false
        actionHeld = false
        joyActive = false
        for (i in 0 until MAX_POINTERS) {
            if (!input.isTouched(i)) {
                owner[i] = NONE
                continue
            }
            val x = input.getX(i).toFloat()
            val y = h - input.getY(i).toFloat()
            if (owner[i] == NONE) {
                owner[i] = when {
                    showThrust && inside(x, y, thrustCenter, thrustRadius * 1.15f) -> THRUST
                    showBrake && inside(x, y, brakeCenter, brakeRadius * 1.15f) -> BRAKE
                    actionEnabled && inside(x, y, actionCenter, actionRadius * 1.2f) -> ACTION
                    x < w * 0.55f -> {
                        joyOrigin.set(x, y)
                        JOY
                    }
                    else -> SKIP
                }
            }
            when (owner[i]) {
                THRUST -> thrustHeld = true
                BRAKE -> brakeHeld = true
                ACTION -> actionHeld = true
                JOY -> {
                    joyActive = true
                    joyPos.set(x, y)
                }
            }
        }

        targetAngle = null
        var joyMove = 0f
        if (joyActive) {
            val dx = joyPos.x - joyOrigin.x
            val dy = joyPos.y - joyOrigin.y
            if (hypot(dx, dy) > joyRadius * 0.15f) targetAngle = atan2(dy, dx)
            val m = (dx / (joyRadius * 0.7f)).coerceIn(-1f, 1f)
            if (abs(m) > 0.2f) joyMove = m
        }

        turn = kTurn
        thrust = if (kThrust || thrustHeld) 1f else 0f
        brake = kBrake || brakeHeld

        var kMove = 0f
        if (input.isKeyPressed(Input.Keys.A) || input.isKeyPressed(Input.Keys.LEFT)) kMove -= 1f
        if (input.isKeyPressed(Input.Keys.D) || input.isKeyPressed(Input.Keys.RIGHT)) kMove += 1f
        moveX = if (kMove != 0f) kMove else joyMove

        jumpHeld = kJump || thrustHeld
        jumpPressed = jumpHeld && !prevJump
        prevJump = jumpHeld

        actionPressed = actionEnabled &&
            (input.isKeyJustPressed(Input.Keys.E) || (actionHeld && !prevActionHeld))
        prevActionHeld = actionHeld
    }

    /** Joystick deflection 0..1, for drawing. */
    fun joyDeflection(): Float =
        if (!joyActive) 0f else min(hypot(joyPos.x - joyOrigin.x, joyPos.y - joyOrigin.y) / joyRadius, 1f)

    private fun layout(w: Float, h: Float) {
        val s = min(w, h)
        thrustRadius = s * 0.11f
        brakeRadius = s * 0.065f
        actionRadius = s * 0.075f
        joyRadius = s * 0.12f
        thrustCenter.set(w - s * 0.17f, s * 0.2f)
        brakeCenter.set(thrustCenter.x - thrustRadius - brakeRadius * 1.7f, thrustCenter.y - thrustRadius * 0.2f)
        actionCenter.set(thrustCenter.x, thrustCenter.y + thrustRadius + actionRadius * 1.4f)
    }

    private fun inside(x: Float, y: Float, c: Vector2, r: Float) = hypot(x - c.x, y - c.y) <= r

    private companion object {
        const val MAX_POINTERS = 10
        const val NONE = 0
        const val JOY = 1
        const val THRUST = 2
        const val BRAKE = 3
        const val SKIP = 4
        const val ACTION = 5
    }
}
