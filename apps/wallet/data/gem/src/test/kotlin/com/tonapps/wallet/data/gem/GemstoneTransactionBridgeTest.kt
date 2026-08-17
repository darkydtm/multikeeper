package com.tonapps.wallet.data.gem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.gemstone.BroadcastOptions
import uniffi.gemstone.GemFeeRate
import uniffi.gemstone.GemTransactionData
import uniffi.gemstone.GemTransactionInputType
import uniffi.gemstone.GemTransactionLoadInput
import uniffi.gemstone.GemTransactionLoadMetadata
import uniffi.gemstone.GemTransactionPreloadInput
import uniffi.gemstone.GemTransactionStateRequest
import uniffi.gemstone.TransactionState as GemTransactionState
import uniffi.gemstone.TransactionUpdate

class GemstoneTransactionBridgeTest {
	@Test
	fun `routes only supported chains`() {
		assertEquals(GemTransactionCapability.Bitcoin, Chain.Bitcoin.gemTransactionCapability())
		assertEquals(GemTransactionCapability.Evm, Chain.Ethereum.gemTransactionCapability())
		assertEquals(GemTransactionCapability.Evm, Chain.SmartChain.gemTransactionCapability())
		assertEquals(GemTransactionCapability.Solana, Chain.Solana.gemTransactionCapability())
		assertEquals(null, Chain.Ton.gemTransactionCapability())
	}

	@Test
	fun `fail closed adapter rejects drafts without lossless Gem metadata`() {
		val result = FailClosedGemTransactionDraftAdapter().toPreloadInput(
			TransactionDraft(
				walletId = WalletId("wallet"),
				chain = Chain.Ethereum,
				assetId = AssetId(Chain.Ethereum, "native"),
				sender = "sender",
				recipient = "recipient",
				amount = "1",
			),
		)

		assertTrue(result.exceptionOrNull() is WalletDataSourceException)
		assertEquals(
			GemError.InvalidInput("Gem transaction metadata is required"),
			(result.exceptionOrNull() as WalletDataSourceException).error,
		)
	}

	@Test
	fun `transaction adapter rejects unknown asset metadata`() {
		val registry = GemWalletRegistry(object : GemWalletRegistryStorage {
			override fun read(): String? = null
			override fun write(value: String): Boolean = true
			override fun delete(): Boolean = true
		})
		val result = GemstoneTransactionDraftAdapter(registry).toPreloadInput(
			TransactionDraft(
				walletId = WalletId("wallet"),
				chain = Chain.Ethereum,
				assetId = AssetId(Chain.Ethereum, "native"),
				sender = "sender",
				recipient = "recipient",
				amount = "1",
			),
		)

		assertTrue(result.isFailure)
		assertEquals(GemError.InvalidInput("Gem wallet is not registered"), (result.exceptionOrNull() as WalletDataSourceException).error)
	}

	@Test
	fun `transaction adapter rejects a sender from another Gem account`() {
		val wallet = GemWallet(
			walletId = WalletId("wallet"),
			keystoreId = "keystore",
			accounts = listOf(ChainAccount(WalletId("wallet"), Chain.Ethereum, "expected-sender")),
		)
		var stored: String? = null
		val registry = GemWalletRegistry(object : GemWalletRegistryStorage {
			override fun read(): String? = stored
			override fun write(value: String): Boolean {
				stored = value
				return true
			}
			override fun delete(): Boolean = true
		}).also { it.persist(wallet) }
		val result = GemstoneTransactionDraftAdapter(registry).toPreloadInput(
			TransactionDraft(
				walletId = WalletId("wallet"),
				chain = Chain.Ethereum,
				assetId = AssetId(Chain.Ethereum, "native"),
				sender = "wrong-sender",
				recipient = "recipient",
				amount = "1",
				assetMetadata = AssetMetadata.Known("ETH", "Ethereum", 18),
			),
		)

		assertTrue(result.isFailure)
		assertEquals(
			GemError.InvalidInput("Transaction sender does not belong to the Gem wallet"),
			(result.exceptionOrNull() as WalletDataSourceException).error,
		)
	}

