package com.tonapps.wallet.data.gem

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GemWalletDataSourceTest {
	@Test
	fun `asset ids do not provide token metadata`() = runBlocking {
		val source = GemWalletDataSource(FakeGemBackend(assets = listOf("ethereum", "ethereum_0xusdt", "smartchain_0xbsc")))

		val assets = source.getAssets(WalletId("wallet"), Chain.Ethereum).getOrThrow()

		assertEquals(listOf("native", "0xusdt"), assets.map { it.asset.id.value })
		assertEquals(listOf(AssetMetadata.Unknown, AssetMetadata.Unknown), assets.map { it.asset.metadata })
		assertNull(assets[0].balance.amount)
	}

	@Test
	fun `native asset uses Gemstone balance when account is registered`() = runBlocking {
		val wallet = GemWallet(
			walletId = WalletId("wallet"),
			keystoreId = "keystore",
			accounts = listOf(ChainAccount(WalletId("wallet"), Chain.Ethereum, "0xsender")),
		)
		val registry = GemWalletRegistry(InMemoryRegistryStorage().also { it.persist(wallet) })
		val source = GemWalletDataSource(
			backend = FakeGemBackend(assets = listOf("ethereum")),
			walletRegistry = registry,
			balanceReader = object : GemBalanceReader {
				override suspend fun getNativeBalance(chain: Chain, address: String): Result<String> {
					assertEquals(Chain.Ethereum, chain)
					assertEquals("0xsender", address)
					return Result.success("42")
				}
			},
		)

		assertEquals("42", source.getAssets(WalletId("wallet"), Chain.Ethereum).getOrThrow().single().balance.amount)
	}

	@Test
	fun `portfolio maps allocations after loading real balances`() = runBlocking {
		val wallet = GemWallet(
			walletId = WalletId("wallet"),
			keystoreId = "keystore",
			accounts = listOf(ChainAccount(WalletId("wallet"), Chain.Ethereum, "0xsender")),
		)
		val registry = GemWalletRegistry(InMemoryRegistryStorage().also { it.persist(wallet) })
		val source = GemWalletDataSource(
			backend = FakeGemBackend(
				assets = listOf("ethereum", "ethereum_0xtoken"),
				portfolio = GemPortfolioAssets(
					totalValue = "12.500000000000000001",
					values = emptyList(),
					allTimeHigh = null,
					allTimeLow = null,
					allocation = listOf(GemPortfolioAllocation("ethereum_0xtoken", 1f, "12.500000000000000001")),
				),
			),
			walletRegistry = registry,
			balanceReader = object : GemBalanceReader {
				override suspend fun getNativeBalance(chain: Chain, address: String): Result<String> = Result.success("100")

				override suspend fun getTokenBalances(
					chain: Chain,
					address: String,
					tokenIds: List<String>,
				): Result<Map<String, String>> = Result.success(mapOf("0xtoken" to "200"))
			},
		)

		val asset = source.getPortfolio(WalletId("wallet"), Chain.Ethereum).getOrThrow().assets.single()

		assertEquals(AssetId(Chain.Ethereum, "0xtoken"), asset.asset.id)
		assertEquals("12.500000000000000001", asset.balance.amount)
	}

	@Test
	fun `smart chain uses backend smartchain identifier`() = runBlocking {
		val source = GemWalletDataSource(FakeGemBackend(assets = listOf("smartchain")))

		val assets = source.getAssets(WalletId("wallet"), Chain.SmartChain).getOrThrow()

		assertEquals(AssetId(Chain.SmartChain, "native"), assets.single().asset.id)
	}

	@Test
	fun `transactions preserve amount and fee strings`() = runBlocking {
		val amount = "123456789012345678901234567890.123456789"
		val fee = "0.000000000000000001"
		val source = GemWalletDataSource(
			FakeGemBackend(
				transactions = GemTransactionsResponse(
					transactions = listOf(
						GemTransaction(
							id = "transaction",
							assetId = "ethereum_0xtoken",
							from = "from",
							to = "to",
							contract = null,
							type = "transfer",
							state = "confirmed",
							blockNumber = null,
							sequence = null,
							fee = fee,
							feeAssetId = "ethereum",
							value = amount,
							memo = null,
							direction = "outgoing",
							utxoInputs = null,
							utxoOutputs = null,
							metadata = null,
						createdAt = "2023-11-14T22:13:20Z",
						),
					),
					addressNames = emptyList(),
				),
			),
		)

		val transaction = source.getTransactions(WalletId("wallet"), Chain.Ethereum, 0).getOrThrow().single()

		assertEquals(amount, transaction.amount)
		assertEquals(fee, transaction.fee)
		assertEquals(1700000000000L, transaction.timestamp)
	}

	@Test
	fun `foreign and unknown asset ids are ignored`() = runBlocking {
		val source = GemWalletDataSource(FakeGemBackend(assets = listOf("smartchain_0xbsc", "unknown_asset")))

		val result = source.getAssets(WalletId("wallet"), Chain.Ethereum)

		assertEquals(emptyList<WalletAsset>(), result.getOrThrow())
	}

	@Test
	fun `non Gem requested chain is rejected`() = runBlocking {
		val source = GemWalletDataSource(FakeGemBackend())

		val result = source.getAssets(WalletId("wallet"), Chain.Ton)

		assertTrue(result.exceptionOrNull() is WalletDataSourceException)
		assertTrue((result.exceptionOrNull() as WalletDataSourceException).error is GemError.InvalidInput)
	}

	@Test
	fun `portfolio does not send fake zero balances`() = runBlocking {
		val source = GemWalletDataSource(FakeGemBackend(assets = listOf("ethereum_0xtoken")))

		val result = source.getPortfolio(WalletId("wallet"), Chain.Ethereum)

		assertTrue(result.exceptionOrNull() is WalletDataSourceException)
		assertTrue((result.exceptionOrNull() as WalletDataSourceException).error is GemError.UnsupportedOperation)
	}

	@Test
	fun `foreign transactions are ignored and invalid timestamps become null`() = runBlocking {
		val source = GemWalletDataSource(
			FakeGemBackend(
				transactions = GemTransactionsResponse(
					transactions = listOf(
						transaction("ethereum_native", "2023-11-14T22:13:20Z"),
						transaction("smartchain_native", "invalid"),
					),
					addressNames = emptyList(),
				),
			),
		)

		val transactions = source.getTransactions(WalletId("wallet"), Chain.Ethereum, 0).getOrThrow()

		assertEquals(1, transactions.size)
		assertEquals(1700000000000L, transactions.single().timestamp)
	}

	@Test
	fun `transaction status rejects a transaction from another chain`() = runBlocking {
		val source = GemWalletDataSource(
			FakeGemBackend(
				transactions = GemTransactionsResponse(
					transactions = listOf(transaction("smartchain_native", "invalid")),
					addressNames = emptyList(),
				),
			),
		)

		val result = source.getTransactionStatus(WalletId("wallet"), Chain.Ethereum, "smartchain_native")

		assertTrue(result.exceptionOrNull() is WalletDataSourceException)
		assertTrue((result.exceptionOrNull() as WalletDataSourceException).error is GemError.InvalidInput)
	}

	private fun transaction(assetId: String, createdAt: String) = GemTransaction(
		id = assetId,
		assetId = assetId,
		from = "from",
		to = "to",
		contract = null,
		type = "transfer",
		state = "confirmed",
		blockNumber = null,
		sequence = null,
		fee = "1",
		feeAssetId = assetId.substringBefore('_'),
		value = "1",
		memo = null,
		direction = "outgoing",
		utxoInputs = null,
		utxoOutputs = null,
		metadata = null,
		createdAt = createdAt,
	)

	private class FakeGemBackend(
		private val assets: List<String> = emptyList(),
		private val transactions: GemTransactionsResponse? = null,
		private val portfolio: GemPortfolioAssets? = null,
	) : GemBackendReader {
		override suspend fun getAssets(walletId: WalletId, fromTimestamp: Long): Result<List<String>> = Result.success(assets)

		override suspend fun getTransactions(
			walletId: WalletId,
			fromTimestamp: Long,
			assetId: String?,
		): Result<GemTransactionsResponse?> = Result.success(transactions)

		override suspend fun getTransaction(transactionId: String): Result<GemTransaction> =
			transactions?.transactions?.firstOrNull { it.id == transactionId }?.let { Result.success(it) }
				?: Result.failure(IllegalArgumentException("Transaction not found"))

		override suspend fun getPortfolioAssets(
			period: String,
			request: GemPortfolioAssetsRequest,
		): Result<GemPortfolioAssets> = Result.success(checkNotNull(portfolio))
	}

	private class InMemoryRegistryStorage : GemWalletRegistryStorage {
		private var value: String? = null

		override fun read(): String? = value

		override fun write(value: String): Boolean {
			this.value = value
			return true
		}

		override fun delete(): Boolean {
			value = null
			return true
		}
	}
}
