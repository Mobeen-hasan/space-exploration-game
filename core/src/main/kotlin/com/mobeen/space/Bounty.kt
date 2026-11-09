package com.mobeen.space

import kotlin.math.hypot
import kotlin.random.Random

/**
 * A job to bring in one named target hiding in the ruins of one planet.
 * [tier] 1 to 3 sets how tough the target is and how well it pays.
 */
class Contract(
    val id: Long,
    val target: String,
    val crime: String,
    val planetId: Long,
    val planetName: String,
    val planetType: PlanetType,
    val distance: Int,
    val reward: Int,
    val tier: Int,
) {
    /** One line for lists. */
    fun summary(): String = "$target  -  $planetName  -  $reward cr  -  Tier $tier"

    fun encode(): String = listOf(id, target, crime, planetId, planetName, planetType.name, distance, reward, tier).joinToString("|")

    companion object {
        /** Reads what [encode] wrote, or returns null if it is damaged. */
        fun decode(s: String): Contract? {
            val p = s.split("|")
            if (p.size != 9) return null
            return try {
                Contract(
                    p[0].toLong(), p[1], p[2], p[3].toLong(), p[4], PlanetType.valueOf(p[5]),
                    p[6].toInt(), p[7].toInt(), p[8].toInt(),
                )
            } catch (e: IllegalArgumentException) { // bad number or unknown planet type
                null
            }
        }

        /** The target's health multiplier for a tier. */
        fun hpScale(tier: Int): Float = when (tier) { 1 -> 1.0f; 2 -> 1.15f; else -> 1.3f }

        /** The target's damage multiplier for a tier. */
        fun dmgScale(tier: Int): Float = when (tier) { 1 -> 1.0f; 2 -> 1.08f; else -> 1.15f }
    }
}

/** The contracts on offer at the ship terminal, and the one the player has taken. */
class BountyBoard {
    val available = ArrayList<Contract>()
    var active: Contract? = null
        private set
    var boardDay = 0
        private set
    var completedCount = 0
        private set

    /** Refreshes the list when a new day has begun (or while it is empty, in case planets are now in range). */
    fun refreshIfNeeded(state: GameState) {
        val day = state.stats.day
        if (boardDay == day && available.isNotEmpty()) return
        boardDay = day
        available.clear()
        available.addAll(BountyGenerator.generate(state, day, exclude = active?.planetId))
    }

    fun accept(c: Contract): Boolean {
        if (active != null || c !in available) return false
        active = c
        available.remove(c)
        return true
    }

    /** Gives up the current contract. It does not come back. */
    fun abandon() {
        active = null
    }

    fun completeActive(c: Contract) {
        if (active?.id == c.id) {
            active = null
            completedCount++
        }
    }

    fun encodeBoard(): String = available.joinToString(";") { it.encode() }

    fun restore(day: Int, done: Int, activeText: String, boardText: String) {
        boardDay = day
        completedCount = done
        active = if (activeText.isBlank()) null else Contract.decode(activeText)
        available.clear()
        boardText.split(";").filter { it.isNotBlank() }.mapNotNullTo(available) { Contract.decode(it) }
    }
}

object BountyGenerator {
    private val first = listOf("Vex", "Kade", "Mara", "Joss", "Rook", "Ilya", "Tavi", "Brin", "Orla", "Dax")
    private val epithet = listOf("Two-Step", "Sunburn", "Quiet", "Lucky", "Ghost", "Cinder", "Halfmoon", "Tin")
    private val last = listOf("Morrow", "Vale", "Okoro", "Strand", "Kestrel", "Ruiz", "Haldane", "Pike")
    private val crimes = listOf(
        "stole a cargo of reactor cores",
        "broke out of a transit prison",
        "skipped a ten-year debt to the Syndicate",
        "sold forged navigation charts",
        "hijacked a mining barge",
        "ran a smuggling ring through three systems",
    )

    /** Extra pay for worlds that are harder on the player. */
    fun hazardBonus(type: PlanetType): Int = when (type) {
        PlanetType.LAVA -> 100
        PlanetType.TOXIC -> 60
        PlanetType.ICE -> 60
        PlanetType.DESERT -> 30
        PlanetType.ROCKY -> 20
        PlanetType.OCEAN -> 0
    }

    fun reward(tier: Int, distance: Int, type: PlanetType): Int {
        val base = 250 + (tier - 1) * 200
        val raw = base + distance / 25 + hazardBonus(type)
        return (raw / 5) * 5
    }

    /** Up to [count] contracts on distinct planets near the ship. The same day and place give the same list. */
    fun generate(state: GameState, day: Int, count: Int = 4, exclude: Long? = null): List<Contract> {
        val ship = state.ship
        val chunk = PlanetGenerator.CHUNK
        val seed = day * 7919L + (ship.pos.x / chunk).toInt() * 31L + (ship.pos.y / chunk).toInt()
        val rnd = Random(seed)

        val nearby = ArrayList<Planet>()
        state.field.near(ship.pos.x, ship.pos.y, 7000f, nearby)
        if (nearby.size < 2) state.field.near(ship.pos.x, ship.pos.y, 12000f, nearby)
        val candidates = nearby.filter { it.id != exclude }.shuffled(rnd).take(count)

        return candidates.map { p ->
            val tier = pickTier(rnd)
            val distance = hypot(p.x - ship.pos.x, p.y - ship.pos.y).toInt()
            Contract(
                id = rnd.nextLong() and Long.MAX_VALUE,
                target = "${first.random(rnd)} '${epithet.random(rnd)}' ${last.random(rnd)}",
                crime = crimes.random(rnd),
                planetId = p.id,
                planetName = p.name,
                planetType = p.type,
                distance = distance,
                reward = reward(tier, distance, p.type),
                tier = tier,
            )
        }
    }

    private fun pickTier(rnd: Random): Int {
        val roll = rnd.nextInt(100)
        return when {
            roll < 50 -> 1
            roll < 85 -> 2
            else -> 3
        }
    }
}
