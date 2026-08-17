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

sealed interface GemSendOutcome {
	data class Confirmed(val transaction: BroadcastedTransaction) : GemSendOutcome
	data class Submitted(
		val transaction: BroadcastedTransaction,
		val cause: Throwable? = null,
	) : GemSendOutcome
	data class Failed(
		val error: Throwable,
		val transaction: BroadcastedTransaction? = null,
	) : GemSendOutcome
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
		return when (val outcome = submitOutcome(transaction)) {
			is GemSendOutcome.Confirmed -> GemSendState.Submitted(outcome.transaction)
			is GemSendOutcome.Submitted -> GemSendState.Submitted(outcome.transaction)
			is GemSendOutcome.Failed -> GemSendState.Failed(outcome.error.toGemError())
		}
	}

	suspend fun submit(transaction: PreloadedTransaction): Result<BroadcastedTransaction> {
		val signed = dataSource.signTransaction(transaction).getOrElse { return Result.failure(it) }
		return dataSource.broadcastTransaction(signed)
	}

	suspend fun submitAndWait(transaction: PreloadedTransaction): Result<BroadcastedTransaction> {
		return when (val outcome = submitOutcome(transaction)) {
			is GemSendOutcome.Confirmed -> Result.success(outcome.transaction)
			is GemSendOutcome.Submitted -> Result.failure(
				outcome.cause ?: WalletDataSourceException(GemError.StatusUnknown, outcome.transaction),
			)
			is GemSendOutcome.Failed -> Result.failure(outcome.error)
		}
	}

	suspend fun submitOutcome(
		transaction: PreloadedTransaction,
		recovery: BroadcastedTransaction? = null,
	): GemSendOutcome {
		if (recovery != null) {
			if (recovery.unsubmittedPayloads.isEmpty()) {
				return waitForOutcome(recovery)
			}
			val retry = dataSource.broadcastTransaction(
				SignedTransaction(
					walletId = recovery.walletId,
					chain = recovery.chain,
					payloads = recovery.unsubmittedPayloads,
				),
			)
			val broadcasted = retry.getOrElse { error ->
				val partial = (error as? WalletDataSourceException)?.partialBroadcast
				return GemSendOutcome.Submitted(
					recovery.merge(partial),
					error,
				)
			}
			return waitForOutcome(recovery.merge(broadcasted))
		}

		val broadcasted = submit(transaction).getOrElse { error ->
			val partial = (error as? WalletDataSourceException)?.partialBroadcast
			return if (partial != null) {
				GemSendOutcome.Submitted(partial, error)
			} else {
				GemSendOutcome.Failed(error)
			}
		}
		return waitForOutcome(broadcasted)
	}

	suspend fun waitForOutcome(broadcasted: BroadcastedTransaction): GemSendOutcome {
		if (broadcasted.transactionIds.isEmpty()) {
			return GemSendOutcome.Failed(
				WalletDataSourceException(GemError.BroadcastFailed("Gem gateway returned no transaction IDs")),
			)
		}
		if (broadcasted.unsubmittedPayloads.isNotEmpty()) {
			return GemSendOutcome.Submitted(
				broadcasted,
				WalletDataSourceException(GemError.StatusUnknown, broadcasted),
			)
		}
		if (maxPolls <= 0) {
			return GemSendOutcome.Submitted(broadcasted)
		}

		for (poll in 0 until maxPolls) {
			val statuses = mutableListOf<TransactionStatus>()
			for (transactionId in broadcasted.transactionIds) {
				val status = dataSource.getTransactionStatus(
					walletId = broadcasted.walletId,
					chain = broadcasted.chain,
					transactionId = transactionId,
				).getOrElse { return GemSendOutcome.Submitted(broadcasted, it) }
				statuses += status
			}
			when {
				statuses.any { it.state == TransactionState.Failed || it.state == TransactionState.Reverted } -> {
					return GemSendOutcome.Failed(
						WalletDataSourceException(GemError.BroadcastFailed("Gem transaction was rejected")),
						broadcasted,
					)
				}
				statuses.all { it.state == TransactionState.Confirmed } -> return GemSendOutcome.Confirmed(broadcasted)
			}
			if (pollDelayMillis > 0) delay(pollDelayMillis)
		}

		return GemSendOutcome.Submitted(
			broadcasted,
			WalletDataSourceException(GemError.StatusUnknown, broadcasted),
		)
	}

	private fun BroadcastedTransaction.merge(
		other: BroadcastedTransaction?,
	): BroadcastedTransaction = copy(
		transactionIds = transactionIds + other?.transactionIds.orEmpty(),
		unsubmittedPayloads = other?.unsubmittedPayloads ?: unsubmittedPayloads,
	)

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
