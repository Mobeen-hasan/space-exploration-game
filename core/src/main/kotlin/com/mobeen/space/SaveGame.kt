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
            "fuel" to st.fuel.toString(),
            "hull" to st.hull.toString(),
            "credits" to st.credits.toString(),
            "day" to st.day.toString(),
            "clock" to s.clock.toString(),
            "discovered" to s.discovered.joinToString(","),
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
            st.fuel = m.getValue("fuel").toFloat()
            st.hull = m.getValue("hull").toFloat()
            st.credits = m.getValue("credits").toInt()
            st.day = m.getValue("day").toInt()
            st.clamp()
            s.clock = m.getValue("clock").toFloat()
            s.discovered.clear()
            m.getValue("discovered").split(",").filter { it.isNotBlank() }.forEach { s.discovered.add(it.toLong()) }

            val ship = s.ship
            ship.restore(
                m.getValue("shipX").toFloat(), m.getValue("shipY").toFloat(),
                m.getValue("shipVX").toFloat(), m.getValue("shipVY").toFloat(),
                m.getValue("shipAngle").toFloat(),
            )
            s.bounties.restore(
                m["bountyDay"]?.toIntOrNull() ?: 0, m["bountyDone"]?.toIntOrNull() ?: 0,
                m["bountyActive"] ?: "", m["bountyBoard"] ?: "",
            )
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

/** Reads and writes the save through libGDX Preferences (a small file in the user's home folder). */
object SaveStore {
    private const val NAME = "space-exploration-save"

    fun save(state: GameState) {
        val prefs = Gdx.app.getPreferences(NAME)
        prefs.clear()
        prefs.put(SaveCodec.encode(state))
        prefs.flush()
    }

    fun load(state: GameState): Boolean {
        val prefs = Gdx.app.getPreferences(NAME)
        val all = prefs.get().mapValues { it.value.toString() }
        return SaveCodec.decode(state, all)
    }
}
