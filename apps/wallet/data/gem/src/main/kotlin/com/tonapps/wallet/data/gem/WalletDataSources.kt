package com.tonapps.wallet.data.gem

import java.time.Instant
import java.util.concurrent.CancellationException

data class WalletPortfolio(
	val assets: List<WalletAsset>,
)

interface GemBackendReader {
	suspend fun getAssets(walletId: WalletId, fromTimestamp: Long): Result<List<String>>
	suspend fun getTransactions(
		walletId: WalletId,
		fromTimestamp: Long,
		assetId: String? = null,
	): Result<GemTransactionsResponse?>
	suspend fun getTransaction(transactionId: String): Result<GemTransaction>
	suspend fun getPortfolioAssets(
		period: String,
		request: GemPortfolioAssetsRequest,
	): Result<GemPortfolioAssets>
}

interface GemDeviceTokenBackend {
	suspend fun getDeviceToken(): Result<GemDeviceToken>
}

data class WalletAsset(
	val asset: Asset,
	val balance: Balance,
)

data class PreloadedTransaction(
	val draft: TransactionDraft,
	val metadata: uniffi.gemstone.GemTransactionLoadMetadata,
	val feeRates: List<uniffi.gemstone.GemFeeRate>,
	val data: List<uniffi.gemstone.GemTransactionData>,
	val selectedFeeIndex: Int? = feeRates.singleOrNull()?.let { 0 },
)

data class GemFeeEstimate(
	val chain: Chain,
	val amount: String,
	val symbol: String,
	val decimals: Int,
	val index: Int,
)

fun PreloadedTransaction.feeEstimate(index: Int): GemFeeEstimate? {
	val fee = data.getOrNull(index)?.fee ?: return null
	val (symbol, decimals) = when (draft.chain) {
		Chain.Bitcoin -> "BTC" to 8
		Chain.Ethereum -> "ETH" to 18
		Chain.SmartChain -> "BNB" to 18
		Chain.Solana -> "SOL" to 9
		Chain.Ton -> return null
	}
	return GemFeeEstimate(draft.chain, fee.fee, symbol, decimals, index)
}

fun PreloadedTransaction.selectFee(index: Int): Result<PreloadedTransaction> =
	if (index in data.indices && index in feeRates.indices) {
		Result.success(copy(selectedFeeIndex = index))
	} else {
		Result.failure(WalletDataSourceException(GemError.InvalidInput("Selected Gem fee rate is out of range")))
	}

data class SignedTransaction(
	val walletId: WalletId,
	val chain: Chain,
	val payloads: List<String>,
)

data class BroadcastedTransaction(
	val walletId: WalletId,
	val chain: Chain,
	val transactionIds: List<String>,
	val unsubmittedPayloads: List<String> = emptyList(),
) {
	val payloadCount: Int
		get() = transactionIds.size + unsubmittedPayloads.size
}

data class TransactionStatus(
	val walletId: WalletId,
	val chain: Chain,
	val transactionId: String,
	val state: TransactionState,
)

interface WalletDataSource {
	suspend fun getPortfolio(walletId: WalletId, chain: Chain): Result<WalletPortfolio>
	suspend fun getAssets(walletId: WalletId, chain: Chain): Result<List<WalletAsset>>
	suspend fun getTransactions(walletId: WalletId, chain: Chain, fromTimestamp: Long): Result<List<TransactionRecord>>
	suspend fun preloadTransaction(draft: TransactionDraft): Result<PreloadedTransaction>
	suspend fun signTransaction(transaction: PreloadedTransaction): Result<SignedTransaction>
	suspend fun broadcastTransaction(transaction: SignedTransaction): Result<BroadcastedTransaction>
	suspend fun getTransactionStatus(
		walletId: WalletId,
		chain: Chain,
		transactionId: String,
	): Result<TransactionStatus>
}

interface TonWalletDataSource : WalletDataSource

