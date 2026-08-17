package com.tonapps.wallet.data.gem

import kotlinx.coroutines.delay

sealed interface GemSendState {
	data object Editing : GemSendState
	data object Loading : GemSendState
	data class Ready(val transaction: PreloadedTransaction) : GemSendState
	data object Signing : GemSendState
	data object Broadcasting : GemSendState
	data class Confirmed(val transaction: BroadcastedTransaction) : GemSendState
	data class Submitted(val transaction: BroadcastedTransaction) : GemSendState
	data class TimedOut(val transaction: BroadcastedTransaction) : GemSendState
	data class Unknown(
		val transaction: BroadcastedTransaction,
		val error: GemError = GemError.StatusUnknown,
		val cause: Throwable? = null,
	) : GemSendState
	data class Failed(val transaction: BroadcastedTransaction?, val error: GemError) : GemSendState
	data class Reverted(val transaction: BroadcastedTransaction, val error: GemError) : GemSendState
	data object Cancelled : GemSendState
}

sealed interface GemSendOutcome {
	data class Confirmed(val transaction: BroadcastedTransaction) : GemSendOutcome
	data class Submitted(
		val transaction: BroadcastedTransaction,
		val cause: Throwable? = null,
	) : GemSendOutcome
	data class TimedOut(val transaction: BroadcastedTransaction) : GemSendOutcome
	data class Unknown(
		val transaction: BroadcastedTransaction,
		val cause: Throwable? = null,
		val error: GemError = GemError.StatusUnknown,
	) : GemSendOutcome
	data class Failed(
		val transaction: BroadcastedTransaction?,
		val error: GemError,
		val terminal: Boolean = false,
	) : GemSendOutcome
	data class Reverted(
		val transaction: BroadcastedTransaction,
		val error: GemError,
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
			onFailure = { GemSendState.Failed(null, it.toGemError()) },
		)
	}

	suspend fun preload(draft: TransactionDraft): Result<PreloadedTransaction> =
		dataSource.preloadTransaction(draft)

	suspend fun submitState(transaction: PreloadedTransaction): GemSendState {
		return when (val outcome = submitOutcome(transaction)) {
			is GemSendOutcome.Confirmed -> GemSendState.Confirmed(outcome.transaction)
			is GemSendOutcome.Submitted -> GemSendState.Submitted(outcome.transaction)
			is GemSendOutcome.TimedOut -> GemSendState.TimedOut(outcome.transaction)
			is GemSendOutcome.Unknown -> GemSendState.Unknown(outcome.transaction, outcome.error, outcome.cause)
			is GemSendOutcome.Failed -> GemSendState.Failed(outcome.transaction, outcome.error)
			is GemSendOutcome.Reverted -> GemSendState.Reverted(outcome.transaction, outcome.error)
		}
	}

	suspend fun submit(transaction: PreloadedTransaction): Result<BroadcastedTransaction> {
		val signed = dataSource.signTransaction(transaction).getOrElse { return Result.failure(it) }
		return dataSource.broadcastTransaction(signed)
	}

	suspend fun submitAndWait(transaction: PreloadedTransaction): Result<BroadcastedTransaction> {
		return when (val outcome = submitOutcome(transaction)) {
			is GemSendOutcome.Confirmed -> Result.success(outcome.transaction)
			else -> Result.failure(outcome.toException())
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
				SignedTransaction(recovery.walletId, recovery.chain, recovery.unsubmittedPayloads),
			)
			val broadcasted = retry.getOrElse { error ->
				val partial = (error as? WalletDataSourceException)?.partialBroadcast
				val merged = recovery.merge(partial)
				return GemSendOutcome.Failed(merged, error.toGemError())
			}
			return waitForOutcome(recovery.merge(broadcasted))
		}

		val broadcasted = submit(transaction).getOrElse {
			val dataSourceError = it as? WalletDataSourceException
			val partialBroadcast = dataSourceError?.partialBroadcast
			if (partialBroadcast != null) {
				if (partialBroadcast.transactionIds.isEmpty()) {
					return GemSendOutcome.Failed(partialBroadcast, it.toGemError())
				}
				return waitForOutcome(partialBroadcast)
			}
			return GemSendOutcome.Failed(null, it.toGemError())
		}
		return waitForOutcome(broadcasted)
	}

	suspend fun waitForOutcome(broadcasted: BroadcastedTransaction): GemSendOutcome {
		if (broadcasted.transactionIds.isEmpty()) {
			return GemSendOutcome.Failed(
				WalletDataSourceException(GemError.BroadcastFailed("Gem gateway returned no transaction IDs")),
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
				).getOrElse {
					return GemSendOutcome.Unknown(broadcasted, error = it.toGemError(), cause = it)
				}
				statuses += status
			}
			when {
				statuses.any { it.state == TransactionState.Reverted } -> return GemSendOutcome.Reverted(
					broadcasted,
					GemError.BroadcastFailed("Gem transaction was reverted"),
				)
				statuses.any { it.state == TransactionState.Failed } -> return GemSendOutcome.Failed(
					broadcasted,
					GemError.BroadcastFailed("Gem transaction was rejected"),
					terminal = true,
				)
				statuses.any { it.state == TransactionState.Unknown } -> return GemSendOutcome.Unknown(broadcasted)
				statuses.all { it.state == TransactionState.Confirmed } &&
					statuses.size == broadcasted.payloadCount -> return GemSendOutcome.Confirmed(broadcasted)
				statuses.all { it.state == TransactionState.Confirmed } -> return GemSendOutcome.Submitted(broadcasted)
			}
			if (pollDelayMillis > 0) delay(pollDelayMillis)
		}

		return GemSendOutcome.TimedOut(broadcasted)
	}

	private fun BroadcastedTransaction.merge(other: BroadcastedTransaction?): BroadcastedTransaction = copy(
		transactionIds = transactionIds + other?.transactionIds.orEmpty(),
		unsubmittedPayloads = if (other?.transactionIds?.isNotEmpty() == true) {
			other.unsubmittedPayloads
		} else {
			unsubmittedPayloads
		},
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
	is GemBackendException -> gemError
	else -> GemError.NetworkUnavailable(this)
}

private fun GemSendOutcome.toException(): Throwable = when (this) {
	is GemSendOutcome.Confirmed -> error("Confirmed transaction cannot be converted to an error")
	is GemSendOutcome.Submitted -> WalletDataSourceException(GemError.StatusUnknown, partialBroadcast = transaction)
	is GemSendOutcome.TimedOut -> WalletDataSourceException(GemError.StatusUnknown, partialBroadcast = transaction)
	is GemSendOutcome.Unknown -> WalletDataSourceException(error, partialBroadcast = transaction, cause = cause)
	is GemSendOutcome.Failed -> WalletDataSourceException(error, partialBroadcast = transaction)
	is GemSendOutcome.Reverted -> WalletDataSourceException(error, partialBroadcast = transaction)
}
