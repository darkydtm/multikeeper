package com.tonapps.wallet.data.gem

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GemSubscriptionRepositoryTest {
	@Test
	fun `treats server subscriptions as authoritative by wallet and chain`() = runBlocking {
		val backend = RecordingSubscriptionBackend(
			current = listOf(GemWalletSubscriptionChains("wallet", listOf("ethereum", "bitcoin"))),
		)
		val repository = GemSubscriptionRepository(backend)

		val result = repository.sync(
			listOf(ChainAccount(WalletId("wallet"), Chain.Ethereum, "local-ethereum")),
		)

		assertTrue(result.isSuccess)
		assertTrue(backend.added.isEmpty())
		assertEquals(
			listOf(GemWalletSubscriptionChains("wallet", listOf("bitcoin"))),
			backend.deleted,
		)
	}

	@Test
	fun `deletes only explicitly reported stale wallet chains`() = runBlocking {
		val backend = RecordingSubscriptionBackend(
			current = listOf(
				GemWalletSubscriptionChains("wallet", listOf("ethereum", "bitcoin")),
				GemWalletSubscriptionChains("other-wallet", listOf("solana")),
			),
		)
		val repository = GemSubscriptionRepository(backend)

		val result = repository.sync(
			listOf(
				ChainAccount(WalletId("wallet"), Chain.Ethereum, "local-ethereum"),
				ChainAccount(WalletId("other-wallet"), Chain.Solana, "local-solana"),
			),
		)

		assertTrue(result.isSuccess)
		assertEquals(
			listOf(GemWalletSubscriptionChains("wallet", listOf("bitcoin"))),
			backend.deleted,
		)
	}

	@Test
	fun `groups local accounts by wallet and address with canonical chain keys`() = runBlocking {
		val backend = RecordingSubscriptionBackend()
		val repository = GemSubscriptionRepository(backend)

		repository.sync(
			listOf(
				ChainAccount(WalletId("wallet"), Chain.SmartChain, "same-address"),
				ChainAccount(WalletId("wallet"), Chain.Ethereum, "same-address"),
				ChainAccount(WalletId("wallet"), Chain.Solana, "other-address"),
				ChainAccount(WalletId("ton"), Chain.Ton, "ton-address"),
			),
		)

		assertEquals(
			listOf(
				GemWalletSubscription(
					walletId = "wallet",
					subscriptions = listOf(
						GemAddressChains("other-address", listOf("solana")),
						GemAddressChains("same-address", listOf("ethereum", "smartchain")),
					),
				),
			),
			backend.added,
		)
		assertTrue(backend.deleted.isEmpty())
	}

	@Test
	fun `retries reconciliation after a partial delete failure`() = runBlocking {
		val backend = StatefulSubscriptionBackend(
			current = listOf(GemWalletSubscriptionChains("wallet", listOf("bitcoin"))),
			failDeleteOnce = true,
		)
		val repository = GemSubscriptionRepository(backend)

		val result = repository.sync(
			listOf(ChainAccount(WalletId("wallet"), Chain.Ethereum, "ethereum-address")),
		)

		assertTrue(result.isSuccess)
		assertEquals(
			listOf(GemWalletSubscriptionChains("wallet", listOf("ethereum"))),
			backend.current,
		)
		assertEquals(2, backend.getSubscriptionsCalls)
		assertEquals(1, backend.addSubscriptionsCalls)
		assertEquals(2, backend.deleteSubscriptionsCalls)
	}

	@Test
	fun `retries reconciliation after a partial add failure`() = runBlocking {
		val backend = StatefulSubscriptionBackend(
			current = listOf(GemWalletSubscriptionChains("wallet", listOf("bitcoin"))),
			failAddOnce = true,
		)
		val repository = GemSubscriptionRepository(backend)

		val result = repository.sync(
			listOf(ChainAccount(WalletId("wallet"), Chain.Ethereum, "ethereum-address")),
		)

		assertTrue(result.isSuccess)
		assertEquals(
			listOf(GemWalletSubscriptionChains("wallet", listOf("ethereum"))),
			backend.current,
		)
		assertEquals(2, backend.getSubscriptionsCalls)
		assertEquals(1, backend.addSubscriptionsCalls)
		assertEquals(1, backend.deleteSubscriptionsCalls)
	}
}

private class RecordingSubscriptionBackend(
	private val current: List<GemWalletSubscriptionChains> = emptyList(),
) : GemSubscriptionBackend {
	val added = mutableListOf<GemWalletSubscription>()
	val deleted = mutableListOf<GemWalletSubscriptionChains>()

	override suspend fun getSubscriptions(): Result<List<GemWalletSubscriptionChains>?> = Result.success(current)

	override suspend fun addSubscriptions(subscriptions: List<GemWalletSubscription>): Result<Int> {
		added += subscriptions
		return Result.success(subscriptions.size)
	}

	override suspend fun deleteSubscriptions(subscriptions: List<GemWalletSubscriptionChains>): Result<Int> {
		deleted += subscriptions
		return Result.success(subscriptions.size)
	}
}

private class StatefulSubscriptionBackend(
	current: List<GemWalletSubscriptionChains>,
	private var failDeleteOnce: Boolean = false,
	private var failAddOnce: Boolean = false,
) : GemSubscriptionBackend {
	var current = current
	var getSubscriptionsCalls = 0
	var addSubscriptionsCalls = 0
	var deleteSubscriptionsCalls = 0

	override suspend fun getSubscriptions(): Result<List<GemWalletSubscriptionChains>?> {
		getSubscriptionsCalls++
		return Result.success(current)
	}

	override suspend fun addSubscriptions(subscriptions: List<GemWalletSubscription>): Result<Int> {
		addSubscriptionsCalls++
		current = current
			.flatMap { existing ->
				subscriptions
					.filter { it.walletId == existing.walletId }
					.fold(existing.chains.toSet()) { chains, addition ->
						chains + addition.subscriptions.flatMap { it.chains }
					}
					.toList()
					.let { chains -> existing.copy(chains = chains) }
			}
		if (failAddOnce) {
			failAddOnce = false
			return Result.failure(IllegalStateException("transient add failure"))
		}
		return Result.success(subscriptions.size)
	}

	override suspend fun deleteSubscriptions(subscriptions: List<GemWalletSubscriptionChains>): Result<Int> {
		deleteSubscriptionsCalls++
		if (failDeleteOnce) {
			failDeleteOnce = false
			return Result.failure(IllegalStateException("transient delete failure"))
		}
		current = current.mapNotNull { existing ->
			val deletion = subscriptions.firstOrNull { it.walletId == existing.walletId }
			val chains = existing.chains - deletion?.chains.orEmpty().toSet()
			if (chains.isEmpty()) null else existing.copy(chains = chains)
		}
		return Result.success(subscriptions.size)
	}
}
