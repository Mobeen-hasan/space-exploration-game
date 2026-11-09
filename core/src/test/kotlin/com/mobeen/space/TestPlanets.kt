package com.mobeen.space

import com.badlogic.gdx.graphics.Color

object TestPlanets {
    fun ofType(type: PlanetType, id: Long = 5L, tempC: Int = 20): Planet = Planet(
        id = id, name = "Test", type = type, x = 0f, y = 0f, radius = 150f, gravityG = 1f,
        temperatureC = tempC, atmosphere = "Thin", dayHours = 24f,
        c0 = Color(type.c0[0], type.c0[1], type.c0[2], 1f),
        c1 = Color(type.c1[0], type.c1[1], type.c1[2], 1f),
        c2 = Color(type.c2[0], type.c2[1], type.c2[2], 1f),
        atmoColor = Color(type.atmo[0], type.atmo[1], type.atmo[2], 1f),
        noiseSeed = 1f, spin = 0.05f,
    )
}