	@Test
	fun `maps confirmed transaction states and preserves unknown state fallback`() {
		assertEquals(TransactionState.Pending, update(GemTransactionState.PENDING).toDomainState())
		assertEquals(TransactionState.InTransit, update(GemTransactionState.IN_TRANSIT).toDomainState())
		assertEquals(TransactionState.Confirmed, update(GemTransactionState.CONFIRMED).toDomainState())
		assertEquals(TransactionState.Failed, update(GemTransactionState.FAILED).toDomainState())
		assertEquals(TransactionState.Reverted, update(GemTransactionState.REVERTED).toDomainState())
	}

	@Test
	fun `converts status request seconds and block number`() {
		val request = GemTransactionStatusRequest(
			walletId = WalletId("wallet"),
			chain = Chain.Ethereum,
			transactionId = "hash",
			senderAddress = "sender",
			createdAtMillis = 123000L,
			blockNumber = "456",
		).toGemRequest()

		assertEquals(
			GemTransactionStateRequest(
				id = "hash",
				senderAddress = "sender",
				createdAt = 123L,
				blockNumber = 456L,
			),
			request,
		)
	}

	@Test
	fun `broadcast preserves every signed payload string`() {
		val payloads = listOf("signed-1", "signed-2")
		val sent = mutableListOf<String>()
		val bridge = GemstoneTransactionBridge(
			gatewayFactory = GemGatewayFactory { object : GemGatewayApi {
				override suspend fun getTransactionPreload(chain: String, input: GemTransactionPreloadInput): GemTransactionLoadMetadata = error("unused")
				override suspend fun getFeeRates(chain: String, input: GemTransactionInputType): List<GemFeeRate> = error("unused")
				override suspend fun getTransactionLoad(chain: String, input: GemTransactionLoadInput): GemTransactionData = error("unused")
				override suspend fun transactionBroadcast(chain: String, data: String, options: BroadcastOptions): String {
					sent += data
					return "hash-$data"
				}
				override suspend fun getTransactionStatus(chain: String, request: GemTransactionStateRequest): TransactionUpdate = error("unused")
			} },
			keystoreFactory = GemKeystoreFactory { error("unused") },
			passwordProvider = GemPasswordProvider { ByteArray(32) },
			draftAdapter = FailClosedGemTransactionDraftAdapter(),
		)

		val result = kotlinx.coroutines.runBlocking {
			bridge.broadcastTransaction(SignedTransaction(WalletId("wallet"), Chain.Ethereum, payloads))
		}.getOrThrow()

		assertEquals(payloads, sent)
		assertEquals(listOf("hash-signed-1", "hash-signed-2"), result.transactionIds)
	}

	@Test
	fun `broadcast preserves submitted ids when a later payload fails`() {
		val bridge = GemstoneTransactionBridge(
			gatewayFactory = GemGatewayFactory { object : GemGatewayApi {
				override suspend fun getTransactionPreload(chain: String, input: GemTransactionPreloadInput): GemTransactionLoadMetadata = error("unused")
				override suspend fun getFeeRates(chain: String, input: GemTransactionInputType): List<GemFeeRate> = error("unused")
				override suspend fun getTransactionLoad(chain: String, input: GemTransactionLoadInput): GemTransactionData = error("unused")
				override suspend fun transactionBroadcast(chain: String, data: String, options: BroadcastOptions): String = when (data) {
					"signed-1" -> "hash-signed-1"
					else -> error("broadcast failed")
				}
				override suspend fun getTransactionStatus(chain: String, request: GemTransactionStateRequest): TransactionUpdate = error("unused")
			} },
			keystoreFactory = GemKeystoreFactory { error("unused") },
			passwordProvider = GemPasswordProvider { ByteArray(32) },
			draftAdapter = FailClosedGemTransactionDraftAdapter(),
		)

		val result = kotlinx.coroutines.runBlocking {
			bridge.broadcastTransaction(SignedTransaction(WalletId("wallet"), Chain.Ethereum, listOf("signed-1", "signed-2")))
		}

		val error = result.exceptionOrNull() as WalletDataSourceException
		assertEquals(listOf("hash-signed-1"), error.partialBroadcast?.transactionIds)
		assertEquals(listOf("signed-2"), error.partialBroadcast?.unsubmittedPayloads)
	}

