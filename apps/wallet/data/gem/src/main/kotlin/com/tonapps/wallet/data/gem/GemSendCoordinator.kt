package com.tonapps.wallet.data.gem

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
	private val dataSource: GemWalletDataSource,
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
		return submit(transaction).fold(
			onSuccess = { GemSendState.Submitted(it) },
			onFailure = { GemSendState.Failed(it.toGemError()) },
		)
	}

	suspend fun submit(transaction: PreloadedTransaction): Result<BroadcastedTransaction> {
		val signed = dataSource.signTransaction(transaction).getOrElse { return Result.failure(it) }
		return dataSource.broadcastTransaction(signed)
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
