package com.tonapps.wallet.data.gem

import kotlinx.coroutines.delay

sealed interface GemSendState {
	data object Editing : GemSendState
	data object Loading : GemSendState
	data class Ready(val transaction: PreloadedTransaction) : GemSendState
	data object Signing : GemSendState
	data object Broadcasting : GemSendState
	data class Submitted(val transaction: BroadcastedTransaction) : GemSendState
	data class Failed(val error: GemError) : GemSendState
	data object Cancelled : GemSendState
}

class GemSendCoordinator(
	private val dataSource: WalletDataSource,
	private val pollDelayMillis: Long = 1_000,
	private val maxPolls: Int = 30,
) {
	suspend fun preloadState(draft: TransactionDraft): GemSendState {
		return dataSource.preloadTransaction(draft).fold(
			onSuccess = { GemSendState.Ready(it) },
			onFailure = { GemSendState.Failed(it.toGemError()) },
		)
	}

	suspend fun preload(draft: TransactionDraft): Result<PreloadedTransaction> =
		dataSource.preloadTransaction(draft)

	suspend fun submitState(transaction: PreloadedTransaction): GemSendState {
		return submitAndWait(transaction).fold(
			onSuccess = { GemSendState.Submitted(it) },
			onFailure = { GemSendState.Failed(it.toGemError()) },
		)
	}

	suspend fun submit(transaction: PreloadedTransaction): Result<BroadcastedTransaction> {
		val signed = dataSource.signTransaction(transaction).getOrElse { return Result.failure(it) }
		return dataSource.broadcastTransaction(signed)
	}

	suspend fun submitAndWait(transaction: PreloadedTransaction): Result<BroadcastedTransaction> {
		val broadcasted = submit(transaction).getOrElse { return Result.failure(it) }
		if (broadcasted.transactionIds.isEmpty()) {
			return Result.failure(WalletDataSourceException(GemError.BroadcastFailed("Gem gateway returned no transaction IDs")))
		}

		for (poll in 0 until maxPolls) {
			val statuses = mutableListOf<TransactionStatus>()
			for (transactionId in broadcasted.transactionIds) {
				val status = dataSource.getTransactionStatus(
					walletId = broadcasted.walletId,
					chain = broadcasted.chain,
					transactionId = transactionId,
				).getOrElse { return Result.failure(it) }
				statuses += status
			}
			when {
				statuses.any { it.state == TransactionState.Failed || it.state == TransactionState.Reverted } -> {
					return Result.failure(WalletDataSourceException(GemError.BroadcastFailed("Gem transaction was rejected")))
				}
				statuses.all { it.state == TransactionState.Confirmed } -> return Result.success(broadcasted)
			}
			if (pollDelayMillis > 0) delay(pollDelayMillis)
		}

		return Result.failure(WalletDataSourceException(GemError.StatusUnknown))
	}

	companion object {
		fun draft(
			walletId: WalletId,
			chain: Chain,
			assetId: AssetId,
			sender: String,
			recipient: String,
			amount: String,
			metadata: AssetMetadata.Known,
			isMaxValue: Boolean = false,
			memo: String? = null,
		): TransactionDraft = TransactionDraft(
			walletId = walletId,
			chain = chain,
			assetId = assetId,
			sender = sender,
			recipient = recipient,
			amount = amount,
			assetMetadata = metadata,
			isMaxValue = isMaxValue,
			memo = memo,
		)
	}
}

private fun Throwable.toGemError(): GemError = when (this) {
	is WalletDataSourceException -> error
	else -> GemError.NetworkUnavailable(this)
}
