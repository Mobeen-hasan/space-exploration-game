package com.mobeen.space

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Builds one floor out of rooms joined by corridors, then fills it with monsters and items. */
object DungeonGenerator {
    const val W = 44
    const val H = 30

    private class Room(val x: Int, val y: Int, val w: Int, val h: Int) {
        val cx: Int get() = x + w / 2
        val cy: Int get() = y + h / 2

        /** True if the two rooms touch or sit within one tile of each other. */
        fun overlaps(o: Room) = x - 1 < o.x + o.w && x + w + 1 > o.x && y - 1 < o.y + o.h && y + h + 1 > o.y
    }

    /**
     * [boss] is the contract whose target hides on the last floor. Without one the last floor has no boss
     * and holds a cash cache instead. [difficulty] is 0 for plain scavenging and 1 to 3 for a contract's
     * tier; it sets how many monsters there are, how tough, and how many are awake.
     */
    fun generate(
        planet: Planet, depth: Int, maxDepth: Int, rnd: Random,
        boss: Contract? = null, difficulty: Int = boss?.tier ?: 0,
    ): Level {
        val level = Level(W, H)

        val rooms = ArrayList<Room>()
        var attempts = 0
        while (rooms.size < 9 && attempts < 300) {
            attempts++
            val w = rnd.nextInt(4, 10)
            val h = rnd.nextInt(4, 8)
            val x = rnd.nextInt(1, W - w - 1)
            val y = rnd.nextInt(1, H - h - 1)
            val r = Room(x, y, w, h)
            if (rooms.none { it.overlaps(r) }) rooms.add(r)
        }
        if (rooms.size < 4) { // extremely unlikely; fall back to a fixed layout so we always have a floor
            rooms.clear()
            rooms.addAll(listOf(Room(2, 2, 6, 5), Room(14, 3, 7, 6), Room(26, 2, 6, 5), Room(8, 16, 7, 6), Room(22, 17, 8, 6)))
        }
        rooms.sortBy { it.cx }

        for (r in rooms) carveRoom(level, r)
        for (i in 1 until rooms.size) corridor(level, rooms[i - 1], rooms[i], rnd)
        repeat(2) {
            val a = rooms[rnd.nextInt(rooms.size)]
            val b = rooms[rnd.nextInt(rooms.size)]
            if (a !== b) corridor(level, a, b, rnd)
        }

        val start = rooms[0]
        val end = rooms.drop(1).maxByOrNull { abs(it.cx - start.cx) + abs(it.cy - start.cy) } ?: rooms.last()

        // Stairs sit in room corners, and you arrive on the tile beside them.
        level.upX = start.x
        level.upY = start.y
        level.upArriveX = start.x + 1
        level.upArriveY = start.y
        level.set(level.upX, level.upY, Tile.STAIRS_UP)
        if (depth < maxDepth) {
            level.downX = end.x + end.w - 1
            level.downY = end.y + end.h - 1
            level.downArriveX = end.x + end.w - 2
            level.downArriveY = end.y + end.h - 1
            level.set(level.downX, level.downY, Tile.STAIRS_DOWN)
        }

        addHazards(level, planet, rooms, start, end, rnd, difficulty)
        addBraziers(level, depth, rooms, start, rnd)
        addMonsters(level, planet, depth, maxDepth, rooms, start, end, rnd, boss, difficulty)
        addItems(level, depth, maxDepth, rooms, start, end, rnd, boss, difficulty)
        return level
    }

    private fun carveRoom(level: Level, r: Room) {
        for (y in r.y until r.y + r.h) for (x in r.x until r.x + r.w) level.set(x, y, Tile.FLOOR)
    }

    private fun corridor(level: Level, a: Room, b: Room, rnd: Random) {
        var x = a.cx
        var y = a.cy
        val horizontalFirst = rnd.nextBoolean()
        fun carve() {
            if (level.tile(x, y) == Tile.WALL) level.set(x, y, Tile.FLOOR)
        }
        carve()
        if (horizontalFirst) {
            while (x != b.cx) { x += if (b.cx > x) 1 else -1; carve() }
            while (y != b.cy) { y += if (b.cy > y) 1 else -1; carve() }
        } else {
            while (y != b.cy) { y += if (b.cy > y) 1 else -1; carve() }
            while (x != b.cx) { x += if (b.cx > x) 1 else -1; carve() }
        }
    }

    private fun hazardFor(type: PlanetType): Tile? = when (type) {
        PlanetType.LAVA -> Tile.LAVA
        PlanetType.TOXIC -> Tile.ACID
        PlanetType.OCEAN -> Tile.WATER
        else -> null
    }