class GemWalletDataSource(
	private val backend: GemBackendReader,
	private val walletRegistry: GemWalletRegistry? = null,
	private val balanceReader: GemBalanceReader? = null,
	private val transactionBridge: GemstoneTransactionBridge? = null,
	private val tokenRepository: GemTokenRepository? = null,
) : WalletDataSource {
	override suspend fun getPortfolio(walletId: WalletId, chain: Chain): Result<WalletPortfolio> = runRead(chain) {
		val assets = getAssets(walletId, chain).getOrElse { return@runRead Result.failure(it) }
		if (assets.any { it.balance.amount == null }) {
			return@runRead unsupported()
		}
		val request = GemPortfolioAssetsRequest(
			assets = assets.map { asset ->
				GemPortfolioAsset(
					assetId = "${chain.key}_${asset.asset.id.value}".removeSuffix("_native"),
					value = asset.balance.amount.orEmpty(),
				)
			},
		)
		backend.getPortfolioAssets("all", request).map { portfolio ->
			WalletPortfolio(
				assets = portfolio.allocation
					.filter { it.assetId.belongsTo(chain) }
					.map { it.toWalletAsset(chain) },
			)
		}
	}

	override suspend fun getAssets(walletId: WalletId, chain: Chain): Result<List<WalletAsset>> = runRead(chain) {
		val manualTokens = tokenRepository?.get(walletId, chain).orEmpty()
		val assetIds = backend.getAssets(walletId, 0).getOrElse {
			if (manualTokens.isEmpty()) return@runRead Result.failure(it)
			emptyList()
		}
		val address = walletRegistry?.load(walletId)?.accounts
			?.firstOrNull { it.chain == chain }
			?.address
		val chainAssets = (assetIds + manualTokens.map { "${chain.key}_${it.assetId}" })
			.filter { it.belongsTo(chain) }
			.distinct()
		val manualMetadata = manualTokens.associate { it.assetId to it.metadata }
		val tokenBalances = if (address != null && balanceReader != null && chainAssets.any { !it.isNativeAsset() }) {
			balanceReader.getTokenBalances(
				chain,
				address,
				chainAssets.filterNot(String::isNativeAsset).map { it.toAssetId(chain) },
			).getOrElse { return@runRead Result.failure(it) }
		} else {
			emptyMap()
		}
		val nativeBalance = if (address != null && balanceReader != null && chainAssets.any(String::isNativeAsset)) {
			balanceReader.getNativeBalance(chain, address).getOrElse { return@runRead Result.failure(it) }
		} else {
			null
		}
		Result.success(chainAssets.map { assetId ->
			val balance = if (assetId.isNativeAsset()) {
				nativeBalance
			} else {
				tokenBalances[assetId.toAssetId(chain)] ?: tokenBalances[assetId]
			}
			assetId.toWalletAsset(chain, balance, manualMetadata[assetId.toAssetId(chain)] ?: AssetMetadata.Unknown)
		})
	}

	override suspend fun getTransactions(
		walletId: WalletId,
		chain: Chain,
		fromTimestamp: Long,
	): Result<List<TransactionRecord>> = runRead(chain) {
		backend.getTransactions(walletId, fromTimestamp / 1000L).map { response ->
			response?.transactions.orEmpty()
				.filter { it.assetId.belongsTo(chain) }
				.map { it.toTransactionRecord(walletId, chain, tokenRepository?.get(walletId, chain).orEmpty()) }
		}
	}

	private suspend fun getTransactionStatusFromBackend(
		walletId: WalletId,
		chain: Chain,
		transactionId: String,
	): Result<TransactionStatus> = runRead(chain) {
		backend.getTransaction(transactionId).map { transaction ->
			if (!transaction.assetId.belongsTo(chain)) {
				throw IllegalArgumentException("Transaction does not belong to requested Gem chain")
			}
			val record = transaction.toTransactionRecord(walletId, chain, tokenRepository?.get(walletId, chain).orEmpty())
			TransactionStatus(walletId, chain, transactionId, record.state)
		}
	}

	override suspend fun preloadTransaction(draft: TransactionDraft): Result<PreloadedTransaction> =
		transactionBridge?.preloadTransaction(draft) ?: unsupported()

	override suspend fun signTransaction(transaction: PreloadedTransaction): Result<SignedTransaction> =
		transactionBridge?.signTransaction(transaction) ?: unsupported()

	override suspend fun broadcastTransaction(transaction: SignedTransaction): Result<BroadcastedTransaction> =
		transactionBridge?.broadcastTransaction(transaction) ?: unsupported()

	override suspend fun getTransactionStatus(
		walletId: WalletId,
		chain: Chain,
		transactionId: String,
	): Result<TransactionStatus> = getTransactionStatusFromBackend(walletId, chain, transactionId)

	suspend fun getTransactionStatus(request: GemTransactionStatusRequest): Result<TransactionStatus> =
		transactionBridge?.getTransactionStatus(request) ?: unsupported()

	private fun <T> unsupported(): Result<T> = Result.failure(
		WalletDataSourceException(GemError.UnsupportedOperation("Gem Wallet bridge is not wired")),
	)

	private suspend fun <T> runRead(chain: Chain, block: suspend () -> Result<T>): Result<T> {
		if (chain.provider != Provider.Gem || chain.key !in supportedGemChainKeys) {
			return Result.failure(WalletDataSourceException(GemError.InvalidInput("Unsupported Gem chain: ${chain.key}")))
		}
		return try {
			block().fold(
				onSuccess = { Result.success(it) },
				onFailure = { error ->
					if (error is CancellationException) {
						throw error
					}
					Result.failure<T>(
						WalletDataSourceException(
							when (error) {
								is WalletDataSourceException -> error.error
								is GemBackendException -> error.gemError
								is IllegalArgumentException -> GemError.InvalidInput(error.message ?: "Invalid Gem input")
								else -> GemError.NetworkUnavailable(error)
							},
						),
					)
				},
			)
		} catch (error: IllegalArgumentException) {
			Result.failure(WalletDataSourceException(GemError.InvalidInput(error.message ?: "Invalid Gem input")))
		} catch (error: WalletDataSourceException) {
			Result.failure(error)
		}
	}
}

