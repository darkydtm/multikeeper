package com.tonapps.wallet.data.gem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.gemstone.GemFeeRate
import kotlinx.coroutines.runBlocking

class GemSendCoordinatorTest {
	@Test
	fun `builds a lossless Gem draft from a chain account and asset`() {
		val draft = GemSendCoordinator.draft(
			walletId = WalletId("wallet"),
			chain = Chain.Ethereum,
			assetId = AssetId(Chain.Ethereum, "native"),
			sender = "0xsender",
			recipient = "0xrecipient",
			amount = "1000000000000000001",
			metadata = AssetMetadata.Known("ETH", "Ethereum", 18),
		)

		assertEquals("1000000000000000001", draft.amount)
		assertEquals(Chain.Ethereum, draft.assetId.chain)
	}

	@Test
	fun `selects the requested fee index`() {
		val transaction = PreloadedTransaction(
			draft = GemSendCoordinator.draft(
				walletId = WalletId("wallet"),
				chain = Chain.Ethereum,
				assetId = AssetId(Chain.Ethereum, "native"),
				sender = "0xsender",
				recipient = "0xrecipient",
				amount = "1",
				metadata = AssetMetadata.Known("ETH", "Ethereum", 18),
			),
			metadata = uniffi.gemstone.GemTransactionLoadMetadata.None,
			feeRates = listOf(
				GemFeeRate("slow", uniffi.gemstone.GemGasPriceType.Regular("1")),
				GemFeeRate("fast", uniffi.gemstone.GemGasPriceType.Regular("2")),
			),
            data = listOf(
                uniffi.gemstone.GemTransactionData(
                    fee = uniffi.gemstone.GemTransactionLoadFee(
                        fee = "1",
                        gasPriceType = uniffi.gemstone.GemGasPriceType.Regular("1"),
                        gasLimit = "1",
                        options = uniffi.gemstone.GemFeeOptions(emptyMap()),
                    ),
                    metadata = uniffi.gemstone.GemTransactionLoadMetadata.None,
                ),
                uniffi.gemstone.GemTransactionData(
                    fee = uniffi.gemstone.GemTransactionLoadFee(
                        fee = "2",
                        gasPriceType = uniffi.gemstone.GemGasPriceType.Regular("2"),
                        gasLimit = "2",
                        options = uniffi.gemstone.GemFeeOptions(emptyMap()),
                    ),
                    metadata = uniffi.gemstone.GemTransactionLoadMetadata.None,
                ),
            ),
		)

		assertEquals(1, transaction.selectFee(1).getOrThrow().selectedFeeIndex)
	}

	@Test
	fun `waits for the broadcast transaction to become confirmed`() = runBlocking {
		val broadcasted = BroadcastedTransaction(WalletId("wallet"), Chain.Ethereum, listOf("tx"))
		val source = FakeGemWalletDataSource(
			broadcasted = broadcasted,
			statuses = ArrayDeque(listOf(TransactionState.Pending, TransactionState.Confirmed)),
		)

		val result = GemSendCoordinator(source, pollDelayMillis = 0).submitAndWait(preloaded())

		assertEquals(broadcasted, result.getOrThrow())
		assertEquals(2, source.statusCalls)
	}

	@Test
	fun `fails when the broadcast transaction is reverted`() = runBlocking {
		val source = FakeGemWalletDataSource(
			broadcasted = BroadcastedTransaction(WalletId("wallet"), Chain.Ethereum, listOf("tx")),
			statuses = ArrayDeque(listOf(TransactionState.Reverted)),
		)

		val result = GemSendCoordinator(source, pollDelayMillis = 0).submitAndWait(preloaded())

		assertTrue(result.exceptionOrNull() is WalletDataSourceException)
		assertEquals(GemError.BroadcastFailed("Gem transaction was rejected"), (result.exceptionOrNull() as WalletDataSourceException).error)
	}

	@Test
	fun `retains accepted ids when a broadcasted transaction is rejected`() = runBlocking {
		val broadcasted = BroadcastedTransaction(WalletId("wallet"), Chain.Ethereum, listOf("tx"))
		val source = FakeGemWalletDataSource(
			broadcasted = broadcasted,
			statuses = ArrayDeque(listOf(TransactionState.Reverted)),
		)

		val result = GemSendCoordinator(source, pollDelayMillis = 0).submitOutcome(preloaded())

		val failed = result as GemSendOutcome.Failed
		assertEquals(GemError.BroadcastFailed("Gem transaction was rejected"), (failed.error as WalletDataSourceException).error)
		assertEquals(broadcasted, failed.transaction)
	}

