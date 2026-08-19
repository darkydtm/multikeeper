package com.tonapps.wallet.data.gem

import java.io.Closeable
import java.util.concurrent.CancellationException
import uniffi.gemstone.BroadcastOptions
import uniffi.gemstone.GemFeeRate
import uniffi.gemstone.GemGateway
import uniffi.gemstone.GemKeystore
import uniffi.gemstone.GemSignerInput
import uniffi.gemstone.GemTransactionData
import uniffi.gemstone.GemTransactionInputType
import uniffi.gemstone.GemTransactionLoadInput
import uniffi.gemstone.GemTransactionLoadMetadata
import uniffi.gemstone.GemTransactionPreloadInput
import uniffi.gemstone.GemTransactionStateRequest
import uniffi.gemstone.TransactionState as GemTransactionState
import uniffi.gemstone.TransactionUpdate

enum class GemTransactionCapability {
	Bitcoin,
	Evm,
	Solana,
}

fun Chain.gemTransactionCapability(): GemTransactionCapability? = when (this) {
	Chain.Bitcoin -> GemTransactionCapability.Bitcoin
	Chain.Ethereum, Chain.SmartChain -> GemTransactionCapability.Evm
	Chain.Solana -> GemTransactionCapability.Solana
	else -> null
}

interface GemGatewayApi {
	suspend fun getTransactionPreload(chain: String, input: GemTransactionPreloadInput): GemTransactionLoadMetadata

	suspend fun getFeeRates(chain: String, input: GemTransactionInputType): List<GemFeeRate>

	suspend fun getTransactionLoad(chain: String, input: GemTransactionLoadInput): GemTransactionData

	suspend fun transactionBroadcast(chain: String, data: String, options: BroadcastOptions): String

	suspend fun getTransactionStatus(chain: String, request: GemTransactionStateRequest): TransactionUpdate
}

fun interface GemGatewayFactory {
	fun create(): GemGatewayApi
}

private class GemstoneGatewayApi(
	private val gateway: GemGateway,
) : GemGatewayApi {
	override suspend fun getTransactionPreload(
		chain: String,
		input: GemTransactionPreloadInput,
	): GemTransactionLoadMetadata = gateway.getTransactionPreload(chain, input)

	override suspend fun getFeeRates(
		chain: String,
		input: GemTransactionInputType,
	): List<GemFeeRate> = gateway.getFeeRates(chain, input)

	override suspend fun getTransactionLoad(
		chain: String,
		input: GemTransactionLoadInput,
	): GemTransactionData = gateway.getTransactionLoad(chain, input)

	override suspend fun transactionBroadcast(
		chain: String,
		data: String,
		options: BroadcastOptions,
	): String = gateway.transactionBroadcast(chain, data, options)

	override suspend fun getTransactionStatus(
		chain: String,
		request: GemTransactionStateRequest,
	): TransactionUpdate = gateway.getTransactionStatus(chain, request)
}

class GemstoneGatewayFactory(
	private val gateway: GemGateway,
) : GemGatewayFactory {
	override fun create(): GemGatewayApi = GemstoneGatewayApi(gateway)
}

interface GemKeystoreApi : Closeable {
	fun sign(
		keystoreId: String,
		chain: String,
		input: GemSignerInput,
		password: ByteArray,
	): List<String>
}

fun interface GemKeystoreFactory {
	fun create(): GemKeystoreApi
}

private class GemstoneKeystoreApi(
	private val keystore: GemKeystore,
) : GemKeystoreApi {
	override fun sign(
		keystoreId: String,
		chain: String,
		input: GemSignerInput,
		password: ByteArray,
	): List<String> = keystore.sign(keystoreId, chain, input, password)

	override fun close() {
		keystore.close()
	}
}

class GemstoneKeystoreFactory(
	private val baseDir: String,
) : GemKeystoreFactory {
	override fun create(): GemKeystoreApi = GemstoneKeystoreApi(GemKeystore(baseDir))
}

interface GemTransactionDraftAdapter {
	fun keystoreId(draft: TransactionDraft): Result<String>

	fun toPreloadInput(draft: TransactionDraft): Result<GemTransactionPreloadInput>

	fun toLoadInput(
		draft: TransactionDraft,
		metadata: GemTransactionLoadMetadata,
		feeRate: GemFeeRate,
	): Result<GemTransactionLoadInput>

	fun toSignerInput(draft: TransactionDraft, data: GemTransactionData): Result<GemSignerInput>
}

class FailClosedGemTransactionDraftAdapter : GemTransactionDraftAdapter {
	override fun keystoreId(draft: TransactionDraft): Result<String> =
		invalidDraft("Gem keystore id is required")

