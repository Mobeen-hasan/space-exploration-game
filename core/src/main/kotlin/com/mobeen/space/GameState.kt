package com.mobeen.space

/** State that must survive switching between the space screen and a planet surface. */
class GameState {
    val ship = Ship()
    val field = PlanetField()
    val discovered = HashSet<Long>()
}
