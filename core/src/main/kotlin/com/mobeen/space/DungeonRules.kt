package com.mobeen.space

import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/** Line of sight and fog of war. */
object Fov {
    const val RADIUS = 9

    /** How far away a brazier-lit tile can still be seen. */
    const val VIEW_MAX = 12

    /** True if nothing opaque lies strictly between the two tiles. The end tile itself may be a wall. */
    fun los(level: Level, x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        var x = x0
        var y = y0
        val dx = abs(x1 - x0)
        val dy = abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx - dy
        while (true) {
            if (x == x1 && y == y1) return true
            val e2 = 2 * err
            if (e2 > -dy) { err -= dy; x += sx }
            if (e2 < dx) { err += dx; y += sy }
            if (x == x1 && y == y1) return true
            if (level.tile(x, y).opaque) return false
        }
    }

    /**
     * Marks every tile the player can see, and remembers them as explored. [radius] is how far your own light
     * reaches; tiles lit by braziers can be seen from up to [VIEW_MAX] away.
     */
    fun compute(level: Level, px: Int, py: Int, radius: Int = RADIUS) {
        java.util.Arrays.fill(level.visible, false)
        val view = max(radius, VIEW_MAX)
        for (ty in py - view..py + view) {
            for (tx in px - view..px + view) {
                if (!level.inBounds(tx, ty)) continue
                val dx = tx - px
                val dy = ty - py
                val d2 = dx * dx + dy * dy
                val i = level.index(tx, ty)
                val nearby = d2 <= radius * radius
                if (!nearby && !(level.lit[i] && d2 <= view * view)) continue
                if (los(level, px, py, tx, ty)) {
                    level.visible[i] = true
                    level.explored[i] = true
                }
            }
        }
    }
}

/** Shortest walking routes on a floor, moving in eight directions but never cutting corners. */
object Pathfinder {
    private val DIRS = arrayOf(
        intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
        intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1),
    )

    fun canStep(level: Level, x: Int, y: Int, dx: Int, dy: Int): Boolean {
        if (!level.tile(x + dx, y + dy).walkable) return false
        if (dx != 0 && dy != 0 && (!level.tile(x + dx, y).walkable || !level.tile(x, y + dy).walkable)) return false
        return true
    }

    /** Returns the tiles to walk through (not including the start), or null if there is no route. */
    fun path(
        level: Level, sx: Int, sy: Int, tx: Int, ty: Int,
        avoidHarm: Boolean, exploredOnly: Boolean, blocked: (Int, Int) -> Boolean,
    ): List<IntArray>? {
        if (sx == tx && sy == ty) return emptyList()
        val parent = IntArray(level.width * level.height) { -1 }
        val seen = BooleanArray(level.width * level.height)
        val queue = ArrayDeque<Int>()
        val start = level.index(sx, sy)
        seen[start] = true
        queue.add(start)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val cx = cur % level.width
            val cy = cur / level.width
            for (d in DIRS) {
                val nx = cx + d[0]
                val ny = cy + d[1]
                if (!level.inBounds(nx, ny) || !canStep(level, cx, cy, d[0], d[1])) continue
                val ni = level.index(nx, ny)
                if (seen[ni]) continue
                val isTarget = nx == tx && ny == ty
                if (exploredOnly && !level.explored[ni]) continue
                if (!isTarget) {
                    if (avoidHarm && level.tiles[ni].harm > 0) continue
                    if (blocked(nx, ny)) continue
                }
                seen[ni] = true
                parent[ni] = cur
                if (isTarget) {
                    val out = ArrayList<IntArray>()
                    var at = ni
                    while (at != start) {
                        out.add(intArrayOf(at % level.width, at / level.width))
                        at = parent[at]
                    }
                    out.reverse()
                    return out
                }
                queue.add(ni)
            }
        }
        return null
    }
}

/**
 * One trip into a planet's ruins: three floors, turn by turn. The player acts, then every monster acts.
 * Health, energy, food and credits are the same ones used everywhere else in the game.
 */
