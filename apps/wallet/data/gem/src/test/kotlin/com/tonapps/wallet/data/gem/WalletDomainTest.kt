package com.tonapps.wallet.data.gem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WalletDomainTest {
	@Test
	fun `chain account identity includes chain and provider`() {
		val walletId = WalletId("wallet")
		val ethereum = ChainAccount(walletId, Chain.Ethereum, "same-address")
		val smartChain = ChainAccount(walletId, Chain.SmartChain, "same-address")

		assertNotEquals(ethereum.identityKey, smartChain.identityKey)
		assertEquals("wallet:ethereum:gem:same-address", ethereum.identityKey)
		assertEquals("wallet:smartchain:gem:same-address", smartChain.identityKey)
	}

	@Test
	fun `asset cache key includes chain and provider`() {
		val ethereum = AssetId(Chain.Ethereum, "0xasset")
		val smartChain = AssetId(Chain.SmartChain, "0xasset")

		assertNotEquals(ethereum.cacheKey, smartChain.cacheKey)
		assertEquals("ethereum:gem:0xasset", ethereum.cacheKey)
	}

	@Test
	fun `transaction cache key includes wallet chain and provider`() {
		val transaction = TransactionRecord(
			walletId = WalletId("wallet"),
			chain = Chain.Bitcoin,
			assetId = AssetId(Chain.Bitcoin, "native"),
			hash = "hash",
			amount = "1",
		)

		assertEquals("wallet:bitcoin:gem:hash", transaction.cacheKey)
	}

	@Test
	fun `string amounts preserve large and fractional values`() {
		val amount = "123456789012345678901234567890.123456789012345678"

		assertEquals(amount, Balance(amount).amount)
		assertEquals(amount, TransactionDraft(WalletId("wallet"), Chain.Ton, AssetId(Chain.Ton, "native"), "from", "to", amount).amount)
	}
}
