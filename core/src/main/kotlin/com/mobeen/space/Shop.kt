package com.mobeen.space

/** The ship terminal's supply shop: buy consumables into the locker, ready for your next trip. */
object Shop {
    val prices: Map<ItemType, Int> = linkedMapOf(
        ItemType.MEDKIT to 20,
        ItemType.TONIC to 25,
        ItemType.OIL_FLASK to 15,
        ItemType.STIM to 15,
        ItemType.RATION to 8,
        ItemType.BANDAGE to 10,
        ItemType.SPLINT to 20,
        ItemType.ANTISEPTIC to 25,
    )

    fun canBuy(state: GameState, type: ItemType): Boolean {
        val price = prices[type] ?: return false
        return state.stats.credits >= price && state.locker.size < state.lockerSize
    }

    fun buy(state: GameState, type: ItemType): Boolean {
        if (!canBuy(state, type)) return false
        state.stats.credits -= prices.getValue(type)
        state.locker.add(type)
        return true
    }
}
