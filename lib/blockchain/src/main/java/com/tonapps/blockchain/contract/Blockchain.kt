package com.tonapps.blockchain.contract

enum class Blockchain(
    val id: String
) {
    TON("TON"),
    TRON("TRON"),
    GEM("GEM");
}

val Blockchain.mainCoin: CoinType
    get() = when (this) {
        Blockchain.TON -> CoinType.Ton
        Blockchain.TRON -> CoinType.Tron
        Blockchain.GEM -> error("Gem assets do not use Tonkeeper coin types")
    }
