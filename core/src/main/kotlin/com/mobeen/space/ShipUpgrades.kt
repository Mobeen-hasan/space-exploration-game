package com.mobeen.space

/** Improvements to the ship, each with [MAX_LEVEL] levels. Installed by the mechanics at a town's fuel depot. */
enum class Upgrade(val label: String, val blurb: String) {
    ENGINE("Engine tuning", "+20% thrust and +15% top speed per level"),
    INJECTORS("Fuel injectors", "burns 20% less fuel per level"),
    PLATING("Hull plating", "takes 25% less crash damage per level"),
    RADAR("Long-range radar", "+35% radar range per level: see and chart more worlds"),
    LOCKER("Bigger locker", "+2 locker slots per level for supplies"),
}

class ShipUpgrades {
    private val levels = IntArray(Upgrade.entries.size)

    fun level(u: Upgrade): Int = levels[u.ordinal]

    /** The price of the next level, or null when the upgrade is maxed out. */
    fun cost(u: Upgrade): Int? = PRICES.getOrNull(level(u))

    fun buy(state: GameState, u: Upgrade): Boolean {
        val price = cost(u) ?: return false
        if (state.stats.credits < price) return false
        state.stats.credits -= price
        levels[u.ordinal]++
        return true
    }

    val thrustScale: Float get() = 1f + 0.2f * level(Upgrade.ENGINE)
    val speedScale: Float get() = 1f + 0.15f * level(Upgrade.ENGINE)
    val burnScale: Float get() = 1f - 0.2f * level(Upgrade.INJECTORS)
    val damageScale: Float get() = 1f - 0.25f * level(Upgrade.PLATING)
    val radarRange: Float get() = BASE_RADAR_RANGE * (1f + 0.35f * level(Upgrade.RADAR))
    val extraLocker: Int get() = 2 * level(Upgrade.LOCKER)

    fun maxAll() = levels.fill(MAX_LEVEL)

    fun reset() = levels.fill(0)

    fun encode(): String = Upgrade.entries.joinToString(",") { "${it.name}:${level(it)}" }

    fun restore(text: String) {
        reset()
        for (part in text.split(",")) {
            val (name, lv) = part.split(":").takeIf { it.size == 2 } ?: continue
            val u = Upgrade.entries.firstOrNull { it.name == name } ?: continue
            levels[u.ordinal] = (lv.toIntOrNull() ?: 0).coerceIn(0, MAX_LEVEL)
        }
    }

    companion object {
        const val MAX_LEVEL = 3
        val PRICES = listOf(150, 350, 700)
        const val BASE_RADAR_RANGE = 5000f
    }
}
