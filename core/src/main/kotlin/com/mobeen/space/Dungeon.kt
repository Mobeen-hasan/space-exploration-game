package com.mobeen.space

/** One square of a dungeon floor. [harm] is health lost for stepping on it. */
enum class Tile(val walkable: Boolean, val opaque: Boolean, val harm: Int) {
    WALL(false, true, 0),
    FLOOR(true, false, 0),
    STAIRS_UP(true, false, 0),
    STAIRS_DOWN(true, false, 0),
    LAVA(true, false, 20),
    ACID(true, false, 8),
    WATER(true, false, 0),

    /** A fire bowl: you cannot walk through it, but it lights the area around it and calms the mind. */
    BRAZIER(false, false, 0),
}

enum class AiState { SLEEPING, WANDERING, HUNTING }

/** Health and damage are on the same 0-100 scale as the player's own health. */
enum class MonsterType(
    val label: String,
    val hp: Int,
    val dmgMin: Int,
    val dmgMax: Int,
    val accuracy: Float,
    val range: Int,
    val fast: Boolean,
    val sight: Int,
    val boss: Boolean = false,
) {
    SCUTTLER("Scuttler", 12, 2, 6, 0.70f, 1, true, 7),
    HUSK("Husk", 26, 5, 10, 0.75f, 1, false, 7),
    SPITTER("Spitter", 18, 4, 8, 0.70f, 4, false, 8),
    BRUTE("Brute", 50, 9, 15, 0.75f, 1, false, 6),
    FUGITIVE("Fugitive", 90, 7, 12, 0.78f, 1, false, 10, boss = true),
}

class Monster(
    val type: MonsterType,
    var x: Int,
    var y: Int,
    val maxHp: Int,
    val dmgScale: Float,
    var state: AiState,
) {
    var hp = maxHp

    /** A bounty target's own name, shown instead of the monster type. */
    var title: String? = null
    val displayName: String get() = title ?: type.label
    var lastSeenX = x
    var lastSeenY = y

    // Used only for drawing: smooth movement between tiles.
    var drawX = x.toFloat()
    var drawY = y.toFloat()
    var flash = 0f

    /** Set the first time the player sees this monster; the first sight of something is what scares you. */
    var spotted = false
}

enum class ItemType(val label: String, val consumable: Boolean) {
    MEDKIT("Medkit", true),
    RATION("Ration", true),
    STIM("Stim", true),
    CREDITS("Credits", false),
    BATON_MOD("Baton mod", false),
    ARMOR_PLATE("Armor plate", false),
    OIL_FLASK("Oil flask", true),
    TONIC("Calming tonic", true),
}

class FloorItem(val type: ItemType, val x: Int, val y: Int, val amount: Int = 0)

/** A monster's attack on the player, so the screen can show where it came from. */
class AttackEvent(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val ranged: Boolean, val hidden: Boolean)

/** A floating number or word shown over a tile for a moment. */
class FloatText(val x: Int, val y: Int, val text: String, val kind: Int) {
    companion object {
        const val HIT_MONSTER = 0
        const val HIT_PLAYER = 1
        const val HEAL = 2
        const val INFO = 3
    }
}

/** One floor: a grid of tiles, what the player has seen, and what lives on it. */
class Level(val width: Int, val height: Int) {
    val tiles = Array(width * height) { Tile.WALL }
    val explored = BooleanArray(width * height)

    /** Tiles lit by braziers. They can be seen from much farther away than your lantern reaches. */
    val lit = BooleanArray(width * height)
    val visible = BooleanArray(width * height)
    val monsters = ArrayList<Monster>()
    val items = ArrayList<FloorItem>()

    var upX = 0
    var upY = 0
    var upArriveX = 0
    var upArriveY = 0
    var downX = -1
    var downY = -1
    var downArriveX = 0
    var downArriveY = 0
    val hasDown: Boolean get() = downX >= 0

    fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height
    fun index(x: Int, y: Int) = y * width + x
    fun tile(x: Int, y: Int): Tile = if (inBounds(x, y)) tiles[index(x, y)] else Tile.WALL
    fun set(x: Int, y: Int, t: Tile) {
        if (inBounds(x, y)) tiles[index(x, y)] = t
    }

    fun isLit(x: Int, y: Int): Boolean = inBounds(x, y) && lit[index(x, y)]

    fun monsterAt(x: Int, y: Int): Monster? = monsters.firstOrNull { it.x == x && it.y == y }
    fun itemAt(x: Int, y: Int): FloorItem? = items.firstOrNull { it.x == x && it.y == y }
}

/** Something the player only imagines: drawn like a monster, but it cannot touch you. */
class Phantom(var x: Int, var y: Int, var ttl: Int, val size: Float) {
    var drawX = x.toFloat()
    var drawY = y.toFloat()
}
