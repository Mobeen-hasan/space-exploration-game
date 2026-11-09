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

    private fun contractFor(p: Planet, tier: Int = 2, reward: Int = 500) =
        Contract(77L, "Vex 'Quiet' Morrow", "stole reactor cores", p.id, "Test", p.type, 3000, reward, tier)

    @Test
    fun targetOnlyAppearsOnTheLastFloorAndOnlyForAContract() {
        val c = contractFor(planet)
        for (seed in 1L..10L) {
            val withContract = DungeonGenerator.generate(planet, 3, 3, Random(seed), c)
            val targets = withContract.monsters.filter { it.type.boss }
            assertEquals(1, targets.size)
            assertEquals(c.target, targets[0].title)
            assertEquals(c.target, targets[0].displayName)

            val without = DungeonGenerator.generate(planet, 3, 3, Random(seed))
            assertEquals(0, without.monsters.count { it.type.boss })
            assertTrue(without.items.any { it.type == ItemType.CREDITS && it.amount >= 80 }, "a cash cache replaces the target")

            val early = DungeonGenerator.generate(planet, 1, 3, Random(seed), c)
            assertEquals(0, early.monsters.count { it.type.boss })
        }
    }

    @Test
    fun tougherContractsMeanTougherTargets() {
        fun target(tier: Int) = DungeonGenerator.generate(planet, 3, 3, Random(5), contractFor(planet, tier)).monsters.first { it.type.boss }
        assertTrue(target(3).maxHp > target(2).maxHp && target(2).maxHp > target(1).maxHp)
        assertTrue(target(3).dmgScale > target(1).dmgScale)
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
    fun youStartWithMedkitsAndAStim() {
        val (run, _) = newRun()
        assertEquals(2, run.pack.count { it == ItemType.MEDKIT })
        assertEquals(1, run.pack.count { it == ItemType.STIM })
        assertTrue(run.pack.size <= DungeonRun.PACK_SIZE)
    }

    @Test
    fun healthSlowlyReturnsWhileNothingIsHuntingYou() {
        val (run, state) = newRun()
        clearArena(run)
        state.stats.health = 50f
        repeat(30) { run.waitTurn() }
        assertTrue(state.stats.health > 55f, "30 calm turns should heal a little, but health is ${state.stats.health}")
        assertTrue(state.stats.health < 75f, "recovery should be slow, but health is ${state.stats.health}")
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
    fun theHardestTargetIsBeatableWithAnAmbushAndSomeMedkits() {
        val avgHit = (DungeonRun.BATON_MIN + DungeonRun.BATON_MAX) / 2f * DungeonRun.PLAYER_ACCURACY
        val hp = MonsterType.FUGITIVE.hp * Contract.hpScale(3)
        val afterAmbush = hp - (DungeonRun.BATON_MIN + DungeonRun.BATON_MAX) / 2f * DungeonRun.AMBUSH_MULTIPLIER
        val turns = Math.ceil((afterAmbush / avgHit).toDouble()).toFloat()
        val perTurn = MonsterType.FUGITIVE.accuracy * (MonsterType.FUGITIVE.dmgMin + MonsterType.FUGITIVE.dmgMax) / 2f * Contract.dmgScale(3)
        val expected = turns * perTurn
        assertTrue(expected <= 100f, "tier 3 target costs about $expected health after an ambush")
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
        assertEquals(DungeonRun.STARTING_ARMOR + 2, run.armor)
        assertTrue(run.level.items.isEmpty())
    }

    @Test
    fun creditChipsPayAndMedkitsHeal() {
        val (run, state) = newRun()
        clearArena(run)
        run.level.items.add(FloorItem(ItemType.CREDITS, run.px + 1, run.py, 42))
        run.level.items.add(FloorItem(ItemType.MEDKIT, run.px + 2, run.py))
        run.pack.clear()
        val credits = state.stats.credits
        run.tryMove(1, 0)
        assertEquals(credits + 42, state.stats.credits)
        run.tryMove(1, 0)
        assertEquals(listOf(ItemType.MEDKIT), run.pack)

        state.stats.health = 30f
        assertTrue(run.useItem(0))
        assertEquals(30f + DungeonRun.MEDKIT_HEAL, state.stats.health, 2f)
        assertTrue(run.pack.isEmpty())
        assertFalse(run.useItem(0))
    }

    @Test
    fun fullPackLeavesTheItemOnTheFloor() {
        val (run, _) = newRun()
        clearArena(run)
        run.pack.clear()
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
    fun defeatingTheTargetPaysTheContractOnceAndClearsIt() {
        val state = GameState()
        val c = contractFor(planet, tier = 2, reward = 480)
        state.bounties.available.add(c)
        assertTrue(state.bounties.accept(c))
        val run = DungeonRun(planet, state, 1L, Random(7), contract = c)
        repeat(2) {
            run.px = run.level.downArriveX
            run.py = run.level.downArriveY
            run.tryMove(1, 0)
            run.useStairs()
        }
        assertEquals(3, run.depth)
        val boss = run.level.monsters.first { it.type.boss }
        clearArena(run)
        boss.x = run.px + 1
        boss.y = run.py
        boss.hp = 1
        run.level.monsters.add(boss)

        val credits = state.stats.credits
        var swings = 0
        while (!run.completed && swings < 30) { run.tryMove(1, 0); swings++ }
        assertTrue(run.completed)
        assertEquals(credits + 480, state.stats.credits)
        assertEquals(480, run.reward)
        assertNull(state.bounties.active, "the contract is used up")
        assertEquals(1, state.bounties.completedCount)
        assertTrue(run.messages.any { it.contains("Bounty complete") && it.contains(c.target) })
    }

    @Test
    fun aRunWithoutAContractNeverPaysABounty() {
        val (run, state) = newRun()
        assertNull(run.contract)
        repeat(2) {
            run.px = run.level.downArriveX
            run.py = run.level.downArriveY
            run.tryMove(1, 0)
            run.useStairs()
        }
        assertEquals(3, run.depth)
        assertTrue(run.level.monsters.none { it.type.boss })
        val credits = state.stats.credits
        clearArena(run)
        val stray = Monster(MonsterType.FUGITIVE, run.px + 1, run.py, 5, 1f, AiState.SLEEPING) // even a boss-type monster pays nothing
        run.level.monsters.add(stray)
        var swings = 0
        while (run.level.monsters.contains(stray) && swings < 20) { run.tryMove(1, 0); swings++ }
        assertEquals(credits, state.stats.credits)
        assertFalse(run.completed)
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

    // ------------------------------------------------------------------ a simple automated player

    /**
     * A plain player: fights everything it meets (ambushing sleepers), heals with medkits when hurt,
     * rests when safe, grabs items it can see, then takes the stairs. It knows where the stairs are,
     * which a real player has to find, but it never skips a fight.
     */
    private fun playThrough(run: DungeonRun, state: GameState, maxActions: Int = 6000): Boolean {
        var actions = 0
        while (actions++ < maxActions && !run.dead && !run.exited) {
            val lv = run.level
            val stats = state.stats
            if (run.completed) return true
            if (run.contract == null && run.depth == run.maxDepth && lv.monsters.isEmpty()) return true // cleared it

            if (stats.health < 45f) {
                val i = run.pack.indexOf(ItemType.MEDKIT)
                if (i >= 0 && run.useItem(i)) continue
            }
            if (stats.energy < 25f) {
                val i = run.pack.indexOf(ItemType.STIM)
                if (i >= 0 && run.useItem(i)) continue
            }
            val adjacent = lv.monsters.firstOrNull {
                DungeonGenerator.distance(it.x, it.y, run.px, run.py) == 1 &&
                    Pathfinder.canStep(lv, run.px, run.py, it.x - run.px, it.y - run.py)
            }
            if (adjacent != null) {
                run.tryMove(adjacent.x - run.px, adjacent.y - run.py)
                continue
            }
            if (run.visibleMonsters().none { it.state == AiState.HUNTING } && stats.health < 60f) {
                run.waitTurn() // catch a breath: recovery is slow, so this costs food and energy
                continue
            }

            fun stepToward(tx: Int, ty: Int): Boolean {
                var route = Pathfinder.path(lv, run.px, run.py, tx, ty, true, false) { bx, by -> lv.monsterAt(bx, by) != null }
                if (route == null) route = Pathfinder.path(lv, run.px, run.py, tx, ty, false, false) { _, _ -> false }
                val next = route?.firstOrNull() ?: return false
                return run.tryMove(next[0] - run.px, next[1] - run.py)
            }

            val nearestMonster = lv.monsters.minByOrNull { DungeonGenerator.distance(it.x, it.y, run.px, run.py) }
            if (nearestMonster != null) {
                if (!stepToward(nearestMonster.x, nearestMonster.y)) run.waitTurn()
                continue
            }
            val nearestItem = lv.items.filter { lv.visible[lv.index(it.x, it.y)] }
                .minByOrNull { DungeonGenerator.distance(it.x, it.y, run.px, run.py) }
            if (nearestItem != null && run.pack.size < DungeonRun.PACK_SIZE) {
                if (!stepToward(nearestItem.x, nearestItem.y)) run.waitTurn()
                continue
            }
            if (lv.hasDown) {
                if (run.stairsHere() == Tile.STAIRS_DOWN) run.useStairs()
                else if (!stepToward(lv.downX, lv.downY)) run.waitTurn()
                continue
            }
            run.waitTurn()
        }
        return run.completed || (run.contract == null && run.depth == run.maxDepth && run.level.monsters.isEmpty())
    }

    /** Share of simulated runs in which the automated player clears the ruins at a given tier (0 = no contract). */
    private fun winRate(tier: Int, total: Int = 60): Double {
        val types = PlanetType.entries
        var wins = 0
        for (i in 0 until total) {
            val p = TestPlanets.ofType(types[i % types.size], id = 1000L + i)
            val state = GameState()
            var contract: Contract? = null
            if (tier > 0) {
                val c = Contract(i.toLong(), "Target $i", "a crime", p.id, "P$i", p.type, 3000, 400, tier)
                state.bounties.available.add(c)
                state.bounties.accept(c)
                contract = c
            }
            val run = DungeonRun(p, state, 500L + i, Random(i), contract = contract)
            if (playThrough(run, state)) wins++
        }
        return wins.toDouble() / total
    }

    @Test
    fun harderContractsAreHarderToFinish() {
        val rates = (0..3).map { winRate(it) }
        // Scavenging (0) ends differently from a contract (no target to beat), so it is only checked on its own.
        assertTrue(rates[0] >= 0.6, "clearing the ruins with no contract should be comfortable: $rates")
        assertTrue(rates[1] >= 0.75, "tier 1 should be comfortable: $rates")
        assertTrue(rates[1] > rates[2] + 0.1, "tier 2 should be harder than tier 1: $rates")
        assertTrue(rates[2] > rates[3] + 0.1, "tier 3 should be harder than tier 2: $rates")
        assertTrue(rates[3] >= 0.12, "even tier 3 must be beatable: $rates")
        assertTrue(rates[2] >= 0.3, "tier 2 should be a fair challenge: $rates")
    }

    // ------------------------------------------------------------------ difficulty

    private fun floors(type: PlanetType, depth: Int, difficulty: Int, seeds: Int = 40): List<Level> =
        (1..seeds).map { DungeonGenerator.generate(TestPlanets.ofType(type), depth, 3, Random(it.toLong()), null, difficulty) }

    @Test
    fun harderTripsHaveMoreToughAndWakefulMonsters() {
        val byTier = (0..3).map { floors(PlanetType.ROCKY, 2, it) }
        val counts = byTier.map { levels -> levels.sumOf { it.monsters.size }.toDouble() / levels.size }
        for (i in 1..3) assertTrue(counts[i] >= counts[i - 1], "monster counts should not fall with difficulty: $counts")
        assertTrue(counts[3] > counts[0], "the hardest trips have more monsters: $counts")

        fun avgHusk(levels: List<Level>) = levels.flatMap { it.monsters }.filter { it.type == MonsterType.HUSK }.map { it.maxHp }.average()
        assertTrue(avgHusk(byTier[3]) > avgHusk(byTier[0]) * 1.15, "husks should be sturdier on hard trips")

        fun asleep(levels: List<Level>): Double {
            val all = levels.flatMap { it.monsters }
            return all.count { it.state == AiState.SLEEPING }.toDouble() / all.size
        }
        assertTrue(asleep(byTier[0]) > asleep(byTier[3]) + 0.1, "fewer monsters sleep on hard trips")

        assertTrue(byTier[3].flatMap { it.monsters }.first().dmgScale > byTier[0].flatMap { it.monsters }.first().dmgScale)
    }

    @Test
    fun theWorldTypeChangesWhatLivesThere() {
        fun share(type: PlanetType, m: MonsterType): Double {
            val all = floors(type, 3, 2).flatMap { it.monsters }.filter { !it.type.boss }
            return all.count { it.type == m }.toDouble() / all.size
        }
        assertTrue(share(PlanetType.LAVA, MonsterType.SPITTER) > share(PlanetType.ROCKY, MonsterType.SPITTER), "lava worlds breed spitters")
        assertTrue(share(PlanetType.TOXIC, MonsterType.SCUTTLER) > share(PlanetType.ROCKY, MonsterType.SCUTTLER), "toxic worlds swarm")
        assertTrue(share(PlanetType.ICE, MonsterType.BRUTE) > share(PlanetType.ROCKY, MonsterType.BRUTE), "ice worlds have brutes")
    }

    @Test
    fun monsterTablesAreSensible() {
        for (type in PlanetType.entries) for (depth in 1..3) for (d in 0..3) {
            val table = RuinsDifficulty.monsterWeights(depth, d, type)
            assertTrue(table.isNotEmpty() && table.all { it.second > 0 }, "$type depth $depth difficulty $d")
        }
        assertTrue(RuinsDifficulty.monsterWeights(1, 3, PlanetType.ICE).none { it.first == MonsterType.BRUTE }, "no brutes on the first floor")
        assertTrue(RuinsDifficulty.monsterWeights(3, 0, PlanetType.ROCKY).none { it.first.boss })
    }

    @Test
    fun hazardPoolsAndLootGrowWithDifficulty() {
        assertTrue(RuinsDifficulty.hazardChance(PlanetType.LAVA, 0) > RuinsDifficulty.hazardChance(PlanetType.ROCKY, 0))
        assertTrue(RuinsDifficulty.hazardChance(PlanetType.LAVA, 3) > RuinsDifficulty.hazardChance(PlanetType.LAVA, 0))
        val calm = floors(PlanetType.ROCKY, 2, 0).sumOf { it.items.size }
        val hard = floors(PlanetType.ROCKY, 2, 3).sumOf { it.items.size }
        assertTrue(hard > calm, "harder trips pay off in loot: $calm vs $hard")
    }
}