	@Test
	fun `broadcast preserves a domain error after an earlier payload was accepted`() {
		val domainError = WalletDataSourceException(GemError.BackendRejected("rejected"))
		val bridge = GemstoneTransactionBridge(
			gatewayFactory = GemGatewayFactory { object : GemGatewayApi {
				override suspend fun getTransactionPreload(chain: String, input: GemTransactionPreloadInput): GemTransactionLoadMetadata = error("unused")
				override suspend fun getFeeRates(chain: String, input: GemTransactionInputType): List<GemFeeRate> = error("unused")
				override suspend fun getTransactionLoad(chain: String, input: GemTransactionLoadInput): GemTransactionData = error("unused")
				override suspend fun transactionBroadcast(chain: String, data: String, options: BroadcastOptions): String = when (data) {
					"signed-1" -> "hash-signed-1"
					else -> throw domainError
				}
				override suspend fun getTransactionStatus(chain: String, request: GemTransactionStateRequest): TransactionUpdate = error("unused")
			} },
			keystoreFactory = GemKeystoreFactory { error("unused") },
			passwordProvider = GemPasswordProvider { ByteArray(32) },
			draftAdapter = FailClosedGemTransactionDraftAdapter(),
		)

		val result = kotlinx.coroutines.runBlocking {
			bridge.broadcastTransaction(SignedTransaction(WalletId("wallet"), Chain.Ethereum, listOf("signed-1", "signed-2")))
		}

		val error = result.exceptionOrNull() as WalletDataSourceException
		assertEquals(GemError.BackendRejected("rejected"), error.error)
		assertEquals(listOf("hash-signed-1"), error.partialBroadcast?.transactionIds)
		assertEquals(listOf("signed-2"), error.partialBroadcast?.unsubmittedPayloads)
	}

	@Test
	fun `broadcast preserves a domain error from the gateway`() {
		val domainError = WalletDataSourceException(GemError.BackendRejected("rejected"))
		val bridge = GemstoneTransactionBridge(
			gatewayFactory = GemGatewayFactory { object : GemGatewayApi {
				override suspend fun getTransactionPreload(chain: String, input: GemTransactionPreloadInput): GemTransactionLoadMetadata = error("unused")
				override suspend fun getFeeRates(chain: String, input: GemTransactionInputType): List<GemFeeRate> = error("unused")
				override suspend fun getTransactionLoad(chain: String, input: GemTransactionLoadInput): GemTransactionData = error("unused")
				override suspend fun transactionBroadcast(chain: String, data: String, options: BroadcastOptions): String = throw domainError
				override suspend fun getTransactionStatus(chain: String, request: GemTransactionStateRequest): TransactionUpdate = error("unused")
			} },
			keystoreFactory = GemKeystoreFactory { error("unused") },
			passwordProvider = GemPasswordProvider { ByteArray(32) },
			draftAdapter = FailClosedGemTransactionDraftAdapter(),
		)

		val result = kotlinx.coroutines.runBlocking {
			bridge.broadcastTransaction(SignedTransaction(WalletId("wallet"), Chain.Ethereum, listOf("signed")))
		}

		assertSame(domainError, result.exceptionOrNull())
	}

	private fun update(state: GemTransactionState) = TransactionUpdate(state = state, changes = emptyList())
}
