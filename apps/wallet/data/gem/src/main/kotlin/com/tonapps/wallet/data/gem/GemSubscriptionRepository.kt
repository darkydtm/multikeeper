package com.tonapps.wallet.data.gem

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface GemSubscriptionBackend {
	suspend fun getSubscriptions(): Result<List<GemWalletSubscriptionChains>?>

	suspend fun addSubscriptions(subscriptions: List<GemWalletSubscription>): Result<Int>

	suspend fun deleteSubscriptions(subscriptions: List<GemWalletSubscriptionChains>): Result<Int>
}

data class GemSubscriptionSyncResult(
	val add: List<GemWalletSubscription>,
	val delete: List<GemWalletSubscriptionChains>,
)

class GemSubscriptionRepository(
	private val backend: GemSubscriptionBackend,
) {
	private val mutex = Mutex()

	suspend fun sync(accounts: List<ChainAccount>): Result<GemSubscriptionSyncResult> = mutex.withLock {
		if (accounts.any { it.chain.provider == Provider.Gem && it.address.isBlank() }) {
			return@withLock Result.failure(
				WalletDataSourceException(GemError.InvalidInput("Gem account address must not be blank")),
			)
		}

		val desiredAccounts = accounts
			.asSequence()
			.filter { it.chain.provider == Provider.Gem }
			.distinct()
			.toList()
		val desiredPairs = desiredAccounts
			.map { it.walletId.value to it.chain.key }
			.toSet()
		var appliedAdditions = emptyList<GemWalletSubscription>()
		var appliedDeletions = emptyList<GemWalletSubscriptionChains>()
		var lastError: Throwable? = null

		repeat(MAX_RECONCILIATION_ATTEMPTS) {
			val currentResult = backend.getSubscriptions()
			if (currentResult.isFailure) {
				lastError = currentResult.exceptionOrNull()
				return@repeat
			}
			val current = currentResult.getOrThrow().orEmpty()
			val currentPairs = current
				.flatMap { subscription -> subscription.chains.map { subscription.walletId to it } }
				.toSet()
			val additions = desiredAccounts
				.filter { (it.walletId.value to it.chain.key) !in currentPairs }
				.groupBy { it.walletId.value to it.address }
				.entries
				.sortedWith(compareBy({ it.key.first }, { it.key.second }))
				.map { (walletAndAddress, accounts) ->
					GemWalletSubscription(
						walletId = walletAndAddress.first,
						subscriptions = listOf(
							GemAddressChains(
								address = walletAndAddress.second,
								chains = accounts.map { it.chain.key }.distinct().sorted(),
							),
						),
					)
				}
			val deletions = (currentPairs - desiredPairs)
				.groupBy { it.first }
				.toSortedMap()
				.map { (walletId, pairs) ->
					GemWalletSubscriptionChains(
						walletId = walletId,
						chains = pairs.map { it.second }.sorted(),
					)
				}

			if (additions.isEmpty() && deletions.isEmpty()) {
				return@withLock Result.success(GemSubscriptionSyncResult(appliedAdditions, appliedDeletions))
			}
			if (additions.isNotEmpty()) {
				val addResult = backend.addSubscriptions(additions)
				if (addResult.isFailure) {
					lastError = addResult.exceptionOrNull()
					return@repeat
				}
				appliedAdditions += additions
			}
			if (deletions.isNotEmpty()) {
				val deleteResult = backend.deleteSubscriptions(deletions)
				if (deleteResult.isFailure) {
					lastError = deleteResult.exceptionOrNull()
					return@repeat
				}
				appliedDeletions += deletions
			}
			return@withLock Result.success(GemSubscriptionSyncResult(appliedAdditions, appliedDeletions))
		}

		Result.failure(lastError ?: IllegalStateException("Gem subscription reconciliation did not converge"))
	}

	private companion object {
		const val MAX_RECONCILIATION_ATTEMPTS = 3
	}
}