private val supportedGemChainKeys = setOf("bitcoin", "ethereum", "smartchain", "solana")

private fun String.toWalletAsset(
	chain: Chain,
	amount: String? = null,
	metadata: AssetMetadata = AssetMetadata.Unknown,
): WalletAsset {
	val value = toAssetId(chain)
	return WalletAsset(Asset(AssetId(chain, value), metadata), Balance(amount))
}

private fun GemPortfolioAllocation.toWalletAsset(chain: Chain): WalletAsset =
	WalletAsset(Asset(AssetId(chain, assetId.toAssetId(chain))), Balance(value.toString()))

private fun GemTransaction.toTransactionRecord(
	walletId: WalletId,
	chain: Chain,
	manualTokens: List<GemToken>,
): TransactionRecord {
	val normalizedAssetId = assetId.toAssetId(chain)
	val metadata = manualTokens.firstOrNull { it.assetId == normalizedAssetId }?.metadata
		?: chain.nativeMetadata()
	return TransactionRecord(
		walletId = walletId,
		chain = chain,
		assetId = AssetId(chain, normalizedAssetId),
		gemId = id,
		hash = id,
		amount = value,
		fee = fee,
		timestamp = runCatching { Instant.parse(createdAt).toEpochMilli() }.getOrNull(),
		feeAssetId = feeAssetId.toAssetId(chain).let { AssetId(chain, it) },
		from = from,
		to = to,
		memo = memo,
		type = type,
		direction = direction,
		metadata = metadata,
		state = when (state.lowercase()) {
			"confirmed", "completed", "success" -> TransactionState.Confirmed
			"in_transit", "in transit" -> TransactionState.InTransit
			"failed" -> TransactionState.Failed
			"reverted" -> TransactionState.Reverted
			"pending", "processing" -> TransactionState.Pending
			else -> TransactionState.Unknown
		},
	)
}

private fun Chain.nativeMetadata(): AssetMetadata.Known = when (this) {
	Chain.Bitcoin -> AssetMetadata.Known("BTC", "Bitcoin", 8)
	Chain.Ethereum -> AssetMetadata.Known("ETH", "Ethereum", 18)
	Chain.SmartChain -> AssetMetadata.Known("BNB", "BNB Smart Chain", 18)
	Chain.Solana -> AssetMetadata.Known("SOL", "Solana", 9)
	Chain.Ton -> AssetMetadata.Known("TON", "Toncoin", 9)
}

private fun String.toAssetId(chain: Chain): String {
	return substringAfter('_', missingDelimiterValue = "native")
}

private fun String.belongsTo(chain: Chain): Boolean = substringBefore('_') == chain.key

private fun String.isNativeAsset(): Boolean = !contains('_') || endsWith("_native")

class CompositeWalletRepository(
	private val ton: TonWalletDataSource,
	private val gem: WalletDataSource,
) : WalletDataSource {
	override suspend fun getPortfolio(walletId: WalletId, chain: Chain): Result<WalletPortfolio> =
		sourceFor(chain).getPortfolio(walletId, chain)

	override suspend fun getAssets(walletId: WalletId, chain: Chain): Result<List<WalletAsset>> =
		sourceFor(chain).getAssets(walletId, chain)

	override suspend fun getTransactions(
		walletId: WalletId,
		chain: Chain,
		fromTimestamp: Long,
	): Result<List<TransactionRecord>> = sourceFor(chain).getTransactions(walletId, chain, fromTimestamp)

	override suspend fun preloadTransaction(draft: TransactionDraft): Result<PreloadedTransaction> =
		sourceFor(draft.chain).preloadTransaction(draft)

	override suspend fun signTransaction(transaction: PreloadedTransaction): Result<SignedTransaction> =
		sourceFor(transaction.draft.chain).signTransaction(transaction)

	override suspend fun broadcastTransaction(transaction: SignedTransaction): Result<BroadcastedTransaction> =
		sourceFor(transaction.chain).broadcastTransaction(transaction)

	override suspend fun getTransactionStatus(
		walletId: WalletId,
		chain: Chain,
		transactionId: String,
	): Result<TransactionStatus> = sourceFor(chain).getTransactionStatus(walletId, chain, transactionId)

	private fun sourceFor(chain: Chain): WalletDataSource = when (chain.provider) {
		Provider.Ton -> ton
		Provider.Gem -> gem
	}
}

class WalletDataSourceException(
	val error: GemError,
	val partialBroadcast: BroadcastedTransaction? = null,
	cause: Throwable? = null,
) : IllegalStateException(error.toString(), cause)
