package com.mobeen.space

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/** The buildings in every town, left to right, by their distance from the town's centre. */
enum class Building(val label: String, val offset: Float) {
    BAR("Bar", -360f),
    TRADER("Trader", -120f),
    CLINIC("Clinic", 120f),
    DEPOT("Fuel depot", 360f),
}

/** Someone standing about in town with a few things to say. [look] picks their colours. */
class Townsperson(val name: String, val role: String, val lines: List<String>, val offset: Float, val look: Int)

class Town(val planetId: Long, val name: String, val folk: List<Townsperson>)

/**
 * Settlements on about a third of planets (always on the world nearest the start). Everything about a town
 * comes from its planet, so it is the same every visit.
 */
object Towns {
    /** Where a town stands on its planet's loop, on its own flat ground. */
    const val TOWN_X = 6500f
    const val HALF_WIDTH = 520f
    const val CHANCE = 0.35f
    const val USE_RANGE = 50f

    private val firstParts = listOf("Rust", "Last", "Dust", "Cold", "Lantern", "Hollow", "Iron", "Salt", "Ember", "Quiet")
    private val lastParts = listOf("water", "light", "haven", "reach", "gate", "fall", "rest", "post", "well", "rock")

    private class Role(val role: String, val lines: List<String>)

    private val roles = listOf(
        Role("Old prospector", listOf(
            "Been digging this rock forty years. Never found what I was looking for. Found plenty I wasn't.",
            "The ruins? Don't go down there without a light. Don't come back up without your mind.",
            "Fossils, crystals, bones... scan 'em all. The data buyers never ask why.",
        )),
        Role("Dock worker", listOf(
            "Fuel's cheaper here than in your ship's tanks, if you're asking.",
            "Saw a hunter come back from the ruins last week. Carried out a captive and a broken leg.",
            "Keep a splint in your locker. Trust me.",
        )),
        Role("Drifter", listOf(
            "Every world's got a landmark if you walk far enough. Most of 'em are graves.",
            "I don't sleep near the ruins. Things whisper.",
            "The bar's got rumors, if you've got credits. Rumors are cheaper than maps.",
        )),
        Role("Mechanic", listOf(
            "Hull's a lot cheaper to patch here than out in the black.",
            "That baton of yours? Get something heavier before you go deep.",
            "Brutes break arms. Aim for theirs first.",
        )),
        Role("Preacher", listOf(
            "The shrines down there answer prayers. Not always the way you'd like.",
            "Light a lantern, and keep it lit. The dark has teeth.",
            "We are all lost out here. Some of us just carry better maps.",
        )),
        Role("Kid", listOf(
            "Are you a bounty hunter? Have you caught anyone? Was it scary?",
            "My mum says not to drink from the black vials.",
            "I saw a light rising out of the ground past the hills! That's where the ruins are.",
        )),
    )

    private val givenNames = listOf("Mara", "Joss", "Tavi", "Brin", "Orla", "Dex", "Ilse", "Corin", "Wren", "Sato")

    private fun rnd(planet: Planet) = Random(planet.id xor 0x70E7L)

    fun hasTown(planet: Planet): Boolean = planet.id == 0L || rnd(planet).nextFloat() < CHANCE

    fun townFor(planet: Planet): Town? {
        if (!hasTown(planet)) return null
        val r = rnd(planet)
        r.nextFloat() // the roll used by hasTown
        val name = firstParts[r.nextInt(firstParts.size)] + lastParts[r.nextInt(lastParts.size)]
        val folk = listOf(-240f, 0f, 240f).mapIndexed { i, offset ->
            val role = roles[r.nextInt(roles.size)]
            Townsperson(givenNames[r.nextInt(givenNames.size)], role.role, role.lines, offset, i)
        }
        return Town(planet.id, name, folk)
    }

    /** Signed distance from x to the nearest town centre, around the planet's loop. */
    fun townOffset(x: Float): Float = x - TOWN_X - Math.round((x - TOWN_X) / Terrain.WIDTH) * Terrain.WIDTH
}

/** What the town's buildings offer. Pure rules on the game state, so they can be tested without a screen. */
object TownServices {
    const val DRINK_COST = 10
    const val DRINK_CALM = 20f
    const val RUMOR_COST = 5

    // ------------------------------------------------------------------ bar

    fun buyDrink(state: GameState): Boolean {
        if (state.stats.credits < DRINK_COST) return false
        state.stats.credits -= DRINK_COST
        state.stats.mind = min(Stats.MAX, state.stats.mind + DRINK_CALM)
        return true
    }

