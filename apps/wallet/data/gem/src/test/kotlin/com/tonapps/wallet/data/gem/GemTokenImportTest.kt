package com.tonapps.wallet.data.gem

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GemTokenImportTest {

	private val noResolver = object : CoinGeckoAttributesResolver() {
		override suspend fun resolve(chain: Chain, contractAddress: String): ResolvedAttributes? = null
	}

	@Test
	fun `creates a token from trimmed import fields`() = runBlocking {
		val token = GemTokenImport(
			walletId = WalletId("wallet"),
			chain = Chain.Ethereum,
			assetId = " 0x1234567890123456789012345678901234567890 ",
			name = " Tether USD ",
			symbol = " USDT ",
			decimals = " 6 ",
		).toGemToken(noResolver).getOrThrow()

		assertEquals("0x1234567890123456789012345678901234567890", token.assetId)
		assertEquals(AssetMetadata.Known("USDT", "Tether USD", 6), token.metadata)
	}

	@Test
	fun `rejects incomplete import fields`() = runBlocking {
		val result = GemTokenImport(
			walletId = WalletId("wallet"),
			chain = Chain.Solana,
			assetId = "",
			name = "Token",
			symbol = "TOK",
			decimals = "9",
		).toGemToken(noResolver)

		assertTrue((result.exceptionOrNull() as WalletDataSourceException).error is GemError.InvalidInput)
	}

	@Test
	fun `rejects native and unsupported token imports`() = runBlocking {
		val native = GemTokenImport(WalletId("wallet"), Chain.Ethereum, "native", "ETH", "ETH", "18")
		val bitcoin = GemTokenImport(WalletId("wallet"), Chain.Bitcoin, "btc-token", "Token", "TOK", "8")

		assertTrue((native.toGemToken(noResolver).exceptionOrNull() as WalletDataSourceException).error is GemError.InvalidInput)
		assertTrue((bitcoin.toGemToken(noResolver).exceptionOrNull() as WalletDataSourceException).error is GemError.InvalidInput)
	}

	@Test
	fun `rejects token IDs that do not match the selected chain`() = runBlocking {
		val ethereum = GemTokenImport(WalletId("wallet"), Chain.Ethereum, "token", "Token", "TOK", "18")
		val solana = GemTokenImport(WalletId("wallet"), Chain.Solana, "0x1234", "Token", "TOK", "9")

		assertTrue((ethereum.toGemToken(noResolver).exceptionOrNull() as WalletDataSourceException).error is GemError.InvalidInput)
		assertTrue((solana.toGemToken(noResolver).exceptionOrNull() as WalletDataSourceException).error is GemError.InvalidInput)
	}

	@Test
	fun `fills blank and invalid fields from resolver`() = runBlocking {
		val resolver = object : CoinGeckoAttributesResolver() {
			override suspend fun resolve(chain: Chain, contractAddress: String): ResolvedAttributes =
				ResolvedAttributes(name = "Tether USD", symbol = "USDT", decimals = 6, imageUrl = "https://img/usdt.png")
		}
		val token = GemTokenImport(
			WalletId("wallet"), Chain.Ethereum,
			"0x1234567890123456789012345678901234567890",
			name = "  ",
			symbol = "",
			decimals = "not-a-number",
		).toGemToken(resolver).getOrThrow()

		assertEquals(AssetMetadata.Known("USDT", "Tether USD", 6, "https://img/usdt.png"), token.metadata)
	}

	@Test
	fun `keeps valid human input over resolver values`() = runBlocking {
		val resolver = object : CoinGeckoAttributesResolver() {
			override suspend fun resolve(chain: Chain, contractAddress: String): ResolvedAttributes =
				ResolvedAttributes(name = "Resolver Name", symbol = "RSN", decimals = 18)
		}
		val token = GemTokenImport(
			WalletId("wallet"), Chain.Ethereum,
			"0x1234567890123456789012345678901234567890",
			name = "My Token",
			symbol = "MYT",
			decimals = "8",
		).toGemToken(resolver).getOrThrow()

		assertEquals(AssetMetadata.Known("MYT", "My Token", 8), token.metadata)
	}
}