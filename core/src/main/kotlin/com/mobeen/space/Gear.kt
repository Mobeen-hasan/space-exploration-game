package com.mobeen.space

import kotlin.math.max
import kotlin.random.Random

enum class GearSlot { WEAPON, ARMOR, SHIELD }

/**
 * Every piece of equipment. Weapons set damage, aim and how hard an ambush lands; armor takes damage off
 * every hit; shields can stop blows outright. A hazard suit also shields you from lava and acid.
 */
enum class GearKind(
    val label: String,
    val slot: GearSlot,
    val dmgMin: Int = 0,
    val dmgMax: Int = 0,
    val accuracy: Float = 0f,
    val ambush: Int = 3,
    val armor: Int = 0,
    val meleeBlock: Float = 0f,
    val rangedBlock: Float = 0f,
    val hazardProof: Boolean = false,
    val starter: Boolean = false,
) {
    STUN_BATON("Stun baton", GearSlot.WEAPON, dmgMin = 8, dmgMax = 16, accuracy = 0.92f, ambush = 3, starter = true),
    SHOCK_KNIFE("Shock knife", GearSlot.WEAPON, dmgMin = 6, dmgMax = 12, accuracy = 0.98f, ambush = 4),
    PIPE_CLUB("Pipe club", GearSlot.WEAPON, dmgMin = 12, dmgMax = 22, accuracy = 0.82f, ambush = 3),
    ARC_SABER("Arc saber", GearSlot.WEAPON, dmgMin = 11, dmgMax = 19, accuracy = 0.92f, ambush = 3),

    SHIP_VEST("Ship-issue vest", GearSlot.ARMOR, armor = 1, starter = true),
    SCRAP_PLATING("Scrap plating", GearSlot.ARMOR, armor = 2),
    COMBAT_WEAVE("Combat weave", GearSlot.ARMOR, armor = 3),
    HAZARD_SUIT("Hazard suit", GearSlot.ARMOR, armor = 2, hazardProof = true),

    RIOT_SHIELD("Riot shield", GearSlot.SHIELD, meleeBlock = 0.18f),
    ARC_BUCKLER("Arc buckler", GearSlot.SHIELD, meleeBlock = 0.12f, rangedBlock = 0.15f),
}

/** How well made a piece is. Each step up adds damage, armor or block chance; worn gear is a step down. */
enum class Quality(val label: String, val step: Int) {
    WORN("Worn", -1),
    STANDARD("", 0),
    FINE("Fine", 1),
    RARE("Rare", 2),
}

class Gear(val kind: GearKind, val quality: Quality = Quality.STANDARD) {
    val slot: GearSlot get() = kind.slot

    val name: String get() = if (quality.label.isEmpty()) kind.label else "${quality.label} ${kind.label.lowercase()}"

    val dmgMin: Int get() = max(1, kind.dmgMin + quality.step * 2)
    val dmgMax: Int get() = max(dmgMin + 1, kind.dmgMax + quality.step * 2)
    val accuracy: Float get() = kind.accuracy
    val ambush: Int get() = kind.ambush

    /** Damage taken off every hit. Quality moves it by at most one point either way. */
    val armor: Int get() = if (kind.slot != GearSlot.ARMOR) 0 else max(0, kind.armor + quality.step.coerceIn(-1, 1))

    val meleeBlock: Float get() = if (kind.meleeBlock <= 0f) 0f else max(0f, kind.meleeBlock + 0.04f * quality.step)
    val rangedBlock: Float get() = if (kind.rangedBlock <= 0f) 0f else max(0f, kind.rangedBlock + 0.04f * quality.step)

    /** A short summary of what this piece does. */
    fun describe(): String = when (kind.slot) {
        GearSlot.WEAPON -> "damage $dmgMin-$dmgMax, aim ${(accuracy * 100).toInt()}%, ambush x$ambush"
        GearSlot.ARMOR -> "armor $armor" + if (kind.hazardProof) ", resists lava and acid" else ""
        GearSlot.SHIELD -> "blocks ${(meleeBlock * 100).toInt()}% of blows" +
            if (rangedBlock > 0f) " and ${(rangedBlock * 100).toInt()}% of spit" else ""
    }

    fun encode(): String = "${kind.name}:${quality.name}"

    override fun equals(other: Any?): Boolean = other is Gear && other.kind == kind && other.quality == quality
    override fun hashCode(): Int = kind.hashCode() * 31 + quality.hashCode()
    override fun toString(): String = name

