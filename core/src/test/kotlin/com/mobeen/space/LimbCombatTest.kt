package com.mobeen.space

import com.badlogic.gdx.Input.Keys
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LimbCombatTest {
    private val planet = TestPlanets.ofType(PlanetType.ROCKY)

    private fun newRun(rndSeed: Int = 7): Pair<DungeonRun, GameState> {
        val state = GameState()
        return DungeonRun(planet, state, 1L, Random(rndSeed)) to state
    }

    private fun arena(run: DungeonRun, size: Int = 5) {
        val lv = run.level
        lv.monsters.clear()
        lv.items.clear()
        lv.traps.clear()
        java.util.Arrays.fill(lv.lit, false)
        for (y in run.py - size..run.py + size) for (x in run.px - size..run.px + size) {
            if (lv.inBounds(x, y) && lv.tile(x, y) != Tile.STAIRS_UP && lv.tile(x, y) != Tile.STAIRS_DOWN) lv.set(x, y, Tile.FLOOR)
        }
        Fov.compute(lv, run.px, run.py)
    }

    private fun aimAt(run: DungeonRun, part: BodyPart) {
        while (run.aim != part) run.cycleAim()
    }

    /** Swing at an awake, harmless dummy [times] times; returns the damage of each blow that landed. */
    private fun swing(run: DungeonRun, state: GameState, dummy: Monster, times: Int): List<Int> {
        val hits = ArrayList<Int>()
        repeat(times) {
            state.stats.health = 100f
            state.stats.mind = 100f
            dummy.stunned = 0
            val before = dummy.hp
            run.tryMove(dummy.x - run.px, dummy.y - run.py)
            if (dummy.hp < before) hits.add(before - dummy.hp)
        }
        return hits
    }

    @Test
    fun youAimAtTheBodyByDefaultAndChangingAimIsFree() {
        val (run, _) = newRun()
        assertEquals(BodyPart.BODY, run.aim)
        val turn = run.turn
        val seen = ArrayList<BodyPart>()
        repeat(4) { run.cycleAim(); seen.add(run.aim) }
        assertEquals(listOf(BodyPart.HEAD, BodyPart.ARMS, BodyPart.LEGS, BodyPart.BODY), seen)
        assertEquals(turn, run.turn)
    }

    @Test
    fun headshotsMissMoreButHitHarderAndCanStun() {
        fun trial(part: BodyPart): Triple<Int, Double, Int> {
            val (run, state) = newRun(rndSeed = 11)
            arena(run)
            state.loadout.weapon = Gear(GearKind.PIPE_CLUB)
            aimAt(run, part)
            val dummy = Monster(MonsterType.HUSK, run.px + 1, run.py, 10_000_000, 0f, AiState.WANDERING)
            run.level.monsters.add(dummy)
            val hits = swing(run, state, dummy, 400)
            return Triple(hits.size, hits.average(), run.messages.count { it.contains("stunned") } + run.floatTexts.count { it.text == "stunned" })
        }
        val body = trial(BodyPart.BODY)
        val head = trial(BodyPart.HEAD)
        assertTrue(head.first < body.first - 40, "headshots should miss more: ${head.first} vs ${body.first} hits")
        assertTrue(head.second > body.second * 1.3, "headshots should hit harder: ${head.second} vs ${body.second}")
        assertTrue(head.third > 10, "headshots should sometimes stun")
        assertEquals(0, body.third, "body blows never stun")
    }

    @Test
    fun aStunnedMonsterLosesItsTurns() {
        val (run, _) = newRun()
        arena(run)
        val husk = Monster(MonsterType.HUSK, run.px + 1, run.py, 26, 1f, AiState.HUNTING)
        husk.stunned = 2
        run.level.monsters.add(husk)
        run.waitTurn()
        run.waitTurn()
        assertTrue(run.attacks.isEmpty(), "two turns of nothing")
        repeat(10) { run.waitTurn() }
        assertTrue(run.attacks.isNotEmpty(), "then it fights again")
    }

    @Test
    fun hittingArmsAndLegsWoundsAMonster() {
        val (run, state) = newRun(rndSeed = 4)
        arena(run)
        val dummy = Monster(MonsterType.HUSK, run.px + 1, run.py, 10_000_000, 0f, AiState.WANDERING)
        run.level.monsters.add(dummy)
        aimAt(run, BodyPart.ARMS)
        swing(run, state, dummy, 20)
        assertEquals(2, dummy.armWounds, "wounds stop at two")
        aimAt(run, BodyPart.LEGS)
        swing(run, state, dummy, 20)
        assertEquals(2, dummy.legWounds)
    }

    @Test
    fun woundedArmsHitSofter() {
        fun damageTaken(armWounds: Int): Float {
            val (run, state) = newRun(rndSeed = 6)
            arena(run)
            val brute = Monster(MonsterType.BRUTE, run.px + 1, run.py, 10_000_000, 1f, AiState.HUNTING)
            brute.armWounds = armWounds
            run.level.monsters.add(brute)
            var taken = 0f
            repeat(200) {
                state.stats.health = 100f
                state.stats.mind = 100f
                state.stats.clearInjuries()
                run.waitTurn()
                taken += 100f - state.stats.health
            }
            return taken
        }
        assertTrue(damageTaken(2) < damageTaken(0) * 0.7f)
    }

    @Test
    fun woundedLegsSlowAMonsterAndStopAFastOneDoublingUp() {
        fun distanceAfter(legWounds: Int, type: MonsterType, turns: Int): Int {
            val (run, _) = newRun()
            arena(run, 7)
            val m = Monster(type, run.px + 6, run.py, 10_000, 0f, AiState.HUNTING)
            m.lastSeenX = run.px
            m.lastSeenY = run.py
            m.legWounds = legWounds
            run.level.monsters.add(m)
            repeat(turns) { run.waitTurn() }
            return DungeonGenerator.distance(m.x, m.y, run.px, run.py)
        }
        assertTrue(distanceAfter(1, MonsterType.HUSK, 4) > distanceAfter(0, MonsterType.HUSK, 4), "a lame husk closes in slower")
        assertTrue(distanceAfter(1, MonsterType.SCUTTLER, 2) > distanceAfter(0, MonsterType.SCUTTLER, 2), "a lame scuttler is no longer fast")
    }

    @Test
    fun monstersStrikeYourLimbsAndHeadToo() {
        val (run, state) = newRun(rndSeed = 9)
        arena(run)
        run.level.monsters.add(Monster(MonsterType.HUSK, run.px + 1, run.py, 10_000_000, 0.2f, AiState.HUNTING))
        val seen = HashSet<String>()
        repeat(300) {
            state.stats.health = 100f
            state.stats.mind = 100f
            state.stats.clearInjuries()
            val before = run.messages.size
            run.waitTurn()
            for (m in run.messages.drop(before)) {
                if (m.contains("your arm")) seen.add("arm")
                if (m.contains("your leg")) seen.add("leg")
                if (m.contains("across the head")) seen.add("head")
                if (m.contains("hits you")) seen.add("body")
            }
        }
        assertEquals(setOf("arm", "leg", "head", "body"), seen)
    }

    @Test
    fun aBlowToTheHeadLeavesYouDazed() {
        val (run, state) = newRun(rndSeed = 9)
        arena(run)
        run.level.monsters.add(Monster(MonsterType.HUSK, run.px + 1, run.py, 10_000_000, 0.2f, AiState.HUNTING))
        var dazed = false
        repeat(300) {
            if (dazed) return@repeat
            state.stats.health = 100f
            state.stats.mind = 100f
            val before = run.messages.size
            run.waitTurn()
            if (run.messages.drop(before).any { it.contains("across the head") }) dazed = state.stats.mind <= 100f - DungeonRun.HEAD_HIT_DREAD + 0.5f
        }
        assertTrue(dazed)
    }

    @Test
    fun brutesBreakBonesOnlyWhereTheyHit() {
        var arms = 0
        var legs = 0
        for (seed in 1..20) {
            val (run, state) = newRun(rndSeed = seed)
            arena(run)
            run.level.monsters.add(Monster(MonsterType.BRUTE, run.px + 1, run.py, 10_000_000, 0f, AiState.HUNTING))
            repeat(120) {
                state.stats.health = 100f
                state.stats.mind = 100f
                val before = run.messages.size
                run.waitTurn()
                val now = run.messages.drop(before)
                if (now.any { it.contains("arm is broken") }) {
                    arms++
                    assertTrue(now.any { it.contains("hits your arm") }, "an arm breaks only from a blow to the arm")
                }
                if (now.any { it.contains("leg is broken") }) {
                    legs++
                    assertTrue(now.any { it.contains("hits your leg") }, "a leg breaks only from a blow to the leg")
                }
                state.stats.clearInjuries()
            }
        }
        assertTrue(arms > 0 && legs > 0, "arms $arms, legs $legs")
    }

    @Test
    fun theAimKeyAndWoundMarksWorkOnScreen() {
        val h = GameHarness()
        h.frame(2)
        h.game.enterDungeon(planet)
        h.frame(3)
        val run = h.state.dungeon!!
        arena(run)
        h.press(Keys.T)
        assertEquals(BodyPart.HEAD, run.aim)
        val m = Monster(MonsterType.HUSK, run.px + 2, run.py, 26, 1f, AiState.HUNTING)
        m.stunned = 3
        m.armWounds = 1
        m.legWounds = 2
        run.level.monsters.add(m)
        h.frame(30)
        assertTrue(h.screen is DungeonScreen)
    }
}
