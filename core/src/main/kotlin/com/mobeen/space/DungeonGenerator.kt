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
     * and holds a cash cache instead.
     */
    fun generate(planet: Planet, depth: Int, maxDepth: Int, rnd: Random, boss: Contract? = null): Level {
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

        addHazards(level, planet, rooms, start, end, rnd)
        addMonsters(level, depth, maxDepth, rooms, start, end, rnd, boss)
        addItems(level, depth, maxDepth, rooms, start, end, rnd, boss)
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
    private fun addHazards(level: Level, planet: Planet, rooms: List<Room>, start: Room, end: Room, rnd: Random) {
        val tile = hazardFor(planet.type) ?: return
        for (r in rooms) {
            if (r === start || r === end || r.w < 5 || r.h < 5) continue
            if (rnd.nextFloat() > 0.5f) continue
            val pw = rnd.nextInt(2, min(4, r.w - 2) + 1)
            val ph = rnd.nextInt(2, min(3, r.h - 2) + 1)
            val px = r.x + 1 + rnd.nextInt(0, r.w - 2 - pw + 1)
            val py = r.y + 1 + rnd.nextInt(0, r.h - 2 - ph + 1)
            for (y in py until py + ph) for (x in px until px + pw) level.set(x, y, tile)
        }
    }

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

    private fun addMonsters(level: Level, depth: Int, maxDepth: Int, rooms: List<Room>, start: Room, end: Room, rnd: Random, boss: Contract?) {
        val others = rooms.filter { it !== start }
        val table: List<Pair<MonsterType, Int>> = when (depth) {
            1 -> listOf(MonsterType.SCUTTLER to 60, MonsterType.HUSK to 40)
            2 -> listOf(MonsterType.SCUTTLER to 30, MonsterType.HUSK to 35, MonsterType.SPITTER to 25, MonsterType.BRUTE to 10)
            else -> listOf(MonsterType.SCUTTLER to 20, MonsterType.HUSK to 25, MonsterType.SPITTER to 30, MonsterType.BRUTE to 25)
        }
        val hpScale = 1f + 0.2f * (depth - 1)
        val dmgScale = 1f + 0.12f * (depth - 1)
        repeat(2 + depth * 2) {
            val spot = randomFloorIn(level, others, rnd) ?: return@repeat
            val type = pick(rnd, table)
            val state = if (rnd.nextFloat() < 0.8f) AiState.SLEEPING else AiState.WANDERING
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

    private fun addItems(level: Level, depth: Int, maxDepth: Int, rooms: List<Room>, start: Room, end: Room, rnd: Random, boss: Contract?) {
        val table = listOf(
            ItemType.MEDKIT to 40, ItemType.RATION to 18, ItemType.STIM to 15,
            ItemType.CREDITS to 25, ItemType.BATON_MOD to 5, ItemType.ARMOR_PLATE to 5,
        )
        fun place(type: ItemType) {
            val spot = randomFloorIn(level, rooms, rnd) ?: return
            val amount = if (type == ItemType.CREDITS) rnd.nextInt(20, 61) else 0
            level.items.add(FloorItem(type, spot.first, spot.second, amount))
        }
        place(ItemType.MEDKIT) // always at least one way to heal
        if (depth >= 2) place(ItemType.RATION)
        repeat(3 + rnd.nextInt(0, 3)) { place(pick(rnd, table)) }
        if (depth == maxDepth && boss == null) { // no target down here: a stash to make the trip worthwhile
            level.items.add(FloorItem(ItemType.CREDITS, end.cx, end.cy, rnd.nextInt(80, 151)))
            repeat(2) { place(pick(rnd, table)) }
        }
    }

    /** Smallest distance in tiles between two points, moving in eight directions. */
    fun distance(ax: Int, ay: Int, bx: Int, by: Int): Int = max(abs(ax - bx), abs(ay - by))
}