	override fun toPreloadInput(draft: TransactionDraft): Result<GemTransactionPreloadInput> =
		invalidDraft("Gem transaction metadata is required")

	override fun toLoadInput(
		draft: TransactionDraft,
		metadata: GemTransactionLoadMetadata,
		feeRate: GemFeeRate,
	): Result<GemTransactionLoadInput> = invalidDraft("Gem transaction metadata is required")

	override fun toSignerInput(draft: TransactionDraft, data: GemTransactionData): Result<GemSignerInput> =
		invalidDraft("Gem signer input is required")
}

class GemstoneTransactionDraftAdapter(
	private val walletRegistry: GemWalletRegistry,
) : GemTransactionDraftAdapter {
	override fun keystoreId(draft: TransactionDraft): Result<String> = draftResult {
		val wallet = validateDraft(draft)
		wallet.keystoreId
	}

	override fun toPreloadInput(draft: TransactionDraft): Result<GemTransactionPreloadInput> = draftResult {
		validateDraft(draft)
		GemTransactionPreloadInput(
			inputType = draft.toGemInputType().getOrThrow(),
			senderAddress = draft.sender,
			destinationAddress = draft.recipient,
		)
	}

	override fun toLoadInput(
		draft: TransactionDraft,
		metadata: GemTransactionLoadMetadata,
		feeRate: GemFeeRate,
	): Result<GemTransactionLoadInput> = draftResult {
		validateDraft(draft)
		GemTransactionLoadInput(
			inputType = draft.toGemInputType().getOrThrow(),
			senderAddress = draft.sender,
			destinationAddress = draft.recipient,
			value = draft.amount,
			gasPrice = feeRate.gasPriceType,
			memo = draft.memo,
			isMaxValue = draft.isMaxValue,
			metadata = metadata,
		)
	}

	override fun toSignerInput(draft: TransactionDraft, data: GemTransactionData): Result<GemSignerInput> = draftResult {
		validateDraft(draft)
		GemSignerInput(
			input = GemTransactionLoadInput(
				inputType = draft.toGemInputType().getOrThrow(),
				senderAddress = draft.sender,
				destinationAddress = draft.recipient,
				value = draft.amount,
				gasPrice = data.fee.gasPriceType,
				memo = draft.memo,
				isMaxValue = draft.isMaxValue,
				metadata = data.metadata,
			),
			fee = data.fee,
		)
	}

	private fun validateDraft(draft: TransactionDraft): GemWallet = run {
		val wallet = checkNotNull(walletRegistry.load(draft.walletId)) { "Gem wallet is not registered" }
		check(draft.assetId.chain == draft.chain) { "Transaction asset chain does not match transaction chain" }
		check(draft.sender.isNotBlank()) { "Transaction sender is required" }
		check(draft.recipient.isNotBlank()) { "Transaction recipient is required" }
		check(draft.amount.isNotBlank()) { "Transaction amount is required" }
		check(draft.contractData == null) { "Contract transaction data is not supported by this adapter" }
		check(wallet.accounts.any { it.chain == draft.chain && it.address == draft.sender }) {
			"Transaction sender does not belong to the Gem wallet"
		}
		wallet
	}
}

private inline fun <T> draftResult(block: () -> T): Result<T> = try {
	Result.success(block())
} catch (error: WalletDataSourceException) {
	Result.failure(error)
} catch (error: Throwable) {
	Result.failure(WalletDataSourceException(GemError.InvalidInput(error.message ?: "Invalid Gem transaction draft")))
}

private fun TransactionDraft.toGemInputType(): Result<GemTransactionInputType> = runCatching {
		check(assetId.chain == chain) { "Transaction asset chain does not match transaction chain" }
		check(sender.isNotBlank()) { "Transaction sender is required" }
		check(recipient.isNotBlank()) { "Transaction recipient is required" }
		check(amount.isNotBlank()) { "Transaction amount is required" }
		check(contractData == null) { "Contract transaction data is not supported by this adapter" }
		val metadata = assetMetadata as? AssetMetadata.Known
			?: error("Known asset metadata is required for Gem transaction loading")
		val isNative = assetId.value == "native"
		val assetType = when {
			isNative -> uniffi.gemstone.GemAssetType.NATIVE
			chain == Chain.Ethereum -> uniffi.gemstone.GemAssetType.ERC20
			chain == Chain.SmartChain -> uniffi.gemstone.GemAssetType.BEP20
			chain == Chain.Solana -> uniffi.gemstone.GemAssetType.SPL
			else -> error("Unsupported Gem token chain: ${chain.key}")
		}
		GemTransactionInputType.Transfer(
			uniffi.gemstone.GemAsset(
				id = if (isNative) chain.key else "${chain.key}_${assetId.value}",
				chain = chain.key,
				tokenId = if (isNative) null else assetId.value,
				name = metadata.name,
				symbol = metadata.symbol,
				decimals = metadata.decimals,
				assetType = assetType,
			),
		)
}

