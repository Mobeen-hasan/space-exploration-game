package com.mobeen.space

import kotlin.math.max
import kotlin.math.min

/** How dangerous a planet is to stand on. Numbers are per real-time second. */
object Hazards {
    /** Suit air lost per second outside. Zero on worlds with breathable air. */
    fun airDrain(type: PlanetType): Float = when (type) {
        PlanetType.OCEAN -> 0f
        PlanetType.ROCKY -> 0.35f
        PlanetType.DESERT -> 0.40f
        PlanetType.ICE -> 0.50f
        PlanetType.TOXIC -> 0.80f
        PlanetType.LAVA -> 0.90f
    }

    const val COMFORT_MIN_C = -40
    const val COMFORT_MAX_C = 45

    /**
     * Health lost per second from heat or cold. Zero inside the comfortable range; just outside it
     * the damage already starts at 0.25/s (more than natural regeneration, so you really do lose
     * health) and grows to 1.0/s for the most extreme worlds.
     */
    fun exposureDamage(tempC: Int): Float {
        val deviation = when {
            tempC > COMFORT_MAX_C -> tempC - COMFORT_MAX_C
            tempC < COMFORT_MIN_C -> COMFORT_MIN_C - tempC
            else -> return 0f
        }
        return min(1f, 0.25f + deviation / 150f * 0.75f)
    }

    const val LAVA_DPS = 25f
    const val ACID_DPS = 12f
}

/** The player's body, supplies and ship condition, plus money. All meters run 0..100. */
class Stats {
    var health = MAX
    var energy = MAX
    var food = MAX
    var air = MAX
    var fuel = MAX
    var hull = MAX
    var credits = START_CREDITS
    var day = 1

    val collapsed: Boolean get() = health <= 0f || energy <= 0f

    /** Set while something is hurting the player, so health does not regenerate through it. */
    private var regenBlocked = false

    /** Slow background drain from being awake and hungry. Call once per frame in every screen. */
    fun tickBody(dt: Float) {
        energy -= ENERGY_DRAIN * dt
        food -= FOOD_DRAIN * dt
        if (food <= 0f) {
            health -= STARVE_DPS * dt
        } else if (!regenBlocked && health < MAX && food > 50f && energy > 30f) {
            health += REGEN * dt
        }
        regenBlocked = false // set again by tickSurface this frame if the player is still being hurt
        clamp()
    }

    /** Extra drains while standing on a planet surface. */
    fun tickSurface(dt: Float, planet: Planet, liquid: Liquid, submerged: Boolean) {
        val drain = Hazards.airDrain(planet.type)
        if (drain > 0f) {
            air -= drain * dt
            if (air <= 0f) health -= SUFFOCATE_DPS * dt
        } else {
            air += AIR_REFILL * dt
        }
        val exposure = Hazards.exposureDamage(planet.temperatureC)
        health -= exposure * dt
        var burning = false
        if (submerged) {
            when (liquid) {
                Liquid.LAVA -> { health -= Hazards.LAVA_DPS * dt; burning = true }
                Liquid.ACID -> { health -= Hazards.ACID_DPS * dt; burning = true }
                else -> {}
            }
        }
        if (exposure > 0f || burning || air <= 0f) regenBlocked = true
        clamp()
    }

    /** True while suit air is gone and the player is taking damage for it. */
    val suffocating: Boolean get() = air <= 0f

    /** What knocked the player out. Call only when [collapsed]. */
    fun collapseReason(): String =
        if (health <= 0f) "You collapsed from your injuries." else "You passed out from exhaustion."

    /** Apply the penalty for collapsing and return the message to show on waking. */
    fun applyCollapse(): String {
        val reason = collapseReason()
        val lost = (credits * COLLAPSE_CREDIT_LOSS).toInt()
        credits -= lost
        health = max(health, 40f)
        energy = max(energy, 40f)
        food = max(food, 30f)
        air = MAX
        return "$reason You woke up in the ship and lost $lost credits."
    }

    fun refillAir() {
        air = MAX
    }

    fun sleep() {
        energy = MAX
        food = max(0f, food - 15f)
        health = min(MAX, health + 30f)
        air = MAX
        day += 1
    }

    fun eat(): Boolean {
        if (credits < RATION_COST || food >= MAX) return false
        credits -= RATION_COST
        food = min(MAX, food + 40f)
        return true
    }

    fun refuel(): Boolean {
        if (credits < FUEL_COST || fuel >= MAX) return false
        credits -= FUEL_COST
        fuel = min(MAX, fuel + 25f)
        return true
    }

    fun repair(): Boolean {
        if (credits < REPAIR_COST || hull >= MAX) return false
        credits -= REPAIR_COST
        hull = min(MAX, hull + 25f)
        return true
    }

    /** A free lifeline so a broke, empty ship is never stuck for good. */
    fun emergencyFuel(): Boolean {
        if (fuel >= EMERGENCY_THRESHOLD) return false
        fuel = EMERGENCY_FUEL
        return true
    }

    fun clamp() {
        health = health.coerceIn(0f, MAX)
        energy = energy.coerceIn(0f, MAX)
        food = food.coerceIn(0f, MAX)
        air = air.coerceIn(0f, MAX)
        fuel = fuel.coerceIn(0f, MAX)
        hull = hull.coerceIn(0f, MAX)
        if (credits < 0) credits = 0
    }

    companion object {
        const val MAX = 100f
        const val START_CREDITS = 300

        const val ENERGY_DRAIN = 0.08f // about 21 minutes from full to empty
        const val FOOD_DRAIN = 0.04f
        const val STARVE_DPS = 0.5f
        const val REGEN = 0.15f
        const val SUFFOCATE_DPS = 4f
        const val AIR_REFILL = 5f
        const val COLLAPSE_CREDIT_LOSS = 0.2f

        const val RATION_COST = 5
        const val FUEL_COST = 25
        const val REPAIR_COST = 30
        const val EMERGENCY_THRESHOLD = 5f
        const val EMERGENCY_FUEL = 10f

        const val FUEL_BURN = 0.35f // per second at full thrust
    }
}
