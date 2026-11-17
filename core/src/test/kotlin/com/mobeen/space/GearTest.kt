package com.mobeen.space

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GearTest {
    @Test
    fun qualityChangesStatsAndNames() {
        val club = Gear(GearKind.PIPE_CLUB)
        assertEquals("Pipe club", club.name)
        assertEquals(12, club.dmgMin)
        assertEquals(22, club.dmgMax)
        val fine = Gear(GearKind.PIPE_CLUB, Quality.FINE)
        assertEquals("Fine pipe club", fine.name)
        assertEquals(14, fine.dmgMin)
        assertEquals(24, fine.dmgMax)
        assertTrue(Gear(GearKind.PIPE_CLUB, Quality.WORN).dmgMax < club.dmgMax)

        assertEquals(3, Gear(GearKind.COMBAT_WEAVE).armor)
        assertEquals(4, Gear(GearKind.COMBAT_WEAVE, Quality.RARE).armor, "armor moves at most one point")
        assertEquals(0, Gear(GearKind.SHIP_VEST, Quality.WORN).armor)
        assertEquals(0, Gear(GearKind.PIPE_CLUB).armor, "weapons give no armor")

        assertEquals(0.18f, Gear(GearKind.RIOT_SHIELD).meleeBlock, 0.001f)
        assertEquals(0.26f, Gear(GearKind.RIOT_SHIELD, Quality.RARE).meleeBlock, 0.001f)
        assertEquals(0f, Gear(GearKind.RIOT_SHIELD).rangedBlock, "a riot shield does not stop spit")
        assertTrue(Gear(GearKind.ARC_BUCKLER).rangedBlock > 0f)
        assertTrue(Gear(GearKind.HAZARD_SUIT).describe().contains("lava"))
    }

    @Test
    fun theStartingBatonMatchesTheOldNumbers() {
        val baton = Gear(GearKind.STUN_BATON)
        assertEquals(DungeonRun.BATON_MIN, baton.dmgMin)
        assertEquals(DungeonRun.BATON_MAX, baton.dmgMax)
        assertEquals(DungeonRun.PLAYER_ACCURACY, baton.accuracy)
        assertEquals(DungeonRun.AMBUSH_MULTIPLIER, baton.ambush)
        assertEquals(DungeonRun.STARTING_ARMOR, Gear(GearKind.SHIP_VEST).armor)
    }

    @Test
    fun gearSurvivesEncodingAndDamagedTextIsRejected() {
        for (k in GearKind.entries) for (q in Quality.entries) {
            val g = Gear(k, q)
            assertEquals(g, Gear.decode(g.encode()))
        }
        assertNull(Gear.decode(""))
        assertNull(Gear.decode("PIPE_CLUB"))
        assertNull(Gear.decode("LASER:FINE"))
        assertNull(Gear.decode("PIPE_CLUB:SHINY"))
    }

    @Test
    fun loadoutStartsWithBatonAndVestAndSwapsFromTheBag() {
        val lo = Loadout()
        assertEquals(GearKind.STUN_BATON, lo.weapon.kind)
        assertEquals(GearKind.SHIP_VEST, lo.armor.kind)
        assertNull(lo.shield)

        lo.bag.add(Gear(GearKind.ARC_SABER, Quality.FINE))
        lo.bag.add(Gear(GearKind.RIOT_SHIELD))
        assertTrue(lo.equipFromBag(0))
        assertEquals(GearKind.ARC_SABER, lo.weapon.kind)
        assertTrue(lo.bag.any { it.kind == GearKind.STUN_BATON }, "the old weapon goes into the bag")
        val shieldIndex = lo.bag.indexOfFirst { it.kind == GearKind.RIOT_SHIELD }
        assertTrue(lo.equipFromBag(shieldIndex))
        assertEquals(GearKind.RIOT_SHIELD, lo.shield?.kind)
        assertEquals(1, lo.bag.size, "no old shield to put back")
        assertFalse(lo.equipFromBag(5))
    }

    @Test
    fun loadoutSurvivesEncodingAndDamagedTextKeepsTheStartingGear() {
        val a = Loadout()
        a.bag.add(Gear(GearKind.PIPE_CLUB, Quality.WORN))
        a.bag.add(Gear(GearKind.HAZARD_SUIT, Quality.RARE))
        a.equipFromBag(1)
        a.shield = Gear(GearKind.ARC_BUCKLER, Quality.FINE)
        val b = Loadout()
        b.restore(a.encode())
        assertEquals(a.weapon, b.weapon)
        assertEquals(a.armor, b.armor)
        assertEquals(a.shield, b.shield)
        assertEquals(a.bag, b.bag)

        val c = Loadout()
        c.restore("nonsense")
        assertEquals(GearKind.STUN_BATON, c.weapon.kind)
        c.restore("RIOT_SHIELD:FINE;PIPE_CLUB:FINE;|")
        assertEquals(GearKind.STUN_BATON, c.weapon.kind, "a shield cannot be equipped as a weapon")
    }

    @Test
    fun deeperFloorsAndHarderJobsTurnUpBetterGear() {
        fun avgStep(depth: Int, difficulty: Int): Double {
            val rnd = Random(9)
            return (1..3000).map { Loot.rollQuality(depth, difficulty, rnd).step }.average()
        }
        assertTrue(avgStep(3, 3) > avgStep(1, 0) + 0.2)
        val rnd = Random(4)
        repeat(500) {
            assertFalse(Loot.rollGear(2, 1, rnd).kind.starter, "you never find the starting gear")
            val trophy = Loot.rollTrophy(rnd)
            assertTrue(trophy.quality == Quality.FINE || trophy.quality == Quality.RARE)
        }
    }

    @Test
    fun theShopSellsIntoTheLockerUntilItIsFull() {
        val s = GameState()
        s.locker.clear()
        s.stats.credits = 100
        assertTrue(Shop.buy(s, ItemType.MEDKIT))
        assertEquals(80, s.stats.credits)
        assertEquals(listOf(ItemType.MEDKIT), s.locker)
        assertFalse(Shop.buy(s, ItemType.CREDITS), "not for sale")
        repeat(10) { Shop.buy(s, ItemType.RATION) }
        assertEquals(s.lockerSize, s.locker.size)
        assertFalse(Shop.canBuy(s, ItemType.RATION))
        s.locker.clear()
        s.stats.credits = 5
        assertFalse(Shop.buy(s, ItemType.MEDKIT), "not enough credits")
    }

    @Test
    fun loadoutAndLockerAreSavedAndOldSavesKeepDefaults() {
        val a = GameState()
        a.loadout.bag.add(Gear(GearKind.COMBAT_WEAVE, Quality.FINE))
        a.loadout.equipFromBag(0)
        a.loadout.bag.add(Gear(GearKind.SHOCK_KNIFE))
        a.locker.clear()
        a.locker.addAll(listOf(ItemType.TONIC, ItemType.OIL_FLASK))
        val b = GameState()
        assertTrue(SaveCodec.decode(b, SaveCodec.encode(a)))
        assertEquals(a.loadout.armor, b.loadout.armor)
        assertEquals(a.loadout.bag, b.loadout.bag)
        assertEquals(listOf(ItemType.TONIC, ItemType.OIL_FLASK), b.locker)

        val old = SaveCodec.encode(a).filterKeys { it != "loadout" && it != "locker" }
        val c = GameState()
        assertTrue(SaveCodec.decode(c, old))
        assertEquals(GearKind.STUN_BATON, assertNotNull(c.loadout.weapon).kind)
        assertEquals(GameState.STARTING_KIT, c.locker, "a new game's starting kit")
    }
}