class GemstoneTransactionBridge(
	private val gatewayFactory: GemGatewayFactory,
	private val keystoreFactory: GemKeystoreFactory,
	private val passwordProvider: GemPasswordProvider,
	private val draftAdapter: GemTransactionDraftAdapter,
) {
	suspend fun preload(draft: TransactionDraft): Result<GemTransactionLoadMetadata> = runGateway(draft) {
		gatewayFactory.create().getTransactionPreload(draft.chain.key, draftAdapter.toPreloadInput(draft).getOrThrow())
	}

	suspend fun getFeeRates(draft: TransactionDraft): Result<List<GemFeeRate>> = runGateway(draft) {
		val input = draftAdapter.toPreloadInput(draft).getOrThrow()
		gatewayFactory.create().getFeeRates(draft.chain.key, input.inputType)
	}

	suspend fun load(
		draft: TransactionDraft,
		metadata: GemTransactionLoadMetadata,
		feeRate: GemFeeRate,
	): Result<GemTransactionData> = runGateway(draft) {
		gatewayFactory.create().getTransactionLoad(
			draft.chain.key,
			draftAdapter.toLoadInput(draft, metadata, feeRate).getOrThrow(),
		)
	}

	suspend fun preloadTransaction(draft: TransactionDraft): Result<PreloadedTransaction> = runGateway(draft) {
		val gateway = gatewayFactory.create()
		val preloadInput = draftAdapter.toPreloadInput(draft).getOrThrow()
		val metadata = gateway.getTransactionPreload(draft.chain.key, preloadInput)
		val feeRates = gateway.getFeeRates(draft.chain.key, preloadInput.inputType)
		if (feeRates.isEmpty()) {
			throw WalletDataSourceException(GemError.InvalidInput("Gem gateway returned no fee rates"))
		}
		val data = feeRates.map { feeRate ->
			gateway.getTransactionLoad(
				draft.chain.key,
				draftAdapter.toLoadInput(draft, metadata, feeRate).getOrThrow(),
			)
		}
		PreloadedTransaction(draft = draft, metadata = metadata, feeRates = feeRates, data = data)
	}

	suspend fun signTransaction(transaction: PreloadedTransaction): Result<SignedTransaction> = runSigning(transaction.draft) {
		val feeIndex = transaction.selectedFeeIndex
			?: throw WalletDataSourceException(GemError.InvalidInput("A fee rate must be selected before signing"))
		val data = transaction.data.getOrNull(feeIndex)
			?: throw WalletDataSourceException(GemError.InvalidInput("Selected Gem fee rate is out of range"))
		val signerInput = draftAdapter.toSignerInput(transaction.draft, data).getOrThrow()
		val keystoreId = draftAdapter.keystoreId(transaction.draft).getOrThrow()
		val password = passwordProvider.passwordBytes()
		try {
			val payloads = keystoreFactory.create().use { keystore ->
				keystore.sign(
					keystoreId = keystoreId,
					chain = transaction.draft.chain.key,
					input = signerInput,
					password = password,
				)
			}
			if (payloads.isEmpty()) {
				throw WalletDataSourceException(GemError.SigningFailed("Gem keystore returned no signed payloads"))
			}
			SignedTransaction(transaction.draft.walletId, transaction.draft.chain, payloads)
		} finally {
			password.fill(0)
		}
	}

	suspend fun broadcastTransaction(
		transaction: SignedTransaction,
		options: BroadcastOptions = BroadcastOptions(skipPreflight = false),
	): Result<BroadcastedTransaction> = runGateway(transaction.chain) {
		if (transaction.payloads.isEmpty()) {
			throw WalletDataSourceException(GemError.InvalidInput("Signed transaction has no payloads"))
		}
		val gateway = gatewayFactory.create()
		val transactionIds = mutableListOf<String>()
		for (payload in transaction.payloads) {
			try {
				transactionIds += gateway.transactionBroadcast(transaction.chain.key, payload, options)
			} catch (error: CancellationException) {
				throw error
			} catch (error: WalletDataSourceException) {
				if (transactionIds.isEmpty() && error.partialBroadcast == null) {
					throw error
				}
				val acceptedIds = transactionIds.ifEmpty {
					error.partialBroadcast?.transactionIds.orEmpty()
				}
				throw WalletDataSourceException(
					error = error.error,
					partialBroadcast = BroadcastedTransaction(
						transaction.walletId,
						transaction.chain,
						acceptedIds,
						transaction.payloads.drop(acceptedIds.size),
					).takeIf { it.transactionIds.isNotEmpty() } ?: error.partialBroadcast,
					cause = error,
				)
			} catch (error: GemBackendException) {
				throw WalletDataSourceException(
					error = error.gemError,
					partialBroadcast = BroadcastedTransaction(
						transaction.walletId,
						transaction.chain,
						transactionIds,
						transaction.payloads.drop(transactionIds.size),
					).takeIf { it.transactionIds.isNotEmpty() },
					cause = error,
				)
			} catch (error: Throwable) {
				throw WalletDataSourceException(
					error = GemError.NetworkUnavailable(error),
					partialBroadcast = BroadcastedTransaction(
						transaction.walletId,
						transaction.chain,
						transactionIds.toList(),
						transaction.payloads.drop(transactionIds.size),
					).takeIf { it.transactionIds.isNotEmpty() },
					cause = error,
				)
		}
		}
		BroadcastedTransaction(transaction.walletId, transaction.chain, transactionIds)
	}

	suspend fun getTransactionStatus(request: GemTransactionStatusRequest): Result<TransactionStatus> {
		if (request.chain.gemTransactionCapability() == null) {
			return unsupported("Gem transaction capability is not available for ${request.chain.key}")
		}
		return try {
			val update = gatewayFactory.create().getTransactionStatus(request.chain.key, request.toGemRequest())
			Result.success(TransactionStatus(
				walletId = request.walletId,
				chain = request.chain,
				transactionId = request.transactionId,
				state = update.toDomainState(),
			))
		} catch (error: CancellationException) {
			throw error
		} catch (error: WalletDataSourceException) {
			Result.failure(error)
		} catch (error: Throwable) {
			Result.failure(WalletDataSourceException(GemError.StatusUnknown))
		}
	}

	private suspend fun <T> runGateway(chain: Chain, block: suspend () -> T): Result<T> {
		if (chain.gemTransactionCapability() == null) {
			return unsupported("Gem transaction capability is not available for ${chain.key}")
		}
		return try {
			Result.success(block())
		} catch (error: CancellationException) {
			throw error
		} catch (error: WalletDataSourceException) {
			Result.failure(error)
		} catch (error: IllegalArgumentException) {
			invalidDraft(error.message ?: "Invalid Gem transaction input")
		} catch (error: Throwable) {
			Result.failure(WalletDataSourceException(GemError.NetworkUnavailable(error)))
		}
	}

	private suspend fun <T> runGateway(draft: TransactionDraft, block: suspend () -> T): Result<T> {
		if (draft.chain.gemTransactionCapability() == null) {
			return unsupported("Gem transaction capability is not available for ${draft.chain.key}")
		}
		return runGateway(draft.chain, block)
	}

	private suspend fun <T> runSigning(draft: TransactionDraft, block: suspend () -> T): Result<T> {
		if (draft.chain.gemTransactionCapability() == null) {
			return unsupported("Gem transaction capability is not available for ${draft.chain.key}")
		}
		return try {
			Result.success(block())
		} catch (error: CancellationException) {
			throw error
		} catch (error: WalletDataSourceException) {
			Result.failure(error)
		} catch (error: IllegalArgumentException) {
			invalidDraft(error.message ?: "Invalid Gem transaction input")
		} catch (error: Throwable) {
			Result.failure(WalletDataSourceException(GemError.SigningFailed(error.message ?: "Gem signing failed", error)))
		}
	}
}

data class GemTransactionStatusRequest(
	val walletId: WalletId,
	val chain: Chain,
	val transactionId: String,
	val senderAddress: String,
	val createdAtMillis: Long,
	val blockNumber: String,
) {
	fun toGemRequest(): GemTransactionStateRequest = GemTransactionStateRequest(
		id = transactionId,
		senderAddress = senderAddress,
		createdAt = createdAtMillis / 1000L,
		blockNumber = blockNumber.toULongOrNull() ?: 0uL,
	)
}

fun TransactionUpdate.toDomainState(): TransactionState = when (state) {
	GemTransactionState.PENDING -> TransactionState.Pending
	GemTransactionState.IN_TRANSIT -> TransactionState.InTransit
	GemTransactionState.CONFIRMED -> TransactionState.Confirmed
	GemTransactionState.FAILED -> TransactionState.Failed
	GemTransactionState.REVERTED -> TransactionState.Reverted
	else -> TransactionState.Unknown
}

private fun <T> invalidDraft(message: String): Result<T> = Result.failure(
	WalletDataSourceException(GemError.InvalidInput(message)),
)

private fun <T> unsupported(message: String): Result<T> = Result.failure(
	WalletDataSourceException(GemError.UnsupportedOperation(message)),
)
