package com.mobeen.space

import kotlin.math.abs
import kotlin.random.Random

/** How a landmark is drawn. */
enum class LandmarkShape { WRECK, MONOLITH, BONES, DOME }

/**
 * Something worth scanning on a planet's surface. [index] is its number on that planet; the landmark is
 * always the last one. Positions are along the planet's loop (see [Terrain.WIDTH]).
 */
class Signal(
    val planetId: Long,
    val index: Int,
    val name: String,
    val lore: String,
    val x: Float,
    val reward: Int,
    val landmark: Boolean,
    val style: Int,
    val shape: LandmarkShape = LandmarkShape.MONOLITH,
) {
    /** The key used to remember that this signal has been scanned. */
    val key: String get() = "$planetId:$index"
}

/** The signals on each planet, generated from the planet itself so they are always the same. */
object Discoveries {
    const val SIGNALS_PER_PLANET = 5
    const val SCAN_RANGE = 60f
    private const val PAD_CLEARANCE = 700f
    private const val MIN_SPACING = 900f

    private class Kind(val name: String, val lore: String)

    private val kinds: Map<PlanetType, List<Kind>> = mapOf(
        PlanetType.DESERT to listOf(
            Kind("Sand-glass formation", "Lightning fused the dunes into glass ribbons that sing in the wind."),
            Kind("Bleached ribcage", "Something enormous died here long ago. The sand has picked it clean."),
            Kind("Cactus bloom", "A single flower, open for one night in a decade. You logged it."),
        ),
        PlanetType.ICE to listOf(
            Kind("Frozen bubble field", "Gas trapped mid-rise, sealed in clear ice like a held breath."),
            Kind("Blue crystal spire", "It hums faintly when the wind passes through it."),
            Kind("Snow-moth colony", "Thousands of pale wings, all turning to face your lamp at once."),
        ),
        PlanetType.OCEAN to listOf(
            Kind("Tidepool cluster", "Tiny glowing things retreat under the rocks as your shadow falls."),
            Kind("Coral fan", "Grown into the shape of a hand, reaching for the shore."),
            Kind("Drifting jellies", "A slow bloom of light, pulsing in time with the waves."),
        ),
        PlanetType.ROCKY to listOf(
            Kind("Mineral vein", "Rich ore, bright enough to read your scanner by."),
            Kind("Fossil bed", "Shells and spines from a sea that dried up an age ago."),
            Kind("Wind-carved arch", "The wind has spent a million years on this one stone."),
        ),
        PlanetType.TOXIC to listOf(
            Kind("Glowcap ring", "A perfect circle of luminous mushrooms. Nothing grows inside it."),
            Kind("Spore chimney", "It exhales a yellow cloud every few minutes, like breathing."),
            Kind("Acid-etched bones", "Whatever this was, the rain has been eating it for years."),
        ),
        PlanetType.LAVA to listOf(
            Kind("Obsidian shard field", "Black glass, sharp enough to cut a boot sole."),
            Kind("Fire-bloom vent", "Flame-coloured growths that seem to feed on the heat."),
            Kind("Magma-glass pool", "A crust of cooling glass over something still glowing."),
        ),
    )

    private class Mark(val name: String, val lore: String, val shape: LandmarkShape)

    private val landmarks: Map<PlanetType, List<Mark>> = mapOf(
        PlanetType.DESERT to listOf(
            Mark("Half-buried freighter", "Its cargo bay still holds crates stamped with a company that no longer exists.", LandmarkShape.WRECK),
            Mark("Sunken temple", "Steps lead down into the sand. Someone built this to be found.", LandmarkShape.MONOLITH),
        ),
        PlanetType.ICE to listOf(
            Mark("Frozen leviathan", "A creature the size of a ship, caught in the ice mid-swim.", LandmarkShape.BONES),
            Mark("Ice-locked research dome", "The last log entry is a single word: 'listening'.", LandmarkShape.DOME),
        ),
        PlanetType.OCEAN to listOf(
            Mark("Wrecked submarine", "It ran aground and never left. The hatch is welded shut from inside.", LandmarkShape.WRECK),
            Mark("Lighthouse ruin", "Its lamp still turns on some nights, with no power at all.", LandmarkShape.MONOLITH),
        ),
        PlanetType.ROCKY to listOf(
            Mark("Ancient monolith", "Perfectly smooth, and warm to the touch in the cold.", LandmarkShape.MONOLITH),
            Mark("Crashed scout ship", "The pilot's seat is empty. The harness was cut, not unbuckled.", LandmarkShape.WRECK),
        ),
        PlanetType.TOXIC to listOf(
            Mark("Bio-reactor husk", "It still bubbles. Something is still being grown inside.", LandmarkShape.DOME),
            Mark("Giant fungal tower", "It has grown around an older building. You can see a door, high up.", LandmarkShape.BONES),
        ),
        PlanetType.LAVA to listOf(
            Mark("Molten forge ruin", "Anvils as tall as a house, and a hammer no person could lift.", LandmarkShape.DOME),
            Mark("Petrified titan", "Kneeling, head bowed, as if it was praying when the fire came.", LandmarkShape.BONES),
        ),
    )

    /** The landmark and smaller signals on [planet]. Away from the landing pad and the ruins entrance. */
    fun signalsFor(planet: Planet): List<Signal> {
        val rnd = Random(planet.id xor 0x51A11L)
        val pool = kinds.getValue(planet.type)
        val out = ArrayList<Signal>()
        val taken = ArrayList<Float>()
        fun clearOfPads(x: Float) =
            minOf(x, Terrain.WIDTH - x) > PAD_CLEARANCE && abs(x - Terrain.ENTRANCE_X) > PAD_CLEARANCE
        fun spaced(x: Float) = taken.all { abs(it - x) > MIN_SPACING }

        var i = 0
        var attempts = 0
        while (i < SIGNALS_PER_PLANET - 1 && attempts++ < 400) {
            val x = 1400f + rnd.nextFloat() * (Terrain.WIDTH - 2800f)
            if (!clearOfPads(x) || !spaced(x)) continue
            val k = pool[rnd.nextInt(pool.size)]
            out.add(Signal(planet.id, i, k.name, k.lore, x, 15 + rnd.nextInt(0, 21), landmark = false, style = pool.indexOf(k)))
            taken.add(x)
            i++
        }
        // The landmark stands roughly on the far side of the planet from the ship.
        val mark = landmarks.getValue(planet.type).let { it[rnd.nextInt(it.size)] }
        var lx = Terrain.WIDTH * 0.5f + (rnd.nextFloat() - 0.5f) * 4000f
        while (!spaced(lx)) lx += MIN_SPACING
        out.add(Signal(planet.id, out.size, mark.name, mark.lore, lx, 80, landmark = true, style = 0, shape = mark.shape))
        return out
    }

    /** The nearest unscanned signal to x on [planet] (allowing for the planet's loop), or null if all are scanned. */
    fun nearestUnscanned(signals: List<Signal>, scanned: Set<String>, x: Float): Signal? =
        signals.filter { it.key !in scanned }.minByOrNull { abs(loopOffset(it.x, x)) }

    /** Signed distance from [from] to [x], the short way around the planet. */
    fun loopOffset(x: Float, from: Float): Float {
        var d = (x - from) % Terrain.WIDTH
        if (d > Terrain.WIDTH / 2f) d -= Terrain.WIDTH
        if (d < -Terrain.WIDTH / 2f) d += Terrain.WIDTH
        return d
    }
}