    companion object {
        fun decode(s: String): Gear? {
            val p = s.split(":")
            if (p.size != 2) return null
            return try {
                Gear(GearKind.valueOf(p[0]), Quality.valueOf(p[1]))
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

/**
 * What the player wears and carries between trips. Equipped gear is kept even if you fall in the ruins;
 * spare gear in the [bag] is lost if you die there.
 */
class Loadout {
    var weapon = Gear(GearKind.STUN_BATON)
    var armor = Gear(GearKind.SHIP_VEST)
    var shield: Gear? = null
    val bag = ArrayList<Gear>()

    fun equipped(slot: GearSlot): Gear? = when (slot) {
        GearSlot.WEAPON -> weapon
        GearSlot.ARMOR -> armor
        GearSlot.SHIELD -> shield
    }

    /** Equip the bag item at [index]; whatever it replaces goes into the bag. Returns false if there is none. */
    fun equipFromBag(index: Int): Boolean {
        val g = bag.getOrNull(index) ?: return false
        bag.removeAt(index)
        val old = equipped(g.slot)
        when (g.slot) {
            GearSlot.WEAPON -> weapon = g
            GearSlot.ARMOR -> armor = g
            GearSlot.SHIELD -> shield = g
        }
        if (old != null) bag.add(old)
        return true
    }

    /** Back to the starting baton and vest, with nothing spare. */
    fun reset() {
        weapon = Gear(GearKind.STUN_BATON)
        armor = Gear(GearKind.SHIP_VEST)
        shield = null
        bag.clear()
    }

    fun encode(): String = listOf(weapon.encode(), armor.encode(), shield?.encode() ?: "").joinToString(";") +
        "|" + bag.joinToString(";") { it.encode() }

    /** Loads what [encode] wrote. Damaged text leaves the starting gear in place. */
    fun restore(text: String) {
        val halves = text.split("|")
        val worn = halves.getOrNull(0)?.split(";") ?: return
        worn.getOrNull(0)?.let { Gear.decode(it) }?.takeIf { it.slot == GearSlot.WEAPON }?.let { weapon = it }
        worn.getOrNull(1)?.let { Gear.decode(it) }?.takeIf { it.slot == GearSlot.ARMOR }?.let { armor = it }
        shield = worn.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { Gear.decode(it) }?.takeIf { it.slot == GearSlot.SHIELD }
        bag.clear()
        halves.getOrNull(1)?.split(";")?.filter { it.isNotBlank() }?.mapNotNullTo(bag) { Gear.decode(it) }
        while (bag.size > BAG_SIZE) bag.removeAt(bag.lastIndex)
    }

    companion object {
        const val BAG_SIZE = 4
    }
}

/** What a vial does. Which colour does what is shuffled on every trip, so you learn by drinking. */
enum class VialEffect(val label: String) {
    HEAL("healing"),
    CALM("calming"),
    ENERGY("invigorating"),
    POISON("poison"),
    NIGHTMARE("nightmares"),
}

object Loot {
    /** Deeper floors and harder contracts turn up better-made gear. */
    fun rollQuality(depth: Int, difficulty: Int, rnd: Random): Quality {
        val d = difficulty.coerceIn(0, 3)
        val weights = listOf(
            Quality.WORN to maxOf(5, 35 - depth * 6 - d * 3),
            Quality.STANDARD to 40,
            Quality.FINE to 14 + depth * 4 + d * 3,
            Quality.RARE to 2 + depth * 2 + d * 2,
        )
        var roll = rnd.nextInt(weights.sumOf { it.second })
        for ((q, w) in weights) {
            if (roll < w) return q
            roll -= w
        }
        return Quality.STANDARD
    }

    /** A random piece of gear you could find, never the starting baton or vest. */
    fun rollGear(depth: Int, difficulty: Int, rnd: Random): Gear {
        val kinds = GearKind.entries.filter { !it.starter }
        return Gear(kinds[rnd.nextInt(kinds.size)], rollQuality(depth, difficulty, rnd))
    }

    /** The bounty target always carries something worth taking: fine or better. */
    fun rollTrophy(rnd: Random): Gear {
        val kinds = GearKind.entries.filter { !it.starter }
        return Gear(kinds[rnd.nextInt(kinds.size)], if (rnd.nextFloat() < 0.35f) Quality.RARE else Quality.FINE)
    }
}
