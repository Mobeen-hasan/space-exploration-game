package com.mobeen.space

import com.badlogic.gdx.Input.Keys
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InjuryTrapEventTest {
    private val planet = TestPlanets.ofType(PlanetType.ROCKY)

    private fun newRun(rndSeed: Int = 7, seed: Long = 1L): Pair<DungeonRun, GameState> {
        val state = GameState()
        return DungeonRun(planet, state, seed, Random(rndSeed)) to state
    }

    /** An open arena with no monsters, items, traps or brazier light around the player. */
    private fun arena(run: DungeonRun, size: Int = 4) {
        val lv = run.level
        lv.monsters.clear()
        lv.items.clear()
        lv.traps.clear()
        lv.knownTraps.clear()
        java.util.Arrays.fill(lv.lit, false)
        for (y in run.py - size..run.py + size) for (x in run.px - size..run.px + size) {
            if (lv.inBounds(x, y) && lv.tile(x, y) != Tile.STAIRS_UP && lv.tile(x, y) != Tile.STAIRS_DOWN) lv.set(x, y, Tile.FLOOR)
        }
        Fov.compute(lv, run.px, run.py)
    }

    // ------------------------------------------------------------------ injuries in the body

    @Test
    fun bleedingDrainsHealthThenClots() {
        val s = Stats()
        s.bleeding = Stats.BLEED_TIME
        s.health = 80f
        s.tickBody(5f)
        assertTrue(s.health < 80f)
        repeat(20) { s.tickBody(1f) }
        assertEquals(0f, s.bleeding, "it clots in time")
        val after = s.health
        s.tickBody(10f)
        assertTrue(s.health >= after, "no more bleeding once it has clotted")
    }

    @Test
    fun infectionSlowsHealingAndWearsOnTheMind() {
        val clean = Stats().also { it.health = 50f; it.tickBody(10f) }
        val sick = Stats().also { it.health = 50f; it.infected = true; it.tickBody(10f) }
        assertTrue(sick.health < clean.health, "an infection slows healing")
        assertTrue(sick.health > 50f, "but does not stop it")
        assertTrue(sick.mind < clean.mind)
    }

    @Test
    fun sleepSetsBonesAndStopsBleedingButNotInfection() {
        val s = Stats()
        s.bleeding = 10f; s.brokenArm = true; s.brokenLeg = true; s.infected = true
        s.sleep()
        assertEquals(0f, s.bleeding)
        assertFalse(s.brokenArm || s.brokenLeg)
        assertTrue(s.infected, "an infection needs antiseptic")
        assertEquals(listOf("INFECTED"), s.injuryTags())
        s.refillAll()
        assertFalse(s.injured, "the refill cheat clears injuries")
    }

    @Test
    fun injuriesAreSavedAndOldSavesHaveNone() {
        val a = GameState()
        a.stats.bleeding = 7f; a.stats.infected = true; a.stats.brokenLeg = true
        val b = GameState()
        assertTrue(SaveCodec.decode(b, SaveCodec.encode(a)))
        assertEquals(7f, b.stats.bleeding)
        assertTrue(b.stats.infected && b.stats.brokenLeg && !b.stats.brokenArm)
        val old = SaveCodec.encode(a).filterKeys { it !in setOf("bleeding", "infected", "brokenArm", "brokenLeg") }
        val c = GameState()
        assertTrue(SaveCodec.decode(c, old))
        assertFalse(c.stats.injured)
    }

    @Test
    fun theShipTreatsInjuriesWithLockerSupplies() {
        val s = GameState()
        s.locker.clear()
        s.locker.addAll(listOf(ItemType.BANDAGE, ItemType.SPLINT, ItemType.ANTISEPTIC))
        s.stats.bleeding = 9f; s.stats.infected = true; s.stats.brokenLeg = true; s.stats.brokenArm = true
        val done = s.treatFromLocker()
        assertEquals(3, done.size)
        assertEquals(0f, s.stats.bleeding)
        assertFalse(s.stats.infected || s.stats.brokenLeg)
        assertTrue(s.stats.brokenArm, "only one splint")
        assertTrue(s.locker.isEmpty())
        assertTrue(s.treatFromLocker().isEmpty(), "nothing left to treat with")
    }

    // ------------------------------------------------------------------ injuries in the ruins

    @Test
    fun brutesAndHusksLeaveInjuries() {
        var bled = 0
        var broken = 0
        for (seed in 1..20) {
            val (run, state) = newRun(rndSeed = seed)
            arena(run)
            run.level.monsters.add(Monster(MonsterType.BRUTE, run.px + 1, run.py, 1_000_000, 0f, AiState.HUNTING))
            repeat(80) {
                state.stats.health = 100f
                state.stats.mind = 100f
                run.waitTurn()
            }
            if (run.messages.any { it.contains("bleeding") } || state.stats.bleeding > 0f) bled++
            if (state.stats.brokenArm || state.stats.brokenLeg) broken++
        }
        assertTrue(bled > 0 && broken > 0, "bled $bled, broken $broken")
    }

    @Test
    fun goodArmorMakesInjuriesRarer() {
        fun injuries(armor: Gear): Int {
            var count = 0
            for (seed in 1..30) {
                val (run, state) = newRun(rndSeed = seed)
                arena(run)
                state.loadout.armor = armor
                run.level.monsters.add(Monster(MonsterType.HUSK, run.px + 1, run.py, 1_000_000, 0f, AiState.HUNTING))
                repeat(60) {
                    state.stats.health = 100f
                    state.stats.mind = 100f
                    state.stats.clearInjuries()
                    val before = run.messages.size
                    run.waitTurn()
                    count += run.messages.drop(before).count { it.contains("bleeding") || it.contains("infected") }
                }
            }
            return count
        }
        assertTrue(injuries(Gear(GearKind.COMBAT_WEAVE, Quality.RARE)) < injuries(Gear(GearKind.SHIP_VEST)))
    }

    @Test
    fun aBrokenArmWeakensYourBlows() {
        val (run, state) = newRun(rndSeed = 3)
        arena(run)
        state.loadout.weapon = Gear(GearKind.PIPE_CLUB)
        state.stats.brokenArm = true
        val dummy = Monster(MonsterType.HUSK, run.px + 1, run.py, 1_000_000, 0f, AiState.WANDERING)
        run.level.monsters.add(dummy)
        val hits = ArrayList<Int>()
        repeat(100) {
            state.stats.health = 100f
            state.stats.mind = 100f
            val before = dummy.hp
            run.tryMove(1, 0)
            if (dummy.hp < before) hits.add(before - dummy.hp)
        }
        assertTrue(hits.isNotEmpty() && hits.all { it in 9..16 }, "a broken arm cuts pipe club blows to 75%: ${hits.distinct().sorted()}")
    }

    @Test
    fun aBrokenLegMakesEveryOtherStepCostAnExtraTurn() {
        val (run, state) = newRun()
        arena(run)
        state.stats.brokenLeg = true
        val turn = run.turn
        run.tryMove(1, 0)
        run.tryMove(1, 0)
        assertEquals(turn + 3, run.turn, "two steps cost three turns")
    }

    @Test
    fun treatmentItemsFixInjuriesAndAreNotWastedOtherwise() {
        val (run, state) = newRun()
        arena(run)
        run.pack.clear()
        run.pack.addAll(listOf(ItemType.BANDAGE, ItemType.SPLINT, ItemType.ANTISEPTIC))
        assertFalse(run.useItem(0), "not bleeding: the bandage is kept")
        assertEquals(3, run.pack.size)

        state.stats.bleeding = 10f; state.stats.brokenLeg = true; state.stats.brokenArm = true; state.stats.infected = true
        assertTrue(run.useItem(run.pack.indexOf(ItemType.BANDAGE)))
        assertEquals(0f, state.stats.bleeding)
        assertTrue(run.useItem(run.pack.indexOf(ItemType.SPLINT)))
        assertFalse(state.stats.brokenLeg, "the leg is set first")
        assertTrue(state.stats.brokenArm)
        assertTrue(run.useItem(run.pack.indexOf(ItemType.ANTISEPTIC)))
        assertFalse(state.stats.infected)

        run.pack.add(ItemType.MEDKIT)
        state.stats.bleeding = 10f
        run.useItem(run.pack.indexOf(ItemType.MEDKIT))
        assertEquals(0f, state.stats.bleeding, "a medkit also stops bleeding")
    }

    // ------------------------------------------------------------------ traps

    @Test
    fun floorsHaveHiddenTrapsAwayFromTheStairsAndArrival() {
        var total = 0
        for (seed in 1L..20L) for (depth in 1..3) {
            val lv = DungeonGenerator.generate(planet, depth, 3, Random(seed), null, 2)
            total += lv.traps.size
            assertTrue(lv.knownTraps.isEmpty(), "traps start hidden")
            for (i in lv.traps.keys) {
                val x = i % lv.width
                val y = i / lv.width
                assertEquals(Tile.FLOOR, lv.tile(x, y))
                assertTrue(DungeonGenerator.distance(x, y, lv.upX, lv.upY) > 1)
                assertTrue(lv.itemAt(x, y) == null && lv.monsterAt(x, y) == null)
            }
        }
        assertTrue(total > 20)
    }

    @Test
    fun spikesHurtAndCauseBleedingThenAreGone() {
        val (run, state) = newRun()
        arena(run)
        val lv = run.level
        lv.traps[lv.index(run.px + 1, run.py)] = TrapType.SPIKES
        val before = state.stats.health
        run.tryMove(1, 0)
        assertTrue(state.stats.health < before)
        assertTrue(state.stats.bleeding > 0f)
        assertNull(lv.trapAt(run.px, run.py), "a sprung trap is spent")
    }

    @Test
    fun noxiousVentsShakeTheMindAndAlarmsWakeTheRuins() {
        val (run, state) = newRun()
        arena(run, 6)
        val lv = run.level
        lv.traps[lv.index(run.px + 1, run.py)] = TrapType.NOXIOUS
        run.tryMove(1, 0)
        assertTrue(state.stats.mind <= Stats.MAX - 15f + 1f)

        val sleeper = Monster(MonsterType.HUSK, run.px + 5, run.py, 26, 1f, AiState.SLEEPING)
        lv.monsters.add(sleeper)
        lv.traps[lv.index(run.px + 1, run.py)] = TrapType.ALARM
        run.tryMove(1, 0)
        assertEquals(AiState.HUNTING, sleeper.state)
    }

    @Test
    fun youCanSpotNearbyTrapsAndRouteAroundThem() {
        val (run, _) = newRun(rndSeed = 5)
        arena(run, 5)
        val lv = run.level
        val trap = lv.index(run.px + 2, run.py)
        lv.traps[trap] = TrapType.PIT
        repeat(30) { if (trap !in lv.knownTraps) run.waitTurn() }
        assertTrue(trap in lv.knownTraps, "standing near it for a while, you should spot it")
        val route = run.pathTo(run.px + 4, run.py)!!
        assertTrue(route.none { lv.index(it[0], it[1]) == trap }, "tapping to walk goes around a known trap")
    }

    // ------------------------------------------------------------------ grim events

    @Test
    fun eventsAppearOnSomeFloors() {
        var events = 0
        for (seed in 1L..30L) events += DungeonGenerator.generate(planet, 2, 3, Random(seed)).items.count { it.type == ItemType.EVENT }
        assertTrue(events > 10)
    }

    private fun standOnEvent(kind: GrimEvent, rndSeed: Int = 7): Pair<DungeonRun, GameState> {
        val (run, state) = newRun(rndSeed = rndSeed)
        arena(run)
        run.level.items.add(FloorItem(ItemType.EVENT, run.px + 1, run.py, kind.ordinal))
        run.tryMove(1, 0)
        assertEquals(kind, run.pendingEvent)
        return run to state
    }

    @Test
    fun anEventWaitsForAChoiceAndBlocksOtherActions() {
        val (run, _) = standOnEvent(GrimEvent.CORPSE)
        val turn = run.turn
        assertFalse(run.tryMove(1, 0))
        run.waitTurn()
        assertEquals(turn, run.turn, "nothing happens until you choose")
        assertTrue(run.chooseEvent(run.eventOptions().lastIndex)) // leave it
        assertNull(run.pendingEvent)
        assertTrue(run.level.items.any { it.type == ItemType.EVENT }, "leaving it keeps it there for later")
        assertEquals(turn, run.turn, "walking away costs no turn")
    }

    @Test
    fun someChoicesNeedSomething() {
        val (run, state) = standOnEvent(GrimEvent.SCAVENGER)
        run.pack.clear()
        assertFalse(run.eventOptions()[0].second, "no medkit to give")
        assertFalse(run.chooseEvent(0))
        run.pack.add(ItemType.MEDKIT)
        assertTrue(run.eventOptions()[0].second)
        val credits = state.stats.credits
        assertTrue(run.chooseEvent(0))
        assertTrue(ItemType.MEDKIT !in run.pack)
        assertEquals(credits + 25, state.stats.credits)
        assertTrue(run.level.explored.all { it }, "they sketch the floor for you")
        assertTrue(run.level.items.none { it.type == ItemType.EVENT }, "a resolved event is gone")

        val (run2, state2) = standOnEvent(GrimEvent.LOCKER)
        state2.stats.credits = 5
        assertFalse(run2.eventOptions()[0].second, "not enough credits for the coin slot")
    }

    @Test
    fun outcomesAreUncertain() {
        var good = 0
        var bad = 0
        for (seed in 1..30) {
            val (run, state) = standOnEvent(GrimEvent.SHRINE, rndSeed = seed)
            state.stats.mind = 50f
            run.chooseEvent(0) // pray
            if (state.stats.mind > 60f) good++ else if (state.stats.mind < 40f) bad++
        }
        assertTrue(good > 3 && bad > 3, "praying should sometimes help and sometimes hurt: good $good, bad $bad")
    }

    @Test
    fun theEventScreenOpensInTheRuinsAndAChoiceCanBeMade() {
        val h = GameHarness()
        h.frame(2)
        h.game.enterDungeon(planet)
        h.frame(3)
        val run = h.state.dungeon!!
        arena(run)
        run.level.items.add(FloorItem(ItemType.EVENT, run.px + 1, run.py, GrimEvent.BLACK_POOL.ordinal))
        h.hold(Keys.D, 1)
        h.frame(2)
        assertEquals(GrimEvent.BLACK_POOL, run.pendingEvent)
        h.press(Keys.DOWN) // from "Reach in" to "Leave it"
        h.press(Keys.ENTER)
        h.frame(2)
        assertNull(run.pendingEvent)
        h.frame(20)
        assertTrue(h.screen is DungeonScreen)
    }

    @Test
    fun theShopSellsTreatment() {
        assertTrue(Shop.prices.keys.containsAll(listOf(ItemType.BANDAGE, ItemType.SPLINT, ItemType.ANTISEPTIC)))
        assertTrue(ItemType.BANDAGE in GameState.STARTING_KIT, "a new game starts with a bandage")
    }
}
