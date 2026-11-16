package com.mobeen.space

/** State that must survive switching between screens (space, surface, ship interior). */
class GameState {
    val ship = Ship()
    val field = PlanetField()
    val discovered = HashSet<Long>()
    val stats = Stats()
    val bounties = BountyBoard()

    /** Equipped gear and spare gear, kept between trips. */
    val loadout = Loadout()

    /**
     * Supplies waiting on the ship. They go into your pack when you enter the ruins, and whatever you bring
     * back out returns here. A new game starts with a small kit.
     */
    val locker = ArrayList<ItemType>(STARTING_KIT)

    /** Seconds played. Drives each planet's day/night so time carries across visits. */
    var clock = 0f

    /** The ruins expedition in progress, if any. Not saved: leaving or dying ends it. */
    var dungeon: DungeonRun? = null

    /** A message the next screen should show once (for example after collapsing). */
    var pendingNotice: String? = null

    /** One life: if you die, the game is over and its save is deleted. Normal games are kept separately. */
    var hardcore = false

    /** Start over completely, in the chosen mode. */
    fun resetForNewGame(hardcoreMode: Boolean) {
        freshHunter()
        stats.credits = Stats.START_CREDITS
        discovered.clear()
        bounties.reset(keepRecord = false)
        clock = 0f
        hardcore = hardcoreMode
    }

    /** The words for the end of a Hardcore game: how you died and what you achieved. */
    fun hardcoreEpitaph(reason: String): String {
        val days = stats.day
        val captures = bounties.completedCount
        val worlds = discovered.size
        return "$reason\n\nYou survived $days day${if (days == 1) "" else "s"}, brought in $captures bount" +
            "${if (captures == 1) "y" else "ies"} and discovered $worlds world${if (worlds == 1) "" else "s"}.\n" +
            "Your Hardcore game is over. Your Normal game is untouched."
    }

    private fun freshHunter() {
        stats.reset()
        ship.restore(0f, 0f, 0f, 0f, (Math.PI / 2).toFloat())
        loadout.reset()
        locker.clear()
        locker.addAll(STARTING_KIT)
        dungeon = null
        pendingNotice = null
    }

    companion object {
        const val LOCKER_SIZE = 6
        val STARTING_KIT: List<ItemType> =
            List(DungeonRun.STARTING_MEDKITS) { ItemType.MEDKIT } + List(DungeonRun.STARTING_STIMS) { ItemType.STIM }
    }
}