    /** Pools stay one tile away from the walls, so there is always a safe ring around them. */
    private fun addHazards(level: Level, planet: Planet, rooms: List<Room>, start: Room, end: Room, rnd: Random, difficulty: Int) {
        val tile = hazardFor(planet.type) ?: return
        val chance = RuinsDifficulty.hazardChance(planet.type, difficulty)
        for (r in rooms) {
            if (r === start || r === end || r.w < 5 || r.h < 5) continue
            if (rnd.nextFloat() > chance) continue
            val pw = rnd.nextInt(2, min(4, r.w - 2) + 1)
            val ph = rnd.nextInt(2, min(3, r.h - 2) + 1)
            val px = r.x + 1 + rnd.nextInt(0, r.w - 2 - pw + 1)
            val py = r.y + 1 + rnd.nextInt(0, r.h - 2 - ph + 1)
            for (y in py until py + ph) for (x in px until px + pw) level.set(x, y, tile)
        }
    }

    /**
     * Fire bowls: one in the arrival room so you start in the light, and one more on the first floor.
     * They stand inside rooms, away from the walls, so they never block a corridor.
     */
    private fun addBraziers(level: Level, depth: Int, rooms: List<Room>, start: Room, rnd: Random) {
        val chosen = ArrayList<Room>()
        chosen.add(start)
        if (depth == 1) rooms.filter { it !== start }.randomOrNull(rnd)?.let { chosen.add(it) }
        for (r in chosen) {
            if (r.w < 3 || r.h < 3) continue
            for (attempt in 0 until 20) {
                val x = rnd.nextInt(r.x + 1, r.x + r.w - 1)
                val y = rnd.nextInt(r.y + 1, r.y + r.h - 1)
                if (level.tile(x, y) != Tile.FLOOR) continue
                level.set(x, y, Tile.BRAZIER)
                lightAround(level, x, y)
                break
            }
        }
    }

    private fun lightAround(level: Level, bx: Int, by: Int) {
        for (y in by - BRAZIER_RADIUS..by + BRAZIER_RADIUS) for (x in bx - BRAZIER_RADIUS..bx + BRAZIER_RADIUS) {
            if (!level.inBounds(x, y)) continue
            val dx = x - bx
            val dy = y - by
            if (dx * dx + dy * dy > BRAZIER_RADIUS * BRAZIER_RADIUS) continue
            if (Fov.los(level, bx, by, x, y)) level.lit[level.index(x, y)] = true
        }
    }

    const val BRAZIER_RADIUS = 4

    private fun <T> pick(rnd: Random, options: List<Pair<T, Int>>): T {
        var roll = rnd.nextInt(options.sumOf { it.second })
        for ((value, weight) in options) {
            if (roll < weight) return value
            roll -= weight
        }
        return options.last().first
    }

    private fun isFree(level: Level, x: Int, y: Int): Boolean =
        level.tile(x, y) == Tile.FLOOR && level.monsterAt(x, y) == null && level.itemAt(x, y) == null

    private fun randomFloorIn(level: Level, rooms: List<Room>, rnd: Random): Pair<Int, Int>? {
        repeat(40) {
            val r = rooms[rnd.nextInt(rooms.size)]
            val x = rnd.nextInt(r.x, r.x + r.w)
            val y = rnd.nextInt(r.y, r.y + r.h)
            if (isFree(level, x, y)) return x to y
        }
        return null
    }

    private fun addMonsters(
        level: Level, planet: Planet, depth: Int, maxDepth: Int, rooms: List<Room>, start: Room, end: Room,
        rnd: Random, boss: Contract?, difficulty: Int,
    ) {
        val others = rooms.filter { it !== start }
        val table = RuinsDifficulty.monsterWeights(depth, difficulty, planet.type)
        val hpScale = (1f + 0.2f * (depth - 1)) * RuinsDifficulty.hpMultiplier(difficulty)
        val dmgScale = (1f + 0.12f * (depth - 1)) * RuinsDifficulty.dmgMultiplier(difficulty)
        val asleep = RuinsDifficulty.sleepChance(difficulty)
        repeat(RuinsDifficulty.monsterCount(depth, difficulty)) {
            val spot = randomFloorIn(level, others, rnd) ?: return@repeat
            val type = pick(rnd, table)
            val state = if (rnd.nextFloat() < asleep) AiState.SLEEPING else AiState.WANDERING
            level.monsters.add(Monster(type, spot.first, spot.second, (type.hp * hpScale).toInt(), dmgScale, state))
        }
        if (depth == maxDepth && boss != null) {
            level.monsters.removeAll { abs(it.x - end.cx) <= 1 && abs(it.y - end.cy) <= 1 }
            val hp = (MonsterType.FUGITIVE.hp * Contract.hpScale(boss.tier)).toInt()
            val target = Monster(MonsterType.FUGITIVE, end.cx, end.cy, hp, Contract.dmgScale(boss.tier), AiState.SLEEPING)
            target.title = boss.target
            level.monsters.add(target)
        }
    }

