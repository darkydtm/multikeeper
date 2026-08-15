package com.tonapps.wallet.data.gem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GemTokenRepositoryTest {
	@Test
	fun `upsert replaces the same wallet chain and asset`() {
		val storage = InMemoryTokenStorage()
		val repository = GemTokenRepository(storage)
		val token = GemToken(
			walletId = WalletId("wallet"),
			chain = Chain.Ethereum,
			assetId = "0xToken",
			metadata = AssetMetadata.Known("USDT", "Tether USD", 6),
		)

		repository.upsert(token)
		repository.upsert(token.copy(metadata = AssetMetadata.Known("USD", "Dollar", 2)))

		assertEquals(listOf(token.copy(metadata = AssetMetadata.Known("USD", "Dollar", 2))), repository.get(WalletId("wallet"), Chain.Ethereum))
		assertEquals(repository.get(WalletId("wallet"), Chain.Ethereum), GemTokenRepository(storage).get(WalletId("wallet"), Chain.Ethereum))
	}

	@Test
	fun `delete removes only the requested token`() {
		val storage = InMemoryTokenStorage()
		val repository = GemTokenRepository(storage)
		val first = GemToken(WalletId("wallet"), Chain.Ethereum, "0xFirst", AssetMetadata.Known("ONE", "One", 18))
		val second = GemToken(WalletId("wallet"), Chain.Ethereum, "0xSecond", AssetMetadata.Known("TWO", "Two", 18))

		repository.upsert(first)
		repository.upsert(second)
		repository.delete(first.walletId, first.chain, first.assetId)

		assertEquals(listOf(second), repository.get(second.walletId, second.chain))
		assertTrue(repository.get(WalletId("other"), Chain.Ethereum).isEmpty())
	}

	private class InMemoryTokenStorage(
		private var value: String? = null,
	) : GemTokenStorage {
		override fun read(): String? = value

		override fun write(value: String): Boolean {
			this.value = value
			return true
		}
	}
}