    /**
     * Pay for a rumor. It may chart nearby worlds on your map, point you to another town, or tip you off about
     * your bounty target; or it may just be a story. Returns what you heard, or null if you could not pay.
     */
    fun hearRumor(state: GameState, here: Planet, rnd: Random): String? {
        if (state.stats.credits < RUMOR_COST) return null
        state.stats.credits -= RUMOR_COST
        val nearby = ArrayList<Planet>()
        state.field.near(here.x, here.y, 24000f, nearby)
        nearby.removeAll { it.id == here.id }
        val roll = rnd.nextFloat()
        val target = state.bounties.hunting
        if (roll < 0.15f && target != null) {
            state.charted.add(target.planetId)
            return "\"${target.target}? Heard they went to ground on ${target.planetName}. Down in the old ruins.\""
        }
        if (roll < 0.55f) {
            val unknown = nearby.filter { it.id !in state.charted }.sortedBy { hypot(it.x - here.x, it.y - here.y) }.take(3)
            if (unknown.isNotEmpty()) {
                unknown.forEach { state.charted.add(it.id) }
                return "A pilot marks ${unknown.size} world${if (unknown.size == 1) "" else "s"} on your star map: " +
                    unknown.joinToString(", ") { it.name } + "."
            }
        }
        if (roll < 0.8f) {
            val town = nearby.filter { Towns.hasTown(it) }.minByOrNull { hypot(it.x - here.x, it.y - here.y) }
            if (town != null) {
                state.charted.add(town.id)
                return "\"There's another settlement, ${Towns.townFor(town)!!.name}, out on ${town.name}. Decent folk.\""
            }
        }
        return stories[rnd.nextInt(stories.size)]
    }

    private val stories = listOf(
        "\"They say the black pools have no bottom. They say a lot of things.\"",
        "\"A hunter went into the ruins with three lanterns. Came out with none, and wouldn't say why.\"",
        "\"The monoliths are warm. All of them. On every world. Think about that.\"",
        "\"Don't rob the scavengers. They remember faces.\"",
    )

    // ------------------------------------------------------------------ trader

    private fun basePrice(kind: GearKind): Int = when (kind) {
        GearKind.STUN_BATON -> 30
        GearKind.SHIP_VEST -> 20
        GearKind.SHOCK_KNIFE -> 70
        GearKind.PIPE_CLUB -> 80
        GearKind.ARC_SABER -> 140
        GearKind.SCRAP_PLATING -> 70
        GearKind.COMBAT_WEAVE -> 140
        GearKind.HAZARD_SUIT -> 120
        GearKind.RIOT_SHIELD -> 90
        GearKind.ARC_BUCKLER -> 120
    }

    private fun qualityFactor(q: Quality): Float = when (q) {
        Quality.WORN -> 0.5f
        Quality.STANDARD -> 1f
        Quality.FINE -> 1.8f
        Quality.RARE -> 3f
    }

    fun price(g: Gear): Int = (basePrice(g.kind) * qualityFactor(g.quality)).toInt()

    fun sellPrice(g: Gear): Int = (price(g) * 0.4f).toInt()

    /** Three pieces for sale. The stock changes every day. */
    fun stock(planet: Planet, day: Int): List<Gear> {
        val r = Random(planet.id * 31 + day * 7919L)
        val kinds = GearKind.entries.filter { !it.starter }
        return List(3) {
            val q = when (r.nextInt(10)) { 0, 1 -> Quality.WORN; 8 -> Quality.FINE; 9 -> if (r.nextBoolean()) Quality.FINE else Quality.RARE; else -> Quality.STANDARD }
            Gear(kinds[r.nextInt(kinds.size)], q)
        }
    }

    fun buy(state: GameState, g: Gear): Boolean {
        val p = price(g)
        if (state.stats.credits < p || state.loadout.bag.size >= Loadout.BAG_SIZE) return false
        state.stats.credits -= p
        state.loadout.bag.add(g)
        return true
    }

    fun sell(state: GameState, bagIndex: Int): Int {
        val g = state.loadout.bag.getOrNull(bagIndex) ?: return 0
        state.loadout.bag.removeAt(bagIndex)
        val p = sellPrice(g)
        state.stats.credits += p
        return p
    }

    // ------------------------------------------------------------------ clinic

    fun treatmentCost(s: Stats): Int {
        val injuries = listOf(s.bleeding > 0f, s.infected, s.brokenArm, s.brokenLeg).count { it }
        return ceil((Stats.MAX - s.health) * 1.5f).toInt() + injuries * 20
    }

    /** Heal to full and treat every injury. */
    fun treat(state: GameState): Boolean {
        val cost = treatmentCost(state.stats)
        if (cost <= 0 || state.stats.credits < cost) return false
        state.stats.credits -= cost
        state.stats.health = Stats.MAX
        state.stats.clearInjuries()
        return true
    }

    // ------------------------------------------------------------------ fuel depot

    const val FUEL_RATE = 0.6f // credits per percent: cheaper than the ship terminal
    const val HULL_RATE = 0.8f

    fun fuelCost(s: Stats): Int = ceil((Stats.MAX - s.fuel) * FUEL_RATE).toInt()
    fun hullCost(s: Stats): Int = ceil((Stats.MAX - s.hull) * HULL_RATE).toInt()

    /** Fill up as far as your credits go. Returns the percent added. */
    fun refuel(state: GameState): Float = topUp(state, state.stats.fuel, FUEL_RATE) { state.stats.fuel += it }

    fun repair(state: GameState): Float = topUp(state, state.stats.hull, HULL_RATE) { state.stats.hull += it }

    private fun topUp(state: GameState, current: Float, rate: Float, add: (Float) -> Unit): Float {
        val missing = Stats.MAX - current
        if (missing <= 0f) return 0f
        val affordable = min(missing, state.stats.credits / rate)
        if (affordable < 1f) return 0f
        val cost = ceil(affordable * rate).toInt()
        state.stats.credits -= cost
        add(affordable)
        state.stats.clamp()
        return affordable
    }
}
