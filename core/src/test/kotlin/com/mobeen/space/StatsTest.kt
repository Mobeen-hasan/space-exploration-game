package com.mobeen.space

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatsTest {
    private fun planet(type: PlanetType, tempC: Int): Planet {
        // Reuse a generated planet, then rebuild it with the type and temperature we want.
        val base = PlanetGenerator.forChunk(0, 0)!!
        return Planet(
            base.id, base.name, type, base.x, base.y, base.radius, base.gravityG, tempC, base.atmosphere,
            base.dayHours, base.c0, base.c1, base.c2, base.atmoColor, base.noiseSeed, base.spin,
        )
    }

    @Test
    fun hungerAndTirednessDrainOverTimeAndClamp() {
        val s = Stats()
        repeat(60) { s.tickBody(1f) }
        assertTrue(s.energy < 100f && s.food < 100f)
        repeat(100000) { s.tickBody(1f) }
        assertEquals(0f, s.food)
        assertEquals(0f, s.energy)
        assertTrue(s.collapsed)
    }

    @Test
    fun starvingHurtsAndFullBellyHeals() {
        val s = Stats()
        s.food = 0f
        s.tickBody(10f)
        assertTrue(s.health < 100f)

        val t = Stats()
        t.health = 50f
        t.tickBody(10f)
        assertTrue(t.health > 50f)
    }

    @Test
    fun breathableWorldRefillsAirAndHarshWorldDrainsIt() {
        val s = Stats()
        s.air = 50f
        s.tickSurface(2f, planet(PlanetType.OCEAN, 20), Liquid.NONE, false)
        assertTrue(s.air > 50f)

        s.air = 100f
        s.tickSurface(10f, planet(PlanetType.TOXIC, 20), Liquid.NONE, false)
        assertEquals(100f - 10f * Hazards.airDrain(PlanetType.TOXIC), s.air, 0.001f)
    }

    @Test
    fun emptyAirHurtsAndHarshWorldsAreWorseThanThinOnes() {
        assertTrue(Hazards.airDrain(PlanetType.LAVA) > Hazards.airDrain(PlanetType.ROCKY))
        assertEquals(0f, Hazards.airDrain(PlanetType.OCEAN))

        val s = Stats()
        s.air = 0f
        s.tickSurface(5f, planet(PlanetType.ROCKY, 20), Liquid.NONE, false)
        assertTrue(s.suffocating)
        assertTrue(s.health < 100f)
    }

    @Test
    fun temperatureOnlyHurtsOutsideTheComfortRange() {
        assertEquals(0f, Hazards.exposureDamage(20))
        assertEquals(0f, Hazards.exposureDamage(-40))
        assertEquals(0f, Hazards.exposureDamage(45))
        assertTrue(Hazards.exposureDamage(120) > 0f)
        assertTrue(Hazards.exposureDamage(-120) > 0f)
        assertTrue(Hazards.exposureDamage(900) <= 1f)
        // Just past the limit already outweighs natural regeneration; the extremes hurt more.
        assertTrue(Hazards.exposureDamage(-41) > Stats.REGEN)
        assertTrue(Hazards.exposureDamage(46) > Stats.REGEN)
        assertTrue(Hazards.exposureDamage(-150) > Hazards.exposureDamage(-50))
    }

    @Test
    fun coldAndHeatBothCostHealthEvenWhenFedAndRested() {
        // Regression: mild cold used to be cancelled out by natural regeneration.
        for (temp in listOf(-41, -50, -60, -100, -150, 46, 55, 80, 110, 500)) {
            val s = Stats()
            s.health = 80f
            repeat(600) { // ten seconds at 60 frames per second
                s.tickBody(1f / 60f)
                s.tickSurface(1f / 60f, planet(PlanetType.ROCKY, temp), Liquid.NONE, false)
            }
            assertTrue(s.health < 80f, "at $temp C health should fall but was ${s.health}")
        }
    }

    @Test
    fun comfortableTemperatureAllowsRegenerationAndHazardsStopItOnlyWhileActive() {
        val calm = Stats()
        calm.health = 50f
        repeat(600) {
            calm.tickBody(1f / 60f)
            calm.tickSurface(1f / 60f, planet(PlanetType.OCEAN, 20), Liquid.NONE, false)
        }
        assertTrue(calm.health > 50f)

        // Leave the cold: regeneration resumes once nothing is hurting you.
        val s = Stats()
        s.health = 50f
        repeat(120) {
            s.tickBody(1f / 60f)
            s.tickSurface(1f / 60f, planet(PlanetType.ICE, -100), Liquid.NONE, false)
        }
        val hurt = s.health
        assertTrue(hurt < 50f)
        repeat(600) { s.tickBody(1f / 60f) }
        assertTrue(s.health > hurt)
    }

    @Test
    fun lavaAndAcidHurtOnlyWhenSubmerged() {
        val p = planet(PlanetType.OCEAN, 20)
        val dry = Stats().also { it.tickSurface(1f, p, Liquid.LAVA, false) }
        assertEquals(100f, dry.health)
        val lava = Stats().also { it.tickSurface(1f, p, Liquid.LAVA, true) }
        assertEquals(100f - Hazards.LAVA_DPS, lava.health, 0.001f)
        val acid = Stats().also { it.tickSurface(1f, p, Liquid.ACID, true) }
        assertEquals(100f - Hazards.ACID_DPS, acid.health, 0.001f)
        val water = Stats().also { it.tickSurface(1f, p, Liquid.WATER, true) }
        assertEquals(100f, water.health)
    }

    @Test
    fun collapsingCostsCreditsButNeverLeavesYouDeadOrBroken() {
        val s = Stats()
        s.credits = 200
        s.health = 0f
        assertTrue(s.collapsed)
        val msg = s.applyCollapse()
        assertEquals(160, s.credits)
        assertFalse(s.collapsed)
        assertTrue(s.health >= 40f && s.energy >= 40f && s.food >= 30f)
        assertTrue(msg.contains("40"))
    }

    @Test
    fun shopActionsChargeCreditsAndRespectLimits() {
        val s = Stats()
        s.food = 30f
        assertTrue(s.eat())
        assertEquals(70f, s.food, 0.001f)
        assertEquals(295, s.credits)
        s.food = 100f
        assertFalse(s.eat())

        s.fuel = 10f
        assertTrue(s.refuel())
        assertEquals(35f, s.fuel, 0.001f)
        assertEquals(270, s.credits)

        s.hull = 60f
        assertTrue(s.repair())
        assertEquals(85f, s.hull, 0.001f)
        assertEquals(240, s.credits)

        s.credits = 0
        s.fuel = 10f
        assertFalse(s.refuel())
    }

    @Test
    fun emergencyFuelIsFreeAndOnlyWhenNearlyEmpty() {
        val s = Stats()
        s.credits = 0
        s.fuel = 50f
        assertFalse(s.emergencyFuel())
        s.fuel = 0f
        assertTrue(s.emergencyFuel())
        assertEquals(Stats.EMERGENCY_FUEL, s.fuel)
    }

    @Test
    fun sleepingRestoresEnergyAndStartsANewDay() {
        val s = Stats()
        s.energy = 5f
        s.food = 50f
        s.health = 40f
        s.sleep()
        assertEquals(100f, s.energy)
        assertEquals(35f, s.food, 0.001f)
        assertEquals(70f, s.health, 0.001f)
        assertEquals(2, s.day)
    }
}
