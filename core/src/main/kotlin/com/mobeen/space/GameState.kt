package com.mobeen.space

/** State that must survive switching between screens (space, surface, ship interior). */
class GameState {
    val ship = Ship()
    val field = PlanetField()
    val discovered = HashSet<Long>()

    /** Planets you have seen on radar (shown on the star map), and signals you have scanned (see [Signal.key]). */
    val charted = HashSet<Long>()
    val scanned = HashSet<String>()

    /** Trader stock already bought today, as "planetId:day:slot". Not saved. */
    val boughtToday = HashSet<String>()
    val stats = Stats()
    val bounties = BountyBoard()

    /** Equipped gear and spare gear, kept between trips. */
    val loadout = Loadout()

    /** Ship improvements bought at town fuel depots. */
    val upgrades = ShipUpgrades()

    /** How many supplies the ship's locker holds. */
    val lockerSize: Int get() = LOCKER_SIZE + upgrades.extraLocker

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

    /**
     * Treat what you can with supplies from the ship's locker: a bandage (or a medkit) for bleeding, antiseptic for
     * an infection, splints for broken bones. Returns what was done, or an empty list if nothing could be treated.
     */
    fun treatFromLocker(): List<String> {
        val done = ArrayList<String>()
        fun take(t: ItemType): Boolean = locker.remove(t)
        if (stats.bleeding > 0f && (take(ItemType.BANDAGE) || take(ItemType.MEDKIT))) {
            stats.bleeding = 0f
            done.add("bandaged your wound")
        }
        if (stats.infected && take(ItemType.ANTISEPTIC)) {
            stats.infected = false
            done.add("cleaned the infection")
        }
        if (stats.brokenLeg && take(ItemType.SPLINT)) {
            stats.brokenLeg = false
            done.add("splinted your leg")
        }
        if (stats.brokenArm && take(ItemType.SPLINT)) {
            stats.brokenArm = false
            done.add("splinted your arm")
        }
        return done
    }

    /** Start over completely, in the chosen mode. */
    fun resetForNewGame(hardcoreMode: Boolean) {
        freshHunter()
        stats.credits = Stats.START_CREDITS
        discovered.clear()
        charted.clear()
        scanned.clear()
        upgrades.reset()
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
            List(DungeonRun.STARTING_MEDKITS) { ItemType.MEDKIT } + List(DungeonRun.STARTING_STIMS) { ItemType.STIM } +
                ItemType.BANDAGE
    }
}
