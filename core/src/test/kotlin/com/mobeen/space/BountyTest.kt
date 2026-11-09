package com.mobeen.space

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BountyTest {
    private fun stateAtStart(): GameState = GameState()

    private fun contract(id: Long, planetId: Long = 1L, tier: Int = 1) =
        Contract(id, "Test 'Ghost' Target", "broke out of prison", planetId, "Kerata IV", PlanetType.ROCKY, 2500, 300, tier)

    @Test
    fun generatorOffersDistinctNearbyPlanetsAndIsRepeatablePerDay() {
        val s = stateAtStart()
        val a = BountyGenerator.generate(s, 1)
        val b = BountyGenerator.generate(s, 1)
        assertTrue(a.isNotEmpty())
        assertTrue(a.size <= 4)
        assertEquals(a.size, a.map { it.planetId }.toSet().size, "each contract should use a different planet")
        assertEquals(a.map { it.encode() }, b.map { it.encode() }, "same day and place give the same list")
        val tomorrow = BountyGenerator.generate(s, 2)
        assertFalse(a.map { it.encode() } == tomorrow.map { it.encode() })
        for (c in a) {
            assertNotNull(SaveCodec.planetFromId(c.planetId), "the target planet must exist")
            assertEquals(c.planetName, SaveCodec.planetFromId(c.planetId)?.name)
            assertTrue(c.tier in 1..3)
            assertTrue(c.reward >= 250)
        }
    }

    @Test
    fun generatorSkipsAnExcludedPlanet() {
        val s = stateAtStart()
        val first = BountyGenerator.generate(s, 1).first()
        val without = BountyGenerator.generate(s, 1, exclude = first.planetId)
        assertTrue(without.none { it.planetId == first.planetId })
    }

    @Test
    fun harderTiersFartherTripsAndHarsherWorldsPayMore() {
        assertTrue(BountyGenerator.reward(2, 1000, PlanetType.ROCKY) > BountyGenerator.reward(1, 1000, PlanetType.ROCKY))
        assertTrue(BountyGenerator.reward(3, 1000, PlanetType.ROCKY) > BountyGenerator.reward(2, 1000, PlanetType.ROCKY))
        assertTrue(BountyGenerator.reward(1, 6000, PlanetType.ROCKY) > BountyGenerator.reward(1, 500, PlanetType.ROCKY))
        assertTrue(BountyGenerator.reward(1, 1000, PlanetType.LAVA) > BountyGenerator.reward(1, 1000, PlanetType.OCEAN))
        assertEquals(0, BountyGenerator.reward(1, 1000, PlanetType.OCEAN) % 5)
    }

    @Test
    fun contractsSurviveEncodingAndDamagedTextIsRejected() {
        val c = contract(42L, planetId = -4294967290L, tier = 3)
        val back = assertNotNull(Contract.decode(c.encode()))
        assertEquals(c.id, back.id)
        assertEquals(c.target, back.target)
        assertEquals(c.planetId, back.planetId)
        assertEquals(c.planetType, back.planetType)
        assertEquals(c.reward, back.reward)
        assertEquals(c.tier, back.tier)

        assertNull(Contract.decode(""))
        assertNull(Contract.decode("a|b|c"))
        assertNull(Contract.decode("x|n|c|1|p|ROCKY|1|1|1"))
        assertNull(Contract.decode("1|n|c|1|p|NOT_A_TYPE|1|1|1"))
    }

    @Test
    fun boardAcceptsOneContractAtATime() {
        val board = BountyBoard()
        val a = contract(1L)
        val b = contract(2L)
        board.available.addAll(listOf(a, b))

        assertTrue(board.accept(a))
        assertEquals(a, board.active)
        assertFalse(board.available.contains(a))
        assertFalse(board.accept(b), "only one active contract")
        assertFalse(board.accept(contract(3L)), "cannot accept something that is not listed")

        board.abandon()
        assertNull(board.active)
        assertFalse(board.available.contains(a), "an abandoned contract does not come back")
        assertTrue(board.accept(b))
    }

    @Test
    fun boardRefreshesOnANewDayButNotBeforeAndKeepsTheActiveContract() {
        val s = stateAtStart()
        s.bounties.refreshIfNeeded(s)
        val day1 = s.bounties.available.map { it.id }
        assertTrue(day1.isNotEmpty())
        s.bounties.refreshIfNeeded(s)
        assertEquals(day1, s.bounties.available.map { it.id }, "same day: same list")

        val taken = s.bounties.available.first()
        assertTrue(s.bounties.accept(taken))
        s.stats.sleep() // a new day
        s.bounties.refreshIfNeeded(s)
        assertEquals(taken, s.bounties.active, "the accepted contract stays")
        assertTrue(s.bounties.available.none { it.planetId == taken.planetId }, "no second job on the same planet")
        assertFalse(s.bounties.available.map { it.id } == day1)
    }

    @Test
    fun completingTheActiveContractCountsOnceAndIgnoresOthers() {
        val board = BountyBoard()
        val a = contract(1L)
        board.available.add(a)
        board.accept(a)
        board.completeActive(contract(99L))
        assertEquals(0, board.completedCount)
        assertNotNull(board.active)
        board.completeActive(a)
        assertEquals(1, board.completedCount)
        assertNull(board.active)
        board.completeActive(a)
        assertEquals(1, board.completedCount)
    }

    @Test
    fun bountiesAreSavedAndLoaded() {
        val a = stateAtStart()
        a.bounties.refreshIfNeeded(a)
        val taken = a.bounties.available.first()
        a.bounties.accept(taken)
        val listedBefore = a.bounties.available.map { it.id }

        val b = GameState()
        assertTrue(SaveCodec.decode(b, SaveCodec.encode(a)))
        assertEquals(taken.id, b.bounties.active?.id)
        assertEquals(taken.target, b.bounties.active?.target)
        assertEquals(listedBefore, b.bounties.available.map { it.id })
        assertEquals(a.bounties.boardDay, b.bounties.boardDay)
    }

    @Test
    fun oldSavesWithoutBountiesStillLoad() {
        val a = stateAtStart()
        val old = SaveCodec.encode(a).filterKeys { !it.startsWith("bounty") }
        val b = GameState()
        assertTrue(SaveCodec.decode(b, old))
        assertNull(b.bounties.active)
        assertTrue(b.bounties.available.isEmpty())
    }
}
