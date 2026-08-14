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
