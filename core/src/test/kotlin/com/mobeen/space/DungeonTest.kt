package com.mobeen.space

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DungeonTest {
    private val planet = TestPlanets.ofType(PlanetType.ROCKY)

    private fun reachable(level: Level, ax: Int, ay: Int, bx: Int, by: Int): Boolean =
        Pathfinder.path(level, ax, ay, bx, by, avoidHarm = false, exploredOnly = false) { _, _ -> false } != null

    private fun newRun(seed: Long = 1L, rndSeed: Int = 7): Pair<DungeonRun, GameState> {
        val state = GameState()
        return DungeonRun(planet, state, seed, Random(rndSeed)) to state
    }

    /** Clear a plain 7x7 open room around the player so tests do not depend on the random floor. */
    private fun clearArena(run: DungeonRun) {
        val lv = run.level
        lv.monsters.clear()
        lv.items.clear()
        for (y in run.py - 3..run.py + 3) for (x in run.px - 3..run.px + 3) {
            if (lv.inBounds(x, y) && lv.tile(x, y) == Tile.WALL) lv.set(x, y, Tile.FLOOR)
            if (lv.tile(x, y) != Tile.STAIRS_UP && lv.tile(x, y) != Tile.STAIRS_DOWN && lv.inBounds(x, y)) lv.set(x, y, Tile.FLOOR)
        }
        Fov.compute(lv, run.px, run.py)
    }

    // ------------------------------------------------------------------ generation

    @Test
    fun everyFloorIsFullyConnectedAndWellFormed() {
        for (type in PlanetType.entries) for (depth in 1..3) for (seed in 1L..12L) {
            val p = TestPlanets.ofType(type)
            val lv = DungeonGenerator.generate(p, depth, 3, Random(seed))
            val what = "$type depth $depth seed $seed"

            assertEquals(Tile.STAIRS_UP, lv.tile(lv.upX, lv.upY), what)
            assertTrue(lv.tile(lv.upArriveX, lv.upArriveY).walkable, what)
            if (depth < 3) {
                assertTrue(lv.hasDown, what)
                assertEquals(Tile.STAIRS_DOWN, lv.tile(lv.downX, lv.downY), what)
                assertTrue(reachable(lv, lv.upArriveX, lv.upArriveY, lv.downArriveX, lv.downArriveY), "stairs not connected: $what")
            } else {
                assertFalse(lv.hasDown, what)
            }
            for (m in lv.monsters) {
                assertTrue(lv.tile(m.x, m.y).walkable && lv.tile(m.x, m.y).harm == 0, "monster on bad tile: $what")
                assertTrue(reachable(lv, lv.upArriveX, lv.upArriveY, m.x, m.y), "monster unreachable: $what")
            }
            for (i in lv.items) {
                assertEquals(Tile.FLOOR, lv.tile(i.x, i.y), "item on bad tile: $what")
                assertTrue(reachable(lv, lv.upArriveX, lv.upArriveY, i.x, i.y), "item unreachable: $what")
            }
            assertTrue(lv.items.any { it.type == ItemType.MEDKIT }, "no medkit: $what")
            assertTrue(lv.monsters.none { DungeonGenerator.distance(it.x, it.y, lv.upArriveX, lv.upArriveY) < 1 }, what)
        }
    }

    @Test
    fun finalFloorHasTheFugitiveAndEarlierOnesDoNot() {
        for (seed in 1L..10L) {
            val last = DungeonGenerator.generate(planet, 3, 3, Random(seed))
            assertEquals(1, last.monsters.count { it.type == MonsterType.FUGITIVE })
            val first = DungeonGenerator.generate(planet, 1, 3, Random(seed))
            assertEquals(0, first.monsters.count { it.type.boss })
        }
    }

    @Test
    fun hazardsMatchThePlanetType() {
        fun hazardTiles(type: PlanetType): Int {
            var n = 0
            for (seed in 1L..15L) {
                val lv = DungeonGenerator.generate(TestPlanets.ofType(type), 2, 3, Random(seed))
                n += lv.tiles.count { it == Tile.LAVA || it == Tile.ACID || it == Tile.WATER }
            }
            return n
        }
        assertEquals(0, hazardTiles(PlanetType.ROCKY))
        assertEquals(0, hazardTiles(PlanetType.ICE))
        assertTrue(hazardTiles(PlanetType.LAVA) > 0)
        assertTrue(hazardTiles(PlanetType.TOXIC) > 0)
        assertTrue(DungeonGenerator.generate(TestPlanets.ofType(PlanetType.LAVA), 2, 3, Random(3)).tiles.none { it == Tile.ACID })
    }

    @Test
    fun sameSeedGivesTheSameFloor() {
        val a = DungeonGenerator.generate(planet, 2, 3, Random(99))
        val b = DungeonGenerator.generate(planet, 2, 3, Random(99))
        assertEquals(a.tiles.toList(), b.tiles.toList())
        assertEquals(a.monsters.map { it.x to it.y }, b.monsters.map { it.x to it.y })
        val c = DungeonGenerator.generate(planet, 2, 3, Random(100))
        assertFalse(a.tiles.toList() == c.tiles.toList())
    }

    // ------------------------------------------------------------------ sight and paths

    @Test
    fun wallsBlockSightButYouCanSeeTheWallItself() {
        val lv = Level(12, 5)
        for (x in 0 until 12) lv.set(x, 2, Tile.FLOOR)
        assertTrue(Fov.los(lv, 1, 2, 10, 2))
        lv.set(5, 2, Tile.WALL)
        assertFalse(Fov.los(lv, 1, 2, 10, 2))
        assertTrue(Fov.los(lv, 1, 2, 5, 2))

        Fov.compute(lv, 1, 2)
        assertTrue(lv.visible[lv.index(4, 2)])
        assertTrue(lv.visible[lv.index(5, 2)])
        assertFalse(lv.visible[lv.index(8, 2)])
        assertTrue(lv.explored[lv.index(5, 2)])
    }

    @Test
    fun pathsAreShortestAndNeverCutCorners() {
        val lv = Level(8, 8)
        for (y in 1..6) for (x in 1..6) lv.set(x, y, Tile.FLOOR)
        val straight = Pathfinder.path(lv, 1, 1, 6, 1, false, false) { _, _ -> false }
        assertEquals(5, straight?.size)

        // A diagonal gap between two wall corners must not be squeezed through.
        val lv2 = Level(5, 5)
        lv2.set(1, 1, Tile.FLOOR)
        lv2.set(2, 2, Tile.FLOOR)
        assertFalse(Pathfinder.canStep(lv2, 1, 1, 1, 1))
        assertNull(Pathfinder.path(lv2, 1, 1, 2, 2, false, false) { _, _ -> false })
    }

    // ------------------------------------------------------------------ turns, combat, items

    @Test
    fun runStartsBesideTheEntranceStairsOnFloorOne() {
        val (run, _) = newRun()
        assertEquals(1, run.depth)
        assertEquals(run.level.upArriveX, run.px)
        assertEquals(run.level.upArriveY, run.py)
        assertFalse(run.exited)
        assertFalse(run.dead)
    }

    @Test
    fun walkingIntoAWallDoesNotUseATurn() {
        val (run, _) = newRun()
        val lv = run.level
        for (d in listOf(intArrayOf(0, 1), intArrayOf(0, -1), intArrayOf(1, 0), intArrayOf(-1, 0))) {
            if (!lv.tile(run.px + d[0], run.py + d[1]).walkable) {
                val before = run.turn
                assertFalse(run.tryMove(d[0], d[1]))
                assertEquals(before, run.turn)
                return
            }
        }
    }

    @Test
    fun everyTurnCostsEnergyAndFood() {
        val (run, state) = newRun()
        clearArena(run)
        val energy = state.stats.energy
        val food = state.stats.food
        repeat(10) { run.waitTurn() }
        assertTrue(state.stats.energy < energy)
        assertTrue(state.stats.food < food)
        assertEquals(10, run.turn)
    }

    @Test
    fun attackingEventuallySubdueMonsterAndLogsIt() {
        val (run, _) = newRun()
        clearArena(run)
        val m = Monster(MonsterType.HUSK, run.px + 1, run.py, 30, 1f, AiState.SLEEPING)
        run.level.monsters.add(m)
        var swings = 0
        while (run.level.monsters.contains(m) && swings < 40 && !run.dead) {
            run.tryMove(1, 0)
            swings++
        }
        assertFalse(run.level.monsters.contains(m), "monster should be gone after $swings swings")
        assertTrue(run.messages.any { it.contains("subdue") })
        assertTrue(swings in 1..40) // an ambush on a sleeper can end it in one blow
    }

    @Test
    fun hittingASleepingMonsterIsAnAmbushForTripleDamage() {
        val (run, _) = newRun()
        clearArena(run)
        val m = Monster(MonsterType.BRUTE, run.px + 1, run.py, 500, 1f, AiState.SLEEPING)
        run.level.monsters.add(m)
        run.tryMove(1, 0) // a sleeping target cannot dodge, so the very first swing lands
        val dealt = 500 - m.hp
        assertTrue(dealt >= DungeonRun.BATON_MIN * 3 && dealt <= DungeonRun.BATON_MAX * 3, "ambush dealt $dealt")
        assertTrue(run.messages.any { it.contains("ambush") })
        assertEquals(AiState.HUNTING, m.state)
    }

    @Test
    fun everyMonsterCanBeBeatenByAPlayerWhoUsesTheirMedkits() {
        // Rough balance guard: expected damage taken in a straight melee, at depth 1 scaling.
        val avgHit = (DungeonRun.BATON_MIN + DungeonRun.BATON_MAX) / 2f * DungeonRun.PLAYER_ACCURACY
        for (type in MonsterType.entries) {
            val turnsToKill = Math.ceil((type.hp / avgHit).toDouble()).toFloat()
            val perTurn = type.accuracy * (type.dmgMin + type.dmgMax) / 2f
            val expected = (turnsToKill - 0.5f) * perTurn
            val limit = if (type.boss) 90f else 60f
            assertTrue(expected <= limit, "${type.label} costs about $expected health in melee (limit $limit)")
        }
    }

    @Test
    fun hitMonsterWakesUpAndFightsBack() {
        val (run, state) = newRun()
        clearArena(run)
        val m = Monster(MonsterType.BRUTE, run.px + 1, run.py, 70, 1f, AiState.SLEEPING)
        run.level.monsters.add(m)
        run.tryMove(1, 0)
        assertEquals(AiState.HUNTING, m.state)
        val before = state.stats.health
        repeat(12) { if (!run.dead) run.waitTurn() }
        assertTrue(state.stats.health < before, "an awake brute beside you should hurt")
    }

    @Test
    fun monstersChaseYouAcrossTheRoom() {
        val (run, _) = newRun()
        clearArena(run)
        val m = Monster(MonsterType.HUSK, run.px + 3, run.py, 30, 1f, AiState.HUNTING)
        m.lastSeenX = run.px
        m.lastSeenY = run.py
        run.level.monsters.add(m)
        repeat(3) { run.waitTurn() }
        assertTrue(DungeonGenerator.distance(m.x, m.y, run.px, run.py) <= 1)
    }

    @Test
    fun spitterShootsFromADistanceButHuskDoesNot() {
        val (run, state) = newRun()
        clearArena(run)
        val spitter = Monster(MonsterType.SPITTER, run.px + 3, run.py, 20, 1f, AiState.HUNTING)
        run.level.monsters.add(spitter)
        val before = state.stats.health
        repeat(15) { if (!run.dead) run.waitTurn() }
        assertTrue(state.stats.health < before)
        assertEquals(run.px + 3, spitter.x, "a spitter keeps its distance")
    }

    @Test
    fun armorAndWeaponModsAreAppliedWhenPickedUp() {
        val (run, _) = newRun()
        clearArena(run)
        run.level.items.add(FloorItem(ItemType.BATON_MOD, run.px + 1, run.py))
        run.level.items.add(FloorItem(ItemType.ARMOR_PLATE, run.px + 2, run.py))
        run.tryMove(1, 0)
        run.tryMove(1, 0)
        assertEquals(3, run.weaponBonus)
        assertEquals(2, run.armor)
        assertTrue(run.level.items.isEmpty())
    }

    @Test
    fun creditChipsPayAndMedkitsHeal() {
        val (run, state) = newRun()
        clearArena(run)
        run.level.items.add(FloorItem(ItemType.CREDITS, run.px + 1, run.py, 42))
        run.level.items.add(FloorItem(ItemType.MEDKIT, run.px + 2, run.py))
        val credits = state.stats.credits
        run.tryMove(1, 0)
        assertEquals(credits + 42, state.stats.credits)
        run.tryMove(1, 0)
        assertEquals(listOf(ItemType.MEDKIT), run.pack)

        state.stats.health = 30f
        assertTrue(run.useItem(0))
        assertEquals(70f, state.stats.health, 1f)
        assertTrue(run.pack.isEmpty())
        assertFalse(run.useItem(0))
    }

    @Test
    fun fullPackLeavesTheItemOnTheFloor() {
        val (run, _) = newRun()
        clearArena(run)
        repeat(DungeonRun.PACK_SIZE) { run.pack.add(ItemType.RATION) }
        run.level.items.add(FloorItem(ItemType.MEDKIT, run.px + 1, run.py))
        run.tryMove(1, 0)
        assertEquals(DungeonRun.PACK_SIZE, run.pack.size)
        assertNotNull(run.level.itemAt(run.px, run.py))
        assertTrue(run.messages.last().contains("full"))
    }

    @Test
    fun lavaAndAcidHurtWhenSteppedOn() {
        val (run, state) = newRun()
        clearArena(run)
        run.level.set(run.px + 1, run.py, Tile.LAVA)
        run.level.set(run.px + 2, run.py, Tile.ACID)
        val h0 = state.stats.health
        run.tryMove(1, 0)
        assertEquals(h0 - 20f, state.stats.health, 2f)
        val h1 = state.stats.health
        run.tryMove(1, 0)
        assertEquals(h1 - 8f, state.stats.health, 2f)
    }

    @Test
    fun routePlanningAvoidsHazardsAndStopsAtUnexploredTiles() {
        val (run, _) = newRun()
        clearArena(run)
        val lv = run.level
        for (dy in -1..1) lv.set(run.px + 1, run.py + dy, Tile.LAVA) // a wall of lava beside you
        val route = run.pathTo(run.px + 3, run.py)
        assertTrue(route == null || route.none { lv.tile(it[0], it[1]) == Tile.LAVA })
        assertNull(run.pathTo(lv.width - 2, lv.height - 2).takeIf { !lv.explored[lv.index(lv.width - 2, lv.height - 2)] })
    }

    // ------------------------------------------------------------------ stairs, bounty, death

    @Test
    fun stairsAreUsedOnPurposeNotByWalkingOverThem() {
        val (run, _) = newRun()
        run.px = run.level.downArriveX
        run.py = run.level.downArriveY
        assertNull(run.stairsHere())
        assertFalse(run.useStairs(), "nothing to use when not standing on stairs")

        run.tryMove(1, 0) // step onto the down stairs
        assertEquals(1, run.depth, "stepping onto stairs must not take them")
        assertEquals(Tile.STAIRS_DOWN, run.stairsHere())
        assertTrue(run.messages.last().contains("lead down"))
        run.waitTurn()
        run.waitTurn()
        assertEquals(1, run.depth)

        assertTrue(run.useStairs())
        assertEquals(2, run.depth)
        assertEquals(run.level.upArriveX, run.px)
        assertNull(run.stairsHere())
    }

    @Test
    fun stairsLeadDownAndBackUpAndOutOfTheRuins() {
        val (run, _) = newRun()
        run.px = run.level.downArriveX
        run.py = run.level.downArriveY
        run.tryMove(1, 0)
        run.useStairs()
        assertEquals(2, run.depth)

        run.tryMove(-1, 0) // onto the up stairs
        assertEquals(2, run.depth)
        assertEquals(Tile.STAIRS_UP, run.stairsHere())
        run.useStairs()
        assertEquals(1, run.depth)

        run.px = run.level.upArriveX
        run.py = run.level.upArriveY
        run.tryMove(-1, 0)
        assertFalse(run.exited, "walking onto the exit must not leave the ruins")
        assertTrue(run.useStairs())
        assertTrue(run.exited)
        assertFalse(run.completed)
        assertFalse(run.useStairs(), "no more actions after leaving")
    }

    @Test
    fun defeatingTheFugitivePaysTheBountyOnce() {
        val (run, state) = newRun()
        repeat(2) {
            run.px = run.level.downArriveX
            run.py = run.level.downArriveY
            run.tryMove(1, 0)
            run.useStairs()
        }
        assertEquals(3, run.depth)
        clearArena(run)
        val boss = Monster(MonsterType.FUGITIVE, run.px + 1, run.py, 150, 1f, AiState.SLEEPING)
        boss.hp = 1
        run.level.monsters.add(boss)
        val credits = state.stats.credits
        var swings = 0
        while (!run.completed && swings < 30) { run.tryMove(1, 0); swings++ }
        assertTrue(run.completed)
        assertEquals(credits + DungeonRun.BOUNTY_REWARD, state.stats.credits)
        assertTrue(run.messages.any { it.contains("Bounty complete") })
    }

    @Test
    fun dyingIsReportedAndStopsFurtherActions() {
        val (run, state) = newRun()
        clearArena(run)
        state.stats.health = 5f
        run.level.monsters.add(Monster(MonsterType.BRUTE, run.px + 1, run.py, 70, 1f, AiState.HUNTING))
        var turns = 0
        while (!run.dead && turns < 100) { run.waitTurn(); turns++ }
        assertTrue(run.dead)
        assertEquals(0f, state.stats.health)
        val t = run.turn
        assertFalse(run.tryMove(0, 1))
        run.waitTurn()
        assertEquals(t, run.turn)
    }
}
