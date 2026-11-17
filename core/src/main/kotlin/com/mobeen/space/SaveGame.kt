package com.mobeen.space

import com.badlogic.gdx.Gdx

/** Turns a [GameState] into plain key/value strings and back. No libGDX needed, so it is testable. */
object SaveCodec {
    private const val VERSION = "1"

    fun encode(s: GameState): Map<String, String> {
        val st = s.stats
        val ship = s.ship
        return mapOf(
            "v" to VERSION,
            "health" to st.health.toString(),
            "energy" to st.energy.toString(),
            "food" to st.food.toString(),
            "air" to st.air.toString(),
            "mind" to st.mind.toString(),
            "bleeding" to st.bleeding.toString(),
            "infected" to st.infected.toString(),
            "brokenArm" to st.brokenArm.toString(),
            "brokenLeg" to st.brokenLeg.toString(),
            "fuel" to st.fuel.toString(),
            "hull" to st.hull.toString(),
            "credits" to st.credits.toString(),
            "day" to st.day.toString(),
            "clock" to s.clock.toString(),
            "discovered" to s.discovered.joinToString(","),
            "charted" to s.charted.joinToString(","),
            "scanned" to s.scanned.joinToString(";"),
            "shipX" to ship.pos.x.toString(),
            "shipY" to ship.pos.y.toString(),
            "shipVX" to ship.vel.x.toString(),
            "shipVY" to ship.vel.y.toString(),
            "shipAngle" to ship.angle.toString(),
            "landedOn" to (ship.landedOn?.id?.toString() ?: ""),
            "landAngle" to ship.landAngleRad.toString(),
            "bountyDay" to s.bounties.boardDay.toString(),
            "bountyDone" to s.bounties.completedCount.toString(),
            "bountyActive" to (s.bounties.active?.encode() ?: ""),
            "bountyBoard" to s.bounties.encodeBoard(),
            "bountyCaptured" to s.bounties.captured.toString(),
            "loadout" to s.loadout.encode(),
            "hardcore" to s.hardcore.toString(),
            "locker" to s.locker.joinToString(",") { it.name },
            "upgrades" to s.upgrades.encode(),
        )
    }

    /** Loads [m] into [s]. Returns false (leaving a fresh game) if the data is missing or damaged. */
    fun decode(s: GameState, m: Map<String, String>): Boolean {
        if (m["v"] != VERSION) return false
        return try {
            val st = s.stats
            st.health = m.getValue("health").toFloat()
            st.energy = m.getValue("energy").toFloat()
            st.food = m.getValue("food").toFloat()
            st.air = m.getValue("air").toFloat()
            st.mind = m["mind"]?.toFloatOrNull() ?: Stats.MAX // older saves have no mind value
            st.bleeding = m["bleeding"]?.toFloatOrNull() ?: 0f
            st.infected = m["infected"] == "true"
            st.brokenArm = m["brokenArm"] == "true"
            st.brokenLeg = m["brokenLeg"] == "true"
            st.fuel = m.getValue("fuel").toFloat()
            st.hull = m.getValue("hull").toFloat()
            st.credits = m.getValue("credits").toInt()
            st.day = m.getValue("day").toInt()
            st.clamp()
            s.clock = m.getValue("clock").toFloat()
            s.discovered.clear()
            m.getValue("discovered").split(",").filter { it.isNotBlank() }.forEach { s.discovered.add(it.toLong()) }
            s.charted.clear()
            m["charted"]?.split(",")?.filter { it.isNotBlank() }?.forEach { it.toLongOrNull()?.let { id -> s.charted.add(id) } }
            s.scanned.clear()
            m["scanned"]?.split(";")?.filter { it.isNotBlank() }?.forEach { s.scanned.add(it) }

            val ship = s.ship
            ship.restore(
                m.getValue("shipX").toFloat(), m.getValue("shipY").toFloat(),
                m.getValue("shipVX").toFloat(), m.getValue("shipVY").toFloat(),
                m.getValue("shipAngle").toFloat(),
            )
            s.bounties.restore(
                m["bountyDay"]?.toIntOrNull() ?: 0, m["bountyDone"]?.toIntOrNull() ?: 0,
                m["bountyActive"] ?: "", m["bountyBoard"] ?: "",
                m["bountyCaptured"] == "true",
            )
            m["loadout"]?.let { s.loadout.restore(it) }
            s.upgrades.restore(m["upgrades"] ?: "")
            s.hardcore = m["hardcore"] == "true"
            m["locker"]?.let { text ->
                s.locker.clear()
                text.split(",").filter { it.isNotBlank() }.forEach { name ->
                    ItemType.entries.firstOrNull { it.name == name && it.consumable }?.let { s.locker.add(it) }
                }
            }
            val landed = m.getValue("landedOn")
            if (landed.isNotBlank()) {
                val planet = planetFromId(landed.toLong())
                if (planet != null) ship.restoreLanded(planet, m.getValue("landAngle").toFloat())
            }
            true
        } catch (e: Exception) { // NumberFormatException, NoSuchElementException
            false
        }
    }

    /** Planet ids are chunk keys, so a planet can be regenerated from its id alone. */
    fun planetFromId(id: Long): Planet? =
        PlanetGenerator.forChunk((id shr 32).toInt(), (id and 0xffffffffL).toInt())
}

/**
 * Reads and writes saves through libGDX Preferences (small files in the user's home folder).
 * Normal and Hardcore games have separate slots, so neither ever overwrites the other.
 */
object SaveStore {
    private const val NORMAL = "space-exploration-save"
    private const val HARDCORE = "space-exploration-hardcore"

    private fun prefs(hardcore: Boolean) = Gdx.app.getPreferences(if (hardcore) HARDCORE else NORMAL)

    private fun read(hardcore: Boolean): Map<String, String> = prefs(hardcore).get().mapValues { it.value.toString() }

    /** Saves into the slot for the game's own mode. */
    fun save(state: GameState) {
        val p = prefs(state.hardcore)
        p.clear()
        p.put(SaveCodec.encode(state))
        p.flush()
    }

    fun hasSave(hardcore: Boolean): Boolean = prefs(hardcore).contains("v")

    /** Loads the given mode's slot into [state]. Returns false (leaving [state] as it was) if there is no usable save. */
    fun load(state: GameState, hardcore: Boolean): Boolean {
        if (!hasSave(hardcore)) return false
        if (!SaveCodec.decode(state, read(hardcore))) return false
        state.hardcore = hardcore
        return true
    }

    /** A one-line summary of a slot for the title screen, or null if it is empty. */
    fun summary(hardcore: Boolean): String? {
        if (!hasSave(hardcore)) return null
        val peek = GameState()
        if (!SaveCodec.decode(peek, read(hardcore))) return null
        return "day ${peek.stats.day}, ${peek.stats.credits} credits"
    }

    fun delete(hardcore: Boolean) {
        val p = prefs(hardcore)
        p.clear()
        p.flush()
    }

    /**
     * Earlier builds kept Hardcore games in the Normal slot. Move such a game to its own slot, so that
     * Normal and Hardcore stay separate from now on.
     */
    fun migrate() {
        val normal = read(false)
        if (normal["hardcore"] != "true") return
        if (!hasSave(true)) {
            val p = prefs(true)
            p.put(normal)
            p.flush()
        }
        delete(false)
    }
}
