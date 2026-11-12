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

    /** Consumables carried. Mods and plating apply as soon as they are picked up. */
    val pack = ArrayList<ItemType>().also { p ->
        repeat(STARTING_MEDKITS) { p.add(ItemType.MEDKIT) }
        repeat(STARTING_STIMS) { p.add(ItemType.STIM) }
    }
    var weaponBonus = 0
        private set
    var armor = STARTING_ARMOR
        private set

    val messages = ArrayList<String>()
    val floatTexts = ArrayList<FloatText>()

    /** Attacks on the player since the screen last looked, so it can show where they came from. */
    val attacks = ArrayList<AttackEvent>()

    private var calmTurns = 0

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

    /** The contract's target has been brought in and the bounty paid. */
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
        if (dead || exited) return false
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
            Tile.LAVA -> hurtPlayer(Tile.LAVA.harm, "Lava scorches you")
            Tile.ACID -> hurtPlayer(Tile.ACID.harm, "Acid burns you")
            else -> {}
        }
        pickup(lv)
        endTurn()
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
        if (dead || exited) return
        endTurn()
    }

    fun useItem(index: Int): Boolean {
        if (dead || exited || index !in pack.indices) return false
        val stats = state.stats
        when (pack[index]) {
            ItemType.MEDKIT -> {
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
            else -> return false
        }
        pack.removeAt(index)
        endTurn()
        return true
    }

    /** The route the player would walk to reach a known tile, or null. Monsters block the way. */
    fun pathTo(x: Int, y: Int): List<IntArray>? {
        val lv = level
        return Pathfinder.path(lv, px, py, x, y, avoidHarm = true, exploredOnly = true) { bx, by -> lv.monsterAt(bx, by) != null }
    }

    fun visibleMonsters(): List<Monster> = level.monsters.filter { level.visible[level.index(it.x, it.y)] }

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
        val accuracy = if (state.stats.mind < PANIC_MIND) PLAYER_ACCURACY - PANIC_ACCURACY_LOSS else PLAYER_ACCURACY
        if (!ambush && rnd.nextFloat() > accuracy) {
            log("You miss the ${m.displayName}.")
            floatTexts.add(FloatText(m.x, m.y, "miss", FloatText.INFO))
            wake(m)
            return
        }
        val dmg = (rnd.nextInt(BATON_MIN, BATON_MAX + 1) + weaponBonus) * (if (ambush) AMBUSH_MULTIPLIER else 1)
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
                state.stats.credits += contract.reward
                state.bounties.completeActive(contract)
                log("Bounty complete! ${m.displayName} is in custody. (+${contract.reward} credits)")
            }
        } else {
            log(if (ambush) "You ambush the ${m.displayName} for $dmg!" else "You hit the ${m.displayName} for $dmg.")
            wake(m)
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

    private fun hurtPlayer(amount: Int, why: String) {
        val stats = state.stats
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
                armor += 2
                lv.items.remove(item)
                log("You strap on an armor plate. (-2 damage taken)")
            }
            else -> {
                if (pack.size < PACK_SIZE) {
                    pack.add(item.type)
                    lv.items.remove(item)
                    log("You pick up a ${item.type.label.lowercase()}.")
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
        for (m in level.monsters) {
            if (m.spotted || !level.visible[level.index(m.x, m.y)]) continue
            m.spotted = true
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
        if (!lanternLit) return
        oil = max(0f, oil - OIL_BURN)
        if (oil <= 0f) log("Your lantern sputters and dies.")
    }

    /** Dread builds in the ruins, faster in the dark and on deeper floors; a brazier's light eases it. */
    private fun tickMind() {
        val stats = state.stats
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
        val repeats = if (m.type.fast) 2 else 1
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
        val base = rnd.nextInt(m.type.dmgMin, m.type.dmgMax + 1)
        val dmg = max(1, (base * m.dmgScale).toInt() - armor)
        val verb = if (ranged) "spits acid at you" else "hits you"
        hurtPlayer(dmg, "$who $verb")
    }

    private fun log(text: String) {
        messages.add(text)
        if (messages.size > 60) messages.removeAt(0)
    }

    companion object {
        const val PLAYER_ACCURACY = 0.92f
        const val BATON_MIN = 8
        const val BATON_MAX = 16
        const val AMBUSH_MULTIPLIER = 3 // hitting a sleeping monster
        const val PACK_SIZE = 6
        const val STARTING_MEDKITS = 2
        const val STARTING_STIMS = 1
        const val STARTING_ARMOR = 1 // a ship-issue vest
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