class DungeonRun(
    val planet: Planet,
    private val state: GameState,
    seed: Long,
    private val rnd: Random = Random.Default,
    val maxDepth: Int = 3,
    /** The accepted contract whose target is on this planet, if any. */
    val contract: Contract? = null,
) {
    private val levelSeed = seed
    private val levels = HashMap<Int, Level>()

    var depth = 1
        private set
    val level: Level
        get() = levels.getOrPut(depth) { DungeonGenerator.generate(planet, depth, maxDepth, Random(levelSeed * 31 + depth), contract) }

    var px = 0
    var py = 0
    var facingX = 1
    var facingY = 0

    /**
     * Consumables carried. You set out with what was in the ship's locker; if it was empty, the ship's
     * emergency kit gives you one medkit. Mods and plating apply as soon as they are picked up.
     */
    val pack = ArrayList<ItemType>().also { p ->
        p.addAll(state.locker.take(PACK_SIZE))
        state.locker.clear()
        if (p.isEmpty()) p.add(ItemType.MEDKIT)
    }

    /** Extra baton damage from mods found on this trip. */
    var weaponBonus = 0
        private set

    /** Extra armor from plates found on this trip. */
    private var plates = 0

    val weapon: Gear get() = state.loadout.weapon
    val shield: Gear? get() = state.loadout.shield

    /** Damage taken off every hit: your armor's rating plus any plates found on this trip. */
    val armor: Int get() = state.loadout.armor.armor + plates

    /** Spare gear you are carrying. Lost if you fall in the ruins. */
    val bag: List<Gear> get() = state.loadout.bag

    /** What each vial colour does on this trip, and which ones you have learned by drinking. */
    private val vialEffects: Map<ItemType, VialEffect> =
        ItemType.vials.zip(VialEffect.entries.shuffled(Random(seed xor 0x5EEDL))).toMap()
    val knownVials = HashSet<ItemType>()

    val messages = ArrayList<String>()
    val floatTexts = ArrayList<FloatText>()

    /** Attacks on the player since the screen last looked, so it can show where they came from. */
    val attacks = ArrayList<AttackEvent>()

    private var calmTurns = 0

    /** Where your blows are aimed. Kept until you change it. */
    var aim = BodyPart.BODY
        private set

    /** Aim at the next body part. Costs no turn. */
    fun cycleAim() {
        aim = BodyPart.entries[(aim.ordinal + 1) % BodyPart.entries.size]
        log("You aim for the ${aim.label}: ${aim.hint}.")
    }

    /** A strange thing you are standing over, waiting for you to choose what to do. Nothing else happens until you do. */
    var pendingEvent: GrimEvent? = null
        private set
    private var pendingEventItem: FloorItem? = null
    private var limpSteps = 0

    /** Hardcore only: whether this trip's one reprieve from a killing blow has been used. */
    var lastStandUsed = false
        private set

    /** Your lantern. Off saves oil and keeps you hidden, but you see almost nothing and dread builds fast. */
    var lanternOn = true
        private set
    var oil = OIL_START
        private set
    val lanternLit: Boolean get() = lanternOn && oil > 0f

    /** How far you can see by your own light. Brazier-lit areas can be seen from farther. */
    val sightRadius: Int get() = if (lanternLit) LANTERN_RADIUS else DARK_RADIUS

    /** Things you think you see when your mind is fraying. They cannot hurt you. */
    val phantoms = ArrayList<Phantom>()

    var turn = 0
        private set
    var exited = false
        private set
    var dead = false
        private set

    /** The contract's target has been captured. They still have to be brought out alive and submitted. */
    var completed = false
        private set

    val reward: Int get() = contract?.reward ?: 0

    init {
        arrive(fromAbove = true)
        log("You step into the ruins of ${planet.name}.")
    }

    // ------------------------------------------------------------------ player actions

    /** Move, or attack if a monster is in the way. Returns true if a turn was used. */
    fun tryMove(dx: Int, dy: Int): Boolean {
        if (dead || exited || pendingEvent != null) return false
        val lv = level
        if (!Pathfinder.canStep(lv, px, py, dx, dy)) return false
        facingX = dx
        facingY = dy
        val nx = px + dx
        val ny = py + dy

        val m = lv.monsterAt(nx, ny)
        if (m != null) {
            attack(m)
            endTurn()
            return true
        }

        px = nx
        py = ny
        when (lv.tile(nx, ny)) {
            Tile.STAIRS_DOWN -> log("The stairs lead down to floor ${depth + 1}.")
            Tile.STAIRS_UP -> log(if (depth == 1) "This is the way out of the ruins." else "The stairs lead up to floor ${depth - 1}.")
            Tile.LAVA -> hurtPlayer(hazardDamage(Tile.LAVA), "Lava scorches you")
            Tile.ACID -> hurtPlayer(hazardDamage(Tile.ACID), "Acid burns you")
            else -> {}
        }
        lv.trapAt(nx, ny)?.let { springTrap(lv, nx, ny, it) }
        pickup(lv)
        endTurn()
        if (state.stats.brokenLeg && !dead && limpSteps++ % 2 == 0) { // a broken leg: every other step costs an extra turn
            if (limpSteps % 8 == 1) log("Your broken leg screams with every step.")
            endTurn()
        }
        return true
    }

    /** The staircase the player is standing on, or null. Stairs are used on purpose, never by walking over them. */
    fun stairsHere(): Tile? = level.tile(px, py).takeIf { it == Tile.STAIRS_UP || it == Tile.STAIRS_DOWN }

    /** Take the stairs underfoot. Leaving the ruins from floor 1 ends the expedition. Costs no turn. */
    fun useStairs(): Boolean {
        if (dead || exited) return false
        when (level.tile(px, py)) {
            Tile.STAIRS_DOWN -> descend()
            Tile.STAIRS_UP -> {
                if (depth == 1) {
                    exited = true
                    log("You climb back out into the daylight.")
                } else {
                    ascend()
                }
            }
            else -> return false
        }
        return true
    }

    /** Turn the lantern on or off. Costs no turn. */
    fun toggleLantern() {
        if (dead || exited) return
        if (!lanternOn && oil <= 0f) {
            log("Your lantern is out of oil.")
            return
        }
        lanternOn = !lanternOn
        log(if (lanternOn) "You raise your lantern." else "You shutter your lantern. The dark closes in.")
        updateSight()
    }

    fun waitTurn() {
        if (dead || exited || pendingEvent != null) return
        endTurn()
    }

    fun useItem(index: Int): Boolean {
        if (dead || exited || pendingEvent != null || index !in pack.indices) return false
        val stats = state.stats
        when (pack[index]) {
            ItemType.MEDKIT -> {
                stats.bleeding = 0f
                stats.health = minOf(Stats.MAX, stats.health + MEDKIT_HEAL)
                floatTexts.add(FloatText(px, py, "+${MEDKIT_HEAL.toInt()}", FloatText.HEAL))
                log("You use a medkit. (+${MEDKIT_HEAL.toInt()} health)")
            }
            ItemType.RATION -> {
                stats.food = minOf(Stats.MAX, stats.food + 40f)
                log("You eat a ration. (+40 food)")
            }
            ItemType.STIM -> {
                stats.energy = minOf(Stats.MAX, stats.energy + 35f)
                log("You inject a stim. (+35 energy)")
            }
            ItemType.OIL_FLASK -> {
                oil = minOf(OIL_MAX, oil + OIL_FLASK_AMOUNT)
                log("You refill your lantern.")
            }
            ItemType.TONIC -> {
                stats.mind = minOf(Stats.MAX, stats.mind + TONIC_CALM)
                floatTexts.add(FloatText(px, py, "+${TONIC_CALM.toInt()}", FloatText.HEAL))
                log("You drink a calming tonic. Your hands stop shaking.")
            }
            ItemType.BANDAGE -> {
                if (stats.bleeding <= 0f) {
                    log("You are not bleeding.")
                    return false
                }
                stats.bleeding = 0f
                stats.health = minOf(Stats.MAX, stats.health + 5f)
                log("You bind the wound. The bleeding stops.")
            }
            ItemType.SPLINT -> {
                when {
                    stats.brokenLeg -> { stats.brokenLeg = false; log("You splint your leg. You can walk properly again.") }
                    stats.brokenArm -> { stats.brokenArm = false; log("You splint your arm. Your grip steadies.") }
                    else -> {
                        log("Nothing is broken.")
                        return false
                    }
                }
            }
            ItemType.ANTISEPTIC -> {
                if (!stats.infected) {
                    log("You have no infection to treat.")
                    return false
                }
                stats.infected = false
                log("The antiseptic burns, and the fever breaks.")
            }
            ItemType.VIAL_RED, ItemType.VIAL_GREEN, ItemType.VIAL_BLUE, ItemType.VIAL_AMBER, ItemType.VIAL_BLACK -> {
                val type = pack[index]
                pack.removeAt(index)
                drinkVial(type)
                endTurn()
                return true
            }
            else -> return false
        }
        pack.removeAt(index)
        endTurn()
        return true
    }

    /** Equip a piece of spare gear from your bag. Takes a turn. */
    fun equipFromBag(index: Int): Boolean {
        if (dead || exited) return false
        val g = bag.getOrNull(index) ?: return false
        state.loadout.equipFromBag(index)
        log("You equip the ${g.name.lowercase()}.")
        endTurn()
        return true
    }

    /** The name to show for an item: vials you have drunk this trip show what they do. */
    fun itemName(t: ItemType): String {
        val effect = vialEffects[t]
        return if (effect != null && t in knownVials) "${t.label} (${effect.label})" else t.label
    }

    /**
     * Leaving the ruins alive: a captured target comes with you (submit them at the ship terminal), and whatever
     * is left in your pack goes back to the ship's locker.
     */
    fun settleOnExit() {
        if (completed && contract != null) state.bounties.markCaptured(contract)
        for (t in pack) if (state.locker.size < GameState.LOCKER_SIZE) state.locker.add(t)
        pack.clear()
    }

    /** Falling in the ruins: your pack and any spare gear are lost. What you have equipped stays with you. */
    fun settleOnDeath() {
        pack.clear()
        state.loadout.bag.clear()
    }

    /** The route the player would walk to reach a known tile, or null. Monsters block the way. */
    fun pathTo(x: Int, y: Int): List<IntArray>? {
        val lv = level
        return Pathfinder.path(lv, px, py, x, y, avoidHarm = true, exploredOnly = true) { bx, by ->
            lv.monsterAt(bx, by) != null || lv.isKnownTrap(bx, by)
        }
    }

    fun visibleMonsters(): List<Monster> = level.monsters.filter { level.visible[level.index(it.x, it.y)] }

    // ------------------------------------------------------------------ developer tools

    /** Show the whole floor. */
    fun devRevealFloor() {
        java.util.Arrays.fill(level.explored, true)
        level.knownTraps.addAll(level.traps.keys)
        updateSight()
    }

    fun devClearMonsters() {
        level.monsters.clear()
        phantoms.clear()
        updateSight()
    }

    /** Go straight down a floor. Returns false on the last floor. */
    fun devDescend(): Boolean {
        if (!level.hasDown || dead || exited) return false
        descend()
        return true
    }

    /** Stand right next to the bounty target. Only works on the last floor while the target is there. */
    fun devTeleportToTarget(): Boolean {
        val target = level.monsters.firstOrNull { it.type.boss } ?: return false
        for (dy in -1..1) for (dx in -1..1) {
            val x = target.x + dx
            val y = target.y + dy
            if ((dx != 0 || dy != 0) && level.tile(x, y).walkable && level.tile(x, y).harm == 0 && level.monsterAt(x, y) == null) {
                px = x
                py = y
                updateSight()
                return true
            }
        }
        return false
    }

    fun devFillLantern() {
        oil = OIL_MAX
        lanternOn = true
        updateSight()
    }

    fun devGiveSupplies() {
        for (t in listOf(ItemType.MEDKIT, ItemType.TONIC, ItemType.OIL_FLASK, ItemType.STIM, ItemType.MEDKIT, ItemType.TONIC)) {
            if (pack.size < PACK_SIZE) pack.add(t)
        }
    }

    // ------------------------------------------------------------------ internals

    private fun arrive(fromAbove: Boolean) {
        val lv = level
        if (fromAbove) { px = lv.upArriveX; py = lv.upArriveY } else { px = lv.downArriveX; py = lv.downArriveY }
        phantoms.clear()
        updateSight()
    }

    private fun descend() {
        depth++
        arrive(fromAbove = true)
        log("You descend to floor $depth.")
    }

    private fun ascend() {
        depth--
        arrive(fromAbove = false)
        log("You climb up to floor $depth.")
    }

    private fun attack(m: Monster) {
        val ambush = m.state == AiState.SLEEPING // a sleeping target cannot dodge
        val weaponAim = weapon.accuracy
        var accuracy = if (state.stats.mind < PANIC_MIND) weaponAim - PANIC_ACCURACY_LOSS else weaponAim
        if (state.stats.brokenArm) accuracy -= BROKEN_ARM_AIM_LOSS
        val part = aim
        accuracy -= part.aimPenalty
        if (!ambush && rnd.nextFloat() > accuracy) {
            log(if (part == BodyPart.BODY) "You miss the ${m.displayName}." else "You swing for its ${part.label} and miss.")
            floatTexts.add(FloatText(m.x, m.y, "miss", FloatText.INFO))
            wake(m)
            return
        }
        val rawDmg = (rnd.nextInt(weapon.dmgMin, weapon.dmgMax + 1) + weaponBonus) * (if (ambush) weapon.ambush else 1)
        val armed = if (state.stats.brokenArm) rawDmg * BROKEN_ARM_DAMAGE else rawDmg.toFloat()
        val dmg = max(1, (armed * part.damage).toInt())
        m.hp -= dmg
        m.flash = 0.2f
        floatTexts.add(FloatText(m.x, m.y, "-$dmg", FloatText.HIT_MONSTER))
        if (m.hp <= 0) {
            level.monsters.remove(m)
            log("You subdue the ${m.displayName}.")
            // Winning a fight steadies your nerves; bringing in the target steadies them a lot.
            val steadied = if (m.type.boss) BOSS_STEADY else KILL_STEADY
            state.stats.mind = minOf(Stats.MAX, state.stats.mind + steadied)
            if (m.type.boss && contract != null && !completed) {
                completed = true
                log("${m.displayName} is in custody! Get them out of the ruins alive, then submit the bounty at your ship terminal.")
                val trophy = Loot.rollTrophy(rnd)
                level.items.add(FloorItem(ItemType.GEAR, m.x, m.y, gear = trophy))
                log("They drop a ${trophy.name.lowercase()}.")
            } else if (rnd.nextFloat() < DROP_CHANCE && level.itemAt(m.x, m.y) == null) {
                level.items.add(FloorItem(randomConsumable(), m.x, m.y))
            }
        } else {
            val where = if (part == BodyPart.BODY) "" else "'s ${part.label}"
            log(if (ambush) "You ambush the ${m.displayName}$where for $dmg!" else "You hit the ${m.displayName}$where for $dmg.")
            woundMonster(m, part)
            wake(m)
        }
    }

    /** What a blow to a particular part does, beyond the damage. */
    private fun woundMonster(m: Monster, part: BodyPart) {
        when (part) {
            BodyPart.HEAD -> if (rnd.nextFloat() < HEAD_STUN_CHANCE) {
                m.stunned = HEAD_STUN_TURNS
                floatTexts.add(FloatText(m.x, m.y, "stunned", FloatText.INFO))
                log("The ${m.displayName} reels, stunned!")
            }
            BodyPart.ARMS -> if (m.armWounds < 2) {
                m.armWounds++
                floatTexts.add(FloatText(m.x, m.y, "arm hit", FloatText.INFO))
                log("The ${m.displayName}'s blows grow weaker.")
            }
            BodyPart.LEGS -> if (m.legWounds < 2) {
                m.legWounds++
                floatTexts.add(FloatText(m.x, m.y, "leg hit", FloatText.INFO))
                log("The ${m.displayName} staggers. It is slowed.")
            }
            BodyPart.BODY -> {}
        }
    }

    private fun wake(m: Monster) {
        if (m.state != AiState.HUNTING) {
            m.state = AiState.HUNTING
            if (level.visible[level.index(m.x, m.y)]) log("The ${m.displayName} notices you!")
        }
        m.lastSeenX = px
        m.lastSeenY = py
    }

    /** Lava and acid hurt much less through a hazard suit. */
    private fun hazardDamage(tile: Tile): Int =
        if (state.loadout.armor.kind.hazardProof) max(1, tile.harm / 4) else tile.harm

    private fun randomConsumable(): ItemType {
        val roll = rnd.nextInt(100)
        return when {
            roll < 40 -> ItemType.vials[rnd.nextInt(ItemType.vials.size)]
            roll < 52 -> ItemType.MEDKIT
            roll < 60 -> ItemType.BANDAGE
            roll < 75 -> ItemType.OIL_FLASK
            roll < 90 -> ItemType.TONIC
            else -> ItemType.STIM
        }
    }

    /** A chest holds one thing: usually a piece of gear, otherwise a consumable. */
    private fun openChest(lv: Level, chest: FloorItem) {
        lv.items.remove(chest)
        if (rnd.nextFloat() < CHEST_GEAR_CHANCE) {
            val g = Loot.rollGear(depth, contract?.tier ?: 0, rnd)
            if (bag.size < Loadout.BAG_SIZE) {
                state.loadout.bag.add(g)
                log("You pry open a chest: a ${g.name.lowercase()} (${g.describe()}). Press G to equip it.")
            } else {
                lv.items.add(FloorItem(ItemType.GEAR, px, py, gear = g))
                log("You pry open a chest: a ${g.name.lowercase()}, but your bag is full.")
            }
        } else {
            val t = randomConsumable()
            if (pack.size < PACK_SIZE) {
                pack.add(t)
                log("You pry open a chest: a ${itemName(t).lowercase()}.")
            } else {
                lv.items.add(FloorItem(t, px, py))
                log("You pry open a chest: a ${itemName(t).lowercase()}, but your pack is full.")
            }
        }
    }

    /** Drink an unknown vial. After the first, you know what that colour does for the rest of the trip. */
    private fun drinkVial(type: ItemType) {
        val effect = vialEffects.getValue(type)
        val wasKnown = type in knownVials
        knownVials.add(type)
        val stats = state.stats
        val what = when (effect) {
            VialEffect.HEAL -> {
                stats.health = minOf(Stats.MAX, stats.health + 30f)
                floatTexts.add(FloatText(px, py, "+30", FloatText.HEAL))
                "Warmth spreads through you. (+30 health)"
            }
            VialEffect.CALM -> {
                stats.mind = minOf(Stats.MAX, stats.mind + 25f)
                "Your thoughts settle. (+25 mind)"
            }
            VialEffect.ENERGY -> {
                stats.energy = minOf(Stats.MAX, stats.energy + 40f)
                "Your tiredness burns away. (+40 energy)"
            }
            VialEffect.POISON -> {
                hurtPlayer(15, "")
                "It burns all the way down. Poison! (-15 health)"
            }
            VialEffect.NIGHTMARE -> {
                if (!stats.invulnerable) stats.mind = max(0f, stats.mind - 20f)
                "The walls start to breathe. (-20 mind)"
            }
        }
        log("You drink the ${type.label.lowercase()}. $what" + if (!wasKnown) " It was ${effect.label}." else "")
    }

    private fun hurtPlayer(amount: Int, why: String) {
        val stats = state.stats
        if (stats.invulnerable) { // developer god mode
            floatTexts.add(FloatText(px, py, "0", FloatText.INFO))
            return
        }
        if (state.hardcore && !lastStandUsed && stats.health - amount <= 0f) {
            // Last stand: once per trip, a blow that would end a Hardcore hunter leaves them barely standing.
            lastStandUsed = true
            stats.health = 1f
            floatTexts.add(FloatText(px, py, "last stand", FloatText.HIT_PLAYER))
            if (why.isNotEmpty()) log("$why!")
            log("You should be dead. Somehow you are still standing. (Last stand: once per trip.)")
            return
        }
        stats.health = max(0f, stats.health - amount)
        stats.mind = max(0f, stats.mind - amount * PAIN_DREAD)
        floatTexts.add(FloatText(px, py, "-$amount", FloatText.HIT_PLAYER))
        if (why.isNotEmpty()) log("$why for $amount!")
        if (stats.health <= 0f && !dead) {
            dead = true
            log("Everything goes dark...")
        }
    }

    private fun pickup(lv: Level) {
        val item = lv.itemAt(px, py) ?: return
        when (item.type) {
            ItemType.CREDITS -> {
                state.stats.credits += item.amount
                lv.items.remove(item)
                log("You find a credit chip. (+${item.amount})")
            }
            ItemType.BATON_MOD -> {
                weaponBonus += 3
                lv.items.remove(item)
                log("You fit a mod to your baton. (+3 damage)")
            }
            ItemType.ARMOR_PLATE -> {
                plates += 2
                lv.items.remove(item)
                log("You strap on an armor plate. (-2 damage taken)")
            }
            ItemType.GEAR -> {
                val g = item.gear ?: return
                if (bag.size < Loadout.BAG_SIZE) {
                    state.loadout.bag.add(g)
                    lv.items.remove(item)
                    log("You pick up a ${g.name.lowercase()} (${g.describe()}). Press G to equip it.")
                } else {
                    log("Your bag is full. You leave the ${g.name.lowercase()}.")
                }
            }
            ItemType.CHEST -> openChest(lv, item)
            ItemType.EVENT -> {
                pendingEvent = GrimEvent.entries.getOrNull(item.amount)
                pendingEventItem = item
            }
            else -> {
                if (pack.size < PACK_SIZE) {
                    pack.add(item.type)
                    lv.items.remove(item)
                    log("You pick up a ${itemName(item.type).lowercase()}.")
                } else {
                    log("Your pack is full.")
                }
            }
        }
    }

    private fun endTurn() {
        turn++
        state.clock += TURN_SECONDS
        state.stats.tickBody(TURN_SECONDS)
        recoverWhenSafe()
        burnOil()
        tickMind()
        for (m in level.monsters.toList()) {
            if (dead) break
            if (m.hp > 0) act(m)
        }
        updateSight()
        updatePhantoms()
        whisper()
        if (state.stats.health <= 0f) dead = true
        if (state.stats.mind <= 0f && !dead) {
            dead = true
            log("Your mind gives way...")
        }
    }

    private fun updateSight() {
        Fov.compute(level, px, py, sightRadius)
        spotTraps()
        for (m in level.monsters) {
            if (m.spotted || !level.visible[level.index(m.x, m.y)]) continue
            m.spotted = true
            if (state.stats.invulnerable) continue
            val scare = scareOf(m.type)
            state.stats.mind = max(0f, state.stats.mind - scare)
            if (scare >= 3f) log("The sight of the ${m.displayName} chills you.")
        }
    }

    private fun scareOf(type: MonsterType): Float = when (type) {
        MonsterType.SCUTTLER -> 0f
        MonsterType.HUSK -> 1f
        MonsterType.SPITTER -> 1f
        MonsterType.BRUTE -> 3f
        MonsterType.FUGITIVE -> 5f
    }

    private fun burnOil() {
        if (!lanternLit || state.stats.invulnerable) return
        oil = max(0f, oil - OIL_BURN)
        if (oil <= 0f) log("Your lantern sputters and dies.")
    }

    /** Dread builds in the ruins, faster in the dark and on deeper floors; a brazier's light eases it. */
    private fun tickMind() {
        val stats = state.stats
        if (stats.invulnerable) return
        val depthFactor = 1f + 0.2f * (depth - 1)
        when {
            level.isLit(px, py) -> stats.mind = minOf(Stats.MAX, stats.mind + BRAZIER_CALM)
            !lanternLit -> stats.mind -= DARK_DREAD * depthFactor
            else -> stats.mind -= RUINS_DREAD * depthFactor
        }
        stats.mind = stats.mind.coerceIn(0f, Stats.MAX)
    }

    /** When your mind frays you start seeing things in the dark. They come closer, then vanish. */
    private fun updatePhantoms() {
        val lv = level
        val it = phantoms.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.ttl--
            val dx = Integer.signum(px - p.x)
            val dy = Integer.signum(py - p.y)
            if (rnd.nextFloat() < 0.7f && lv.tile(p.x + dx, p.y + dy).walkable) {
                p.x += dx
                p.y += dy
            }
            if (DungeonGenerator.distance(p.x, p.y, px, py) <= 1) {
                it.remove()
                log("Something lunges at you... and dissolves. There was nothing there.")
            } else if (p.ttl <= 0) {
                it.remove()
            }
        }
        val mind = state.stats.mind
        if (mind >= HALLUCINATE_MIND || phantoms.size >= 3) return
        if (rnd.nextFloat() > (HALLUCINATE_MIND - mind) / HALLUCINATE_MIND * 0.3f) return
        repeat(10) {
            val x = px + rnd.nextInt(-6, 7)
            val y = py + rnd.nextInt(-6, 7)
            val d = DungeonGenerator.distance(x, y, px, py)
            if (d < 3 || !lv.inBounds(x, y) || !lv.tile(x, y).walkable || !lv.explored[lv.index(x, y)]) return@repeat
            if (lv.monsterAt(x, y) != null) return@repeat
            phantoms.add(Phantom(x, y, rnd.nextInt(3, 7), 8f + rnd.nextFloat() * 6f))
            return
        }
    }

    private fun whisper() {
        if (state.stats.mind >= WHISPER_MIND || rnd.nextFloat() > 0.06f) return
        log(whispers[rnd.nextInt(whispers.size)])
    }

    private val whispers = listOf(
        "You hear scratching behind the walls.",
        "Someone whispers your name.",
        "Footsteps stop when you stop.",
        "The dark feels like it is breathing.",
        "You are sure something just moved at the edge of your light.",
    )

    /** A slow heal while nothing is hunting you: one health every few turns. */
    private fun recoverWhenSafe() {
        if (visibleMonsters().any { it.state == AiState.HUNTING }) {
            calmTurns = 0
            return
        }
        calmTurns++
        if (calmTurns >= TURNS_PER_REGEN) {
            calmTurns = 0
            state.stats.health = minOf(Stats.MAX, state.stats.health + 1f)
        }
    }

    private fun act(m: Monster) {
        if (m.stunned > 0) { // reeling from a blow to the head
            m.stunned--
            return
        }
        if (m.legWounds > 0) { // lame: it only manages every other turn
            m.limpTick = !m.limpTick
            if (m.limpTick) return
        }
        val repeats = if (m.type.fast && m.legWounds == 0) 2 else 1
        for (step in 0 until repeats) {
            if (actOnce(m, step)) break
        }
    }

    /** Returns true if the monster attacked (which ends its turn). */
    private fun actOnce(m: Monster, step: Int): Boolean {
        val lv = level
        val dist = DungeonGenerator.distance(m.x, m.y, px, py)
        val sight = if (lanternLit || lv.isLit(px, py)) m.type.sight else minOf(m.type.sight, DARK_MONSTER_SIGHT)
        val sees = dist <= sight && Fov.los(lv, m.x, m.y, px, py)
        when (m.state) {
            AiState.SLEEPING -> {
                if (step == 0 && sees && rnd.nextFloat() < 0.12f) wake(m)
                return false
            }
            AiState.WANDERING -> {
                if (sees && rnd.nextFloat() < 0.6f) {
                    wake(m)
                    return false
                }
                if (step == 0 && rnd.nextFloat() < 0.5f) wanderStep(m)
                return false
            }
            AiState.HUNTING -> {
                if (sees) {
                    m.lastSeenX = px
                    m.lastSeenY = py
                }
                val canHit = if (m.type.range <= 1) {
                    dist == 1 && Pathfinder.canStep(lv, m.x, m.y, px - m.x, py - m.y)
                } else {
                    sees && dist in 1..m.type.range
                }
                if (canHit) {
                    // A fast monster may move twice, but never move and strike in the same turn:
                    // you always get a turn to react once something reaches you.
                    if (step == 0) monsterAttack(m)
                    return true
                }
                if (m.x == m.lastSeenX && m.y == m.lastSeenY && !sees) {
                    m.state = AiState.WANDERING
                    return false
                }
                stepToward(m, m.lastSeenX, m.lastSeenY)
                return false
            }
        }
    }

    private fun isOccupied(x: Int, y: Int) = (x == px && y == py) || level.monsterAt(x, y) != null

    private fun wanderStep(m: Monster) {
        val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
        val d = dirs[rnd.nextInt(dirs.size)]
        val nx = m.x + d[0]
        val ny = m.y + d[1]
        if (Pathfinder.canStep(level, m.x, m.y, d[0], d[1]) && !isOccupied(nx, ny) && level.tile(nx, ny).harm == 0) {
            m.x = nx
            m.y = ny
        }
    }

    private fun stepToward(m: Monster, tx: Int, ty: Int) {
        val lv = level
        val route = Pathfinder.path(lv, m.x, m.y, tx, ty, avoidHarm = true, exploredOnly = false) { bx, by -> isOccupied(bx, by) }
        val next = route?.firstOrNull()
        if (next != null && !isOccupied(next[0], next[1])) {
            m.x = next[0]
            m.y = next[1]
            return
        }
        // No clean route (another monster in the way): try heading straight for the target.
        val sx = Integer.signum(tx - m.x)
        val sy = Integer.signum(ty - m.y)
        for (d in listOf(intArrayOf(sx, sy), intArrayOf(sx, 0), intArrayOf(0, sy))) {
            if (d[0] == 0 && d[1] == 0) continue
            val nx = m.x + d[0]
            val ny = m.y + d[1]
            if (Pathfinder.canStep(lv, m.x, m.y, d[0], d[1]) && !isOccupied(nx, ny) && lv.tile(nx, ny).harm == 0) {
                m.x = nx
                m.y = ny
                return
            }
        }
    }

    private fun monsterAttack(m: Monster) {
        val ranged = m.type.range > 1
        val hidden = !level.visible[level.index(m.x, m.y)]
        attacks.add(AttackEvent(m.x, m.y, px, py, ranged, hidden))
        val who = if (hidden) "Something in the dark" else "The ${m.displayName}"
        val acc = (m.type.accuracy - armor * 0.02f).coerceIn(0.35f, 0.95f)
        if (rnd.nextFloat() > acc) {
            log(if (ranged) "$who spits at you and misses." else "$who misses you.")
            floatTexts.add(FloatText(px, py, "miss", FloatText.INFO))
            return
        }
        val block = if (ranged) shield?.rangedBlock ?: 0f else shield?.meleeBlock ?: 0f
        if (block > 0f && rnd.nextFloat() < block) {
            log(if (ranged) "You catch the spit on your shield." else "You block the blow with your shield.")
            floatTexts.add(FloatText(px, py, "block", FloatText.INFO))
            return
        }
        val part = if (ranged) BodyPart.BODY else rollTargetPart()
        val base = rnd.nextInt(m.type.dmgMin, m.type.dmgMax + 1)
        val weakened = 1f - 0.25f * m.armWounds
        val headBonus = if (part == BodyPart.HEAD) 1.15f else 1f
        val dmg = max(1, (base * m.dmgScale * weakened * headBonus).toInt() - armor)
        val verb = when {
            ranged -> "spits acid at you"
            part == BodyPart.HEAD -> "catches you across the head"
            part == BodyPart.ARMS -> "hits your arm"
            part == BodyPart.LEGS -> "hits your leg"
            else -> "hits you"
        }
        hurtPlayer(dmg, "$who $verb")
        if (!dead && part == BodyPart.HEAD && !state.stats.invulnerable) {
            state.stats.mind = max(0f, state.stats.mind - HEAD_HIT_DREAD) // a blow to the head leaves you dazed
        }
        if (!dead) rollInjury(m, part)
    }

    /** Where a monster's blow lands on you: mostly the body, sometimes a limb, now and then the head. */
    private fun rollTargetPart(): BodyPart {
        val roll = rnd.nextInt(100)
        return when {
            roll < 10 -> BodyPart.HEAD
            roll < 30 -> BodyPart.ARMS
            roll < 50 -> BodyPart.LEGS
            else -> BodyPart.BODY
        }
    }

    /** A blow that lands may leave a lasting injury. Good armor makes that less likely. */
    private fun rollInjury(m: Monster, part: BodyPart = BodyPart.BODY) {
        if (state.stats.invulnerable) return
        val guard = 0.7f * (1f - armor * 0.12f).coerceIn(0.35f, 1f)
        fun roll(chance: Float) = rnd.nextFloat() < chance * guard
        when (m.type) {
            MonsterType.SCUTTLER -> if (roll(0.07f)) startBleeding("Its claws open a gash")
            MonsterType.HUSK -> {
                if (roll(0.09f)) startBleeding("It tears your skin")
                if (roll(0.05f)) infect("Its rotten teeth broke the skin")
            }
            MonsterType.SPITTER -> if (roll(0.07f)) infect("The acid eats into an old cut")
            MonsterType.BRUTE -> when (part) { // bones break where the blow lands
                BodyPart.ARMS -> if (roll(0.35f)) breakArm("Something in your arm snaps")
                BodyPart.LEGS -> if (roll(0.25f)) breakLeg("Your leg buckles with a crack")
                else -> if (roll(0.07f)) startBleeding("The blow splits your skin")
            }
            MonsterType.FUGITIVE -> if (roll(0.12f)) startBleeding("A hidden blade finds you")
        }
    }

    private fun startBleeding(why: String) {
        val stats = state.stats
        if (stats.invulnerable) return
        if (stats.bleeding <= 0f) log("$why. You are bleeding!")
        stats.bleeding = max(stats.bleeding, Stats.BLEED_TIME)
        floatTexts.add(FloatText(px, py, "bleeding", FloatText.HIT_PLAYER))
    }

    private fun infect(why: String) {
        val stats = state.stats
        if (stats.invulnerable || stats.infected) return
        stats.infected = true
        log("$why. The wound is infected.")
        floatTexts.add(FloatText(px, py, "infected", FloatText.HIT_PLAYER))
    }

    private fun breakArm(why: String) {
        val stats = state.stats
        if (stats.invulnerable || stats.brokenArm) return
        stats.brokenArm = true
        log("$why. Your arm is broken!")
        floatTexts.add(FloatText(px, py, "broken arm", FloatText.HIT_PLAYER))
    }

    private fun breakLeg(why: String) {
        val stats = state.stats
        if (stats.invulnerable || stats.brokenLeg) return
        stats.brokenLeg = true
        log("$why. Your leg is broken!")
        floatTexts.add(FloatText(px, py, "broken leg", FloatText.HIT_PLAYER))
    }

    // ------------------------------------------------------------------ traps

    /** Look around for traps: a fair chance to notice one within two tiles that you can see. */
    private fun spotTraps() {
        val lv = level
        val chance = if (state.stats.mind < PANIC_MIND) 0.1f else TRAP_SPOT_CHANCE
        for ((i, type) in lv.traps) {
            if (i in lv.knownTraps || !lv.visible[i]) continue
            val tx = i % lv.width
            val ty = i / lv.width
            if (DungeonGenerator.distance(tx, ty, px, py) > 2) continue
            if (rnd.nextFloat() < chance) {
                lv.knownTraps.add(i)
                log("You spot ${type.label} in the floor.")
            }
        }
    }

    private fun springTrap(lv: Level, x: Int, y: Int, type: TrapType) {
        val i = lv.index(x, y)
        lv.traps.remove(i)
        lv.knownTraps.remove(i)
        when (type) {
            TrapType.SPIKES -> {
                hurtPlayer(7, "Spikes punch up through the floor and into your foot")
                if (!dead) startBleeding("The spikes went deep")
            }
            TrapType.PIT -> {
                hurtPlayer(8, "The floor gives way and you drop into a pit")
                if (!dead && rnd.nextFloat() < 0.25f) breakLeg("You land badly")
            }
            TrapType.NOXIOUS -> {
                if (!state.stats.invulnerable) state.stats.mind = max(0f, state.stats.mind - 15f)
                log("A vent hisses. Choking green gas fills your lungs and your head swims.")
                if (rnd.nextFloat() < 0.35f) infect("The gas burns in your chest")
            }
            TrapType.ALARM -> {
                log("A wire snaps taut. A shrill alarm wails through the ruins!")
                for (m in lv.monsters) {
                    if (DungeonGenerator.distance(m.x, m.y, px, py) <= ALARM_RANGE) {
                        m.state = AiState.HUNTING
                        m.lastSeenX = px
                        m.lastSeenY = py
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ grim events

    /** The choices for the event you are standing over, and whether each can be chosen right now. */
    fun eventOptions(): List<Pair<String, Boolean>> {
        val e = pendingEvent ?: return emptyList()
        return e.options.mapIndexed { i, text ->
            val ok = when {
                e == GrimEvent.SCAVENGER && i == 0 -> ItemType.MEDKIT in pack
                e == GrimEvent.LOCKER && i == 0 -> state.stats.credits >= 20
                else -> true
            }
            text to ok
        }
    }

    /** Decide what to do about the event. The last option always walks away and leaves it for later. */
    fun chooseEvent(index: Int): Boolean {
        val e = pendingEvent ?: return false
        val options = eventOptions()
        if (index !in options.indices || !options[index].second) return false
        val leave = index == e.options.lastIndex
        val item = pendingEventItem
        pendingEvent = null
        pendingEventItem = null
        if (leave) {
            log("You leave it alone.")
            return true
        }
        item?.let { level.items.remove(it) }
        resolveEvent(e, index)
        endTurn()
        return true
    }

    private fun resolveEvent(e: GrimEvent, choice: Int) {
        val stats = state.stats
        when (e) {
            GrimEvent.CORPSE -> {
                if (rnd.nextFloat() < 0.6f) giveFind("Pried from its fingers")
                else {
                    log("Its fluids seep into a cut on your hand.")
                    infect("The body was rotten")
                    if (rnd.nextFloat() < 0.5f) giveFind("Still, it was holding")
                }
            }
            GrimEvent.SHRINE -> if (choice == 0) {
                if (rnd.nextFloat() < 0.5f) {
                    stats.mind = minOf(Stats.MAX, stats.mind + 30f)
                    log("A strange calm settles over you. (+30 mind)")
                } else {
                    if (!stats.invulnerable) stats.mind = max(0f, stats.mind - 20f)
                    log("Something answers. You wish it had not. (-20 mind)")
                }
            } else {
                oil = minOf(OIL_MAX, oil + 35f)
                if (!stats.invulnerable) stats.mind = max(0f, stats.mind - 5f)
                log("You tip the candle's oil into your lantern. The shrine feels emptier. (+35 oil)")
            }
            GrimEvent.SCAVENGER -> if (choice == 0) {
                pack.remove(ItemType.MEDKIT)
                stats.mind = minOf(Stats.MAX, stats.mind + 20f)
                java.util.Arrays.fill(level.explored, true)
                stats.credits += 25
                log("\"Thank you.\" They sketch the whole floor for you and press a few credits into your hand. (+20 mind, +25 credits)")
            } else {
                stats.credits += 40
                if (!stats.invulnerable) stats.mind = max(0f, stats.mind - 15f)
                log("You take what they have. (+40 credits, -15 mind)")
                if (rnd.nextFloat() < 0.3f) startBleeding("They lash out with a knife as you leave")
            }
            GrimEvent.LOCKER -> if (choice == 0) {
                stats.credits -= 20
                if (rnd.nextFloat() < 0.5f) giveGear("The locker clanks open") else log("It swallows your credits and stays shut.")
            } else {
                if (rnd.nextFloat() < 0.5f) giveGear("The lock gives way") else {
                    log("The lock shrieks as it breaks. Things in the dark heard that.")
                    for (m in level.monsters) {
                        if (DungeonGenerator.distance(m.x, m.y, px, py) <= ALARM_RANGE) {
                            m.state = AiState.HUNTING
                            m.lastSeenX = px
                            m.lastSeenY = py
                        }
                    }
                }
            }
            GrimEvent.BLACK_POOL -> if (rnd.nextFloat() < 0.5f) {
                if (rnd.nextFloat() < 0.3f) giveGear("Your fingers close around something") else {
                    val found = rnd.nextInt(60, 121)
                    stats.credits += found
                    log("You fish out a handful of old credit chips. (+$found credits)")
                }
            } else {
                log("Something beneath the surface bites down.")
                hurtPlayer(6, "")
                if (!dead) startBleeding("Teeth, in the black water")
                if (!stats.invulnerable) stats.mind = max(0f, stats.mind - 10f)
            }
        }
    }

    private fun giveFind(lead: String) {
        if (rnd.nextFloat() < 0.2f) {
            giveGear(lead)
            return
        }
        val t = randomConsumable()
        if (pack.size < PACK_SIZE) {
            pack.add(t)
            log("$lead: a ${itemName(t).lowercase()}.")
        } else {
            level.items.add(FloorItem(t, px, py))
            log("$lead: a ${itemName(t).lowercase()}, but your pack is full.")
        }
    }

    private fun giveGear(lead: String) {
        val g = Loot.rollGear(depth, contract?.tier ?: 0, rnd)
        if (bag.size < Loadout.BAG_SIZE) {
            state.loadout.bag.add(g)
            log("$lead: a ${g.name.lowercase()} (${g.describe()}).")
        } else {
            level.items.add(FloorItem(ItemType.GEAR, px, py, gear = g))
            log("$lead: a ${g.name.lowercase()}, but your bag is full.")
        }
    }

    private fun log(text: String) {
        messages.add(text)
        if (messages.size > 60) messages.removeAt(0)
    }

    companion object {
        // The starting stun baton's numbers (see GearKind.STUN_BATON).
        const val PLAYER_ACCURACY = 0.92f
        const val BATON_MIN = 8
        const val BATON_MAX = 16
        const val AMBUSH_MULTIPLIER = 3 // hitting a sleeping monster
        const val PACK_SIZE = 6
        const val STARTING_MEDKITS = 2
        const val STARTING_STIMS = 1
        const val STARTING_ARMOR = 1 // the ship-issue vest
        const val DROP_CHANCE = 0.12f
        const val BROKEN_ARM_AIM_LOSS = 0.15f
        const val HEAD_STUN_CHANCE = 0.3f
        const val HEAD_STUN_TURNS = 2
        const val HEAD_HIT_DREAD = 2f
        const val BROKEN_ARM_DAMAGE = 0.75f
        const val TRAP_SPOT_CHANCE = 0.3f
        const val ALARM_RANGE = 12
        const val CHEST_GEAR_CHANCE = 0.55f
        const val MEDKIT_HEAL = 50f
        const val TURNS_PER_REGEN = 4
        const val TURN_SECONDS = 1.2f

        // Light
        const val LANTERN_RADIUS = 6
        const val DARK_RADIUS = 1
        const val DARK_MONSTER_SIGHT = 3
        const val OIL_START = 100f
        const val OIL_MAX = 100f
        const val OIL_BURN = 0.25f
        const val OIL_FLASK_AMOUNT = 40f

        // Mind
        const val RUINS_DREAD = 0.05f
        const val DARK_DREAD = 0.6f
        const val BRAZIER_CALM = 0.8f
        const val PAIN_DREAD = 0.12f
        const val TONIC_CALM = 30f
        const val KILL_STEADY = 1.5f
        const val BOSS_STEADY = 10f
        const val PANIC_MIND = 15f
        const val PANIC_ACCURACY_LOSS = 0.2f
        const val HALLUCINATE_MIND = 35f
        const val WHISPER_MIND = 50f
    }
}
