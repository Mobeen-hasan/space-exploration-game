package com.mobeen.space

/** State that must survive switching between screens (space, surface, ship interior). */
class GameState {
    val ship = Ship()
    val field = PlanetField()
    val discovered = HashSet<Long>()
    val stats = Stats()

    /** Seconds played. Drives each planet's day/night so time carries across visits. */
    var clock = 0f

    /** The ruins expedition in progress, if any. Not saved: leaving or dying ends it. */
    var dungeon: DungeonRun? = null

    /** A message the next screen should show once (for example after collapsing). */
    var pendingNotice: String? = null
}