    private fun addItems(level: Level, depth: Int, maxDepth: Int, rooms: List<Room>, start: Room, end: Room, rnd: Random, boss: Contract?, difficulty: Int) {
        val table = listOf(
            ItemType.MEDKIT to 45, ItemType.RATION to 18, ItemType.STIM to 22,
            ItemType.CREDITS to 25, ItemType.BATON_MOD to 5, ItemType.ARMOR_PLATE to 5,
            ItemType.OIL_FLASK to 18, ItemType.TONIC to 18,
        )
        fun place(type: ItemType) {
            val spot = randomFloorIn(level, rooms, rnd) ?: return
            val amount = if (type == ItemType.CREDITS) rnd.nextInt(20, 61) else 0
            level.items.add(FloorItem(type, spot.first, spot.second, amount))
        }
        place(ItemType.MEDKIT) // always at least one way to heal
        if (depth == maxDepth) place(ItemType.MEDKIT) // and a second one before the last fight
        if (depth >= 2) place(ItemType.RATION)
        repeat(3 + rnd.nextInt(0, 3) + RuinsDifficulty.extraLoot(difficulty)) { place(pick(rnd, table)) }
        if (depth == maxDepth && boss == null) { // no target down here: a stash to make the trip worthwhile
            level.items.add(FloorItem(ItemType.CREDITS, end.cx, end.cy, rnd.nextInt(80, 151)))
            repeat(2) { place(pick(rnd, table)) }
        }
    }

    /** Smallest distance in tiles between two points, moving in eight directions. */
    fun distance(ax: Int, ay: Int, bx: Int, by: Int): Int = max(abs(ax - bx), abs(ay - by))
}

/**
 * How hard a trip into the ruins is. [difficulty] is 0 for plain scavenging and 1 to 3 for a contract's tier;
 * harder trips have more monsters, tougher ones, and fewer asleep. The planet type changes what lives there.
 */
object RuinsDifficulty {
    private val hp = floatArrayOf(1.0f, 1.05f, 1.1f, 1.25f)
    private val dmg = floatArrayOf(1.0f, 1.0f, 1.03f, 1.1f)
    private val asleep = floatArrayOf(0.80f, 0.75f, 0.72f, 0.62f)

    private fun level(difficulty: Int) = difficulty.coerceIn(0, 3)

    fun hpMultiplier(difficulty: Int) = hp[level(difficulty)]
    fun dmgMultiplier(difficulty: Int) = dmg[level(difficulty)]
    fun sleepChance(difficulty: Int) = asleep[level(difficulty)]
    fun monsterCount(depth: Int, difficulty: Int) = 2 + depth * 2 + (if (level(difficulty) >= 3) 1 else 0)
    fun extraLoot(difficulty: Int) = (level(difficulty) + 1) / 2

    /** Chance that a roomy chamber holds a hazard pool, on worlds that have one. */
    fun hazardChance(type: PlanetType, difficulty: Int): Float {
        val base = if (type == PlanetType.LAVA || type == PlanetType.TOXIC) 0.7f else 0.5f
        return base + 0.05f * level(difficulty)
    }

    /** Which monsters can appear, and how likely each is, for this floor, danger level and world. */
    fun monsterWeights(depth: Int, difficulty: Int, type: PlanetType): List<Pair<MonsterType, Int>> {
        val w = linkedMapOf(MonsterType.SCUTTLER to 0, MonsterType.HUSK to 0, MonsterType.SPITTER to 0, MonsterType.BRUTE to 0)
        when (depth) {
            1 -> { w[MonsterType.SCUTTLER] = 60; w[MonsterType.HUSK] = 40 }
            2 -> { w[MonsterType.SCUTTLER] = 30; w[MonsterType.HUSK] = 35; w[MonsterType.SPITTER] = 25; w[MonsterType.BRUTE] = 10 }
            else -> { w[MonsterType.SCUTTLER] = 20; w[MonsterType.HUSK] = 25; w[MonsterType.SPITTER] = 30; w[MonsterType.BRUTE] = 25 }
        }
        val d = level(difficulty)
        if (d > 0) { // higher stakes bring heavier hitters and fewer weaklings
            if (depth >= 2) w[MonsterType.BRUTE] = w.getValue(MonsterType.BRUTE) + d * 4
            w[MonsterType.SPITTER] = w.getValue(MonsterType.SPITTER) + d * 2
            w[MonsterType.SCUTTLER] = maxOf(5, w.getValue(MonsterType.SCUTTLER) - d * 4)
        }
        fun add(m: MonsterType, n: Int) { w[m] = w.getValue(m) + n }
        when (type) { // what lives on each kind of world
            PlanetType.LAVA -> add(MonsterType.SPITTER, 15)
            PlanetType.TOXIC -> add(MonsterType.SCUTTLER, 15)
            PlanetType.ICE -> if (depth >= 2) add(MonsterType.BRUTE, 12)
            PlanetType.DESERT -> { add(MonsterType.HUSK, 10); add(MonsterType.SCUTTLER, 8) }
            PlanetType.ROCKY -> add(MonsterType.HUSK, 5)
            PlanetType.OCEAN -> add(MonsterType.SPITTER, 8)
        }
        return w.entries.filter { it.value > 0 }.map { it.key to it.value }
    }
}