	@Test
	fun `returns partial broadcast as submitted without rebroadcasting`() = runBlocking {
		val partial = BroadcastedTransaction(WalletId("wallet"), Chain.Ethereum, listOf("tx-1"))
		val broadcastError = WalletDataSourceException(GemError.NetworkUnavailable(), partial)
		val source = FakeGemWalletDataSource(
			broadcasted = partial,
			statuses = ArrayDeque(listOf(TransactionState.Confirmed)),
			broadcastFailure = broadcastError,
		)
		val coordinator = GemSendCoordinator(source, pollDelayMillis = 0)

		val submitted = coordinator.submitOutcome(preloaded())
		val confirmed = coordinator.waitForOutcome(partial)

		assertEquals(GemSendOutcome.Submitted(partial, broadcastError), submitted)
		assertEquals(GemSendOutcome.Confirmed(partial), confirmed)
		assertEquals(1, source.broadcastCalls)
	}

	@Test
	fun `retries only unsubmitted payloads and retains accepted ids`() = runBlocking {
		val partial = BroadcastedTransaction(
			WalletId("wallet"),
			Chain.Ethereum,
			listOf("tx-1"),
			listOf("signed-2"),
		)
		val source = FakeGemWalletDataSource(
			broadcasted = BroadcastedTransaction(WalletId("wallet"), Chain.Ethereum, listOf("tx-2")),
			statuses = ArrayDeque(listOf(TransactionState.Confirmed, TransactionState.Confirmed)),
		)

		val result = GemSendCoordinator(source, pollDelayMillis = 0).submitOutcome(preloaded(), partial)

		assertEquals(GemSendOutcome.Confirmed(
			BroadcastedTransaction(WalletId("wallet"), Chain.Ethereum, listOf("tx-1", "tx-2")),
		), result)
		assertEquals(listOf("signed-2"), source.broadcastPayloads)
	}

	@Test
	fun `does not confirm a partial multi-payload broadcast`() = runBlocking {
		val partial = BroadcastedTransaction(
			WalletId("wallet"),
			Chain.Ethereum,
			listOf("tx-1"),
			listOf("signed-2"),
		)
		val source = FakeGemWalletDataSource(
			broadcasted = partial,
			statuses = ArrayDeque(listOf(TransactionState.Confirmed)),
		)

		val result = GemSendCoordinator(source, pollDelayMillis = 0).waitForOutcome(partial)

		assertTrue(result is GemSendOutcome.Submitted)
	}

	@Test
	fun `preserves the original data source exception`() = runBlocking {
		val error = WalletDataSourceException(GemError.InvalidInput("invalid"))
		val source = FakeGemWalletDataSource(
			broadcasted = BroadcastedTransaction(WalletId("wallet"), Chain.Ethereum, listOf("tx")),
			signFailure = error,
		)

		val result = GemSendCoordinator(source, pollDelayMillis = 0).submitAndWait(preloaded())

		assertSame(error, result.exceptionOrNull())
	}

	private fun preloaded() = PreloadedTransaction(
		draft = GemSendCoordinator.draft(
			walletId = WalletId("wallet"),
			chain = Chain.Ethereum,
			assetId = AssetId(Chain.Ethereum, "native"),
			sender = "0xsender",
			recipient = "0xrecipient",
			amount = "1",
			metadata = AssetMetadata.Known("ETH", "Ethereum", 18),
		),
		metadata = uniffi.gemstone.GemTransactionLoadMetadata.None,
		feeRates = emptyList(),
		data = emptyList(),
	)

	private class FakeGemWalletDataSource(
		private val broadcasted: BroadcastedTransaction,
		private val statuses: ArrayDeque<TransactionState>,
		private val signFailure: Throwable? = null,
		private val broadcastFailure: Throwable? = null,
	) : WalletDataSource {
		var statusCalls = 0
		var broadcastCalls = 0
		var broadcastPayloads = emptyList<String>()

		override suspend fun getPortfolio(walletId: WalletId, chain: Chain) = Result.failure<WalletPortfolio>(IllegalStateException("unsupported"))
		override suspend fun getAssets(walletId: WalletId, chain: Chain) = Result.failure<List<WalletAsset>>(IllegalStateException("unsupported"))
		override suspend fun getTransactions(walletId: WalletId, chain: Chain, fromTimestamp: Long) = Result.failure<List<TransactionRecord>>(IllegalStateException("unsupported"))
		override suspend fun preloadTransaction(draft: TransactionDraft) = Result.failure<PreloadedTransaction>(IllegalStateException("unsupported"))

		override suspend fun signTransaction(transaction: PreloadedTransaction): Result<SignedTransaction> =
			signFailure?.let { Result.failure(it) } ?: Result.success(
				SignedTransaction(transaction.draft.walletId, transaction.draft.chain, listOf("signed")),
			)

		override suspend fun broadcastTransaction(transaction: SignedTransaction): Result<BroadcastedTransaction> {
			broadcastCalls++
			broadcastPayloads = transaction.payloads
			return broadcastFailure?.let { Result.failure(it) } ?: Result.success(broadcasted)
		}

		override suspend fun getTransactionStatus(
			walletId: WalletId,
			chain: Chain,
			transactionId: String,
		) = Result.success(TransactionStatus(walletId, chain, transactionId, statuses.removeFirst().also { statusCalls++ }))
	}
}
