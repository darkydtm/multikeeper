package com.tonapps.wallet.data.gem

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GemRuntimeCoordinatorTest {
	@Test
	fun `emits a chain-aware balance refresh event`() = runBlocking {
		val coordinator = coordinator()
		val event = async(start = CoroutineStart.UNDISPATCHED) {
			coordinator.refreshEvents.first()
		}

		coordinator.processEvent(
			GemWebSocketEvent.Balances(
				listOf(GemBalanceInvalidation("wallet", "ethereum_native")),
			),
		)

		assertEquals(
			GemRefreshEvent.Balances(WalletId("wallet"), setOf(Chain.Ethereum)),
			event.await(),
		)
	}

	@Test
	fun `delivers a refresh event to a late subscriber`() = runBlocking {
		val coordinator = coordinator()
		coordinator.processEvent(
			GemWebSocketEvent.Balances(
				listOf(GemBalanceInvalidation("wallet", "ethereum_native")),
			),
		)

		assertEquals(
			GemRefreshEvent.Balances(WalletId("wallet"), setOf(Chain.Ethereum)),
			coordinator.refreshEvents.first(),
		)
	}

	@Test
	fun `coalesces balance and transaction invalidations for active subscribers`() = runBlocking {
		val coordinator = coordinator()
		val firstEvents = async(start = CoroutineStart.UNDISPATCHED) {
			coordinator.refreshEvents.take(2).toList()
		}
		val secondEvents = async(start = CoroutineStart.UNDISPATCHED) {
			coordinator.refreshEvents.take(2).toList()
		}

		coordinator.processEvent(
			GemWebSocketEvent.Balances(
				listOf(
					GemBalanceInvalidation("wallet", "ethereum_native"),
					GemBalanceInvalidation("wallet", "ethereum_native"),
					GemBalanceInvalidation("wallet", "bitcoin_native"),
				),
			),
		)
		coordinator.processEvent(GemWebSocketEvent.Transactions("wallet", listOf("tx-1", "tx-1")))

		assertEquals(
			listOf(
				GemRefreshEvent.Balances(WalletId("wallet"), setOf(Chain.Ethereum, Chain.Bitcoin)),
				GemRefreshEvent.Transactions(WalletId("wallet"), setOf("tx-1")),
			),
			firstEvents.await(),
		)
		assertEquals(firstEvents.await(), secondEvents.await())
	}

	@Test
	fun `delivers repeated balance and transaction invalidations`() = runBlocking {
		val coordinator = coordinator()
		val events = async(start = CoroutineStart.UNDISPATCHED) {
			coordinator.refreshEvents.take(4).toList()
		}

		repeat(2) {
			coordinator.processEvent(
				GemWebSocketEvent.Balances(
					listOf(GemBalanceInvalidation("wallet", "ethereum_native")),
				),
			)
			coordinator.processEvent(GemWebSocketEvent.Transactions("wallet", listOf("tx-1", "tx-1")))
		}

		assertEquals(
			listOf(
				GemRefreshEvent.Balances(WalletId("wallet"), setOf(Chain.Ethereum)),
				GemRefreshEvent.Transactions(WalletId("wallet"), setOf("tx-1")),
				GemRefreshEvent.Balances(WalletId("wallet"), setOf(Chain.Ethereum)),
				GemRefreshEvent.Transactions(WalletId("wallet"), setOf("tx-1")),
			),
			events.await(),
		)
	}

	@Test
	fun `bounds replay to the latest refresh events`() = runBlocking {
		val coordinator = coordinator()
		repeat(65) { index ->
			coordinator.processEvent(GemWebSocketEvent.Transactions("wallet", listOf("tx-$index")))
		}

		val events = coordinator.refreshEvents.take(64).toList()

		assertEquals(
			(1..64).map { GemRefreshEvent.Transactions(WalletId("wallet"), setOf("tx-$it")) },
			events,
		)
	}

	@Test
	fun `delivers a reinserted wallet refresh to a slow subscriber`() = runBlocking {
		val coordinator = coordinator()
		val events = mutableListOf<GemRefreshEvent>()
		val firstEvent = CompletableDeferred<Unit>()
		val release = CompletableDeferred<Unit>()
		val collector = launch(start = CoroutineStart.UNDISPATCHED) {
			coordinator.refreshEvents.take(66).collect { event ->
				events += event
				if (events.size == 1) {
					firstEvent.complete(Unit)
					release.await()
				}
			}
		}

		coordinator.processEvent(GemWebSocketEvent.Transactions("wallet-0", listOf("tx-0")))
		firstEvent.await()
		repeat(64) { index ->
			coordinator.processEvent(GemWebSocketEvent.Transactions("wallet-${index + 1}", listOf("tx-${index + 1}")))
		}
		val reinsert = async {
			coordinator.processEvent(GemWebSocketEvent.Transactions("wallet-0", listOf("tx-0")))
		}
		release.complete(Unit)
		reinsert.await()
		collector.join()

		assertEquals(66, events.size)
		assertEquals(
			GemRefreshEvent.Transactions(WalletId("wallet-0"), setOf("tx-0")),
			events.last(),
		)
	}

	@Test
	fun `emits transaction refresh ids after authoritative refresh`() = runBlocking {
		val coordinator = coordinator()
		val event = async(start = CoroutineStart.UNDISPATCHED) {
			coordinator.refreshEvents.first()
		}

		coordinator.processEvent(
			GemWebSocketEvent.Transactions("wallet", listOf("tx-1", "tx-1")),
		)

		assertEquals(
			GemRefreshEvent.Transactions(WalletId("wallet"), setOf("tx-1")),
			event.await(),
		)
	}

	@Test
	fun `emits transaction refresh ids when authoritative refresh fails`() = runBlocking {
		val event = CompletableDeferred<GemRefreshEvent>()
		val coordinator = coordinator(
			refresh = GemAuthoritativeRefresh { error("offline") },
		)
		val collector = async(start = CoroutineStart.UNDISPATCHED) {
			event.complete(coordinator.refreshEvents.first())
		}

		try {
			coordinator.processEvent(GemWebSocketEvent.Transactions("wallet", listOf("tx-1")))
			fail("Expected authoritative refresh to fail")
		} catch (error: IllegalStateException) {
			assertEquals("offline", error.message)
		}

		assertEquals(
			GemRefreshEvent.Transactions(WalletId("wallet"), setOf("tx-1")),
			event.await(),
		)
		collector.cancel()
	}

	@Test
	fun `refreshes subscriptions from the current registry and reports network errors`() = runBlocking {
		val wallet = GemWallet(
			walletId = WalletId("wallet"),
			keystoreId = "keystore",
			accounts = listOf(ChainAccount(WalletId("wallet"), Chain.Ethereum, "address")),
		)
		val registry = GemWalletRegistry(InMemoryRegistryStorage().also { storage ->
			GemWalletRegistry(storage).persist(wallet)
		})
		val backend = RefreshSubscriptionBackend(Result.failure(IOException("offline")))
		val coordinator = GemRuntimeCoordinator(
			deviceRegistration = testDeviceRegistrationCoordinator(),
			walletRegistry = registry,
			keystoreDeleter = GemKeystoreDeleter { },
			subscriptionRepository = GemSubscriptionRepository(backend),
			webSocketClient = GemWebSocketClient(
				client = OkHttpClient.Builder().build(),
				signer = GemRequestSigner { _, _, _, _ -> "authorization" },
			),
		)

		val result = coordinator.refreshSubscriptions()

		assertEquals(1, backend.getSubscriptionsCalls)
		assertTrue(result.isFailure)
		assertTrue((coordinator.state.value as GemRuntimeState.Running).error is GemError.NetworkUnavailable)
	}

	@Test
	fun `waits for refresh before persisting a wallet`() = runBlocking {
		val wallet = GemWallet(
			walletId = WalletId("wallet"),
			keystoreId = "keystore",
			accounts = listOf(ChainAccount(WalletId("wallet"), Chain.Ethereum, "address")),
		)
		val registryStorage = InMemoryRegistryStorage().also { storage ->
			GemWalletRegistry(storage).persist(wallet)
		}
		val registry = GemWalletRegistry(registryStorage)
		val backend = BlockingRefreshBackend()
		val coordinator = GemRuntimeCoordinator(
			deviceRegistration = testDeviceRegistrationCoordinator(),
			walletRegistry = registry,
			keystoreDeleter = GemKeystoreDeleter { },
			subscriptionRepository = GemSubscriptionRepository(backend),
			webSocketClient = GemWebSocketClient(
				client = OkHttpClient.Builder().build(),
				signer = GemRequestSigner { _, _, _, _ -> "authorization" },
			),
		)

		val refresh = async(start = CoroutineStart.UNDISPATCHED) { coordinator.refreshSubscriptions() }
		backend.firstSyncStarted.await()
		val secondWallet = wallet.copy(walletId = WalletId("second"))
		val persist = async(start = CoroutineStart.UNDISPATCHED) { coordinator.persistWallet(secondWallet) }
		assertFalse(persist.isCompleted)
		backend.release.complete(Unit)

		assertTrue(refresh.await().isSuccess)
		persist.await()
		assertEquals(2, registry.load().size)
	}

	@Test
	fun `deletes the keystore before removing the wallet from the registry`() = runBlocking {
		val wallet = GemWallet(
			walletId = WalletId("wallet"),
			keystoreId = "keystore",
			accounts = listOf(ChainAccount(WalletId("wallet"), Chain.Ethereum, "address")),
		)
		val registry = GemWalletRegistry(InMemoryRegistryStorage().also { storage ->
			GemWalletRegistry(storage).persist(wallet)
		})
		var deletedKeystore: String? = null
		val coordinator = GemRuntimeCoordinator(
			deviceRegistration = testDeviceRegistrationCoordinator(),
			walletRegistry = registry,
			keystoreDeleter = GemKeystoreDeleter { deletedKeystore = it },
			subscriptionRepository = GemSubscriptionRepository(RefreshSubscriptionBackend(Result.success(null))),
			webSocketClient = GemWebSocketClient(
				client = OkHttpClient.Builder().build(),
				signer = GemRequestSigner { _, _, _, _ -> "authorization" },
			),
		)

		coordinator.deleteWallet(wallet.walletId.value)

		assertEquals(wallet.keystoreId, deletedKeystore)
		assertTrue(registry.load().isEmpty())
	}

	private fun testDeviceRegistrationCoordinator() = GemDeviceRegistrationCoordinator(
		identity = object : GemDeviceIdentityProvider {
			override fun getDeviceId(): String = "device"
		},
		backend = object : GemDeviceBackend {
			override suspend fun getDevice(): Result<GemDevice?> = Result.success(null)
			override suspend fun registerDevice(device: GemDevice): Result<GemDevice?> = Result.success(device)
			override suspend fun updateDevice(device: GemDevice): Result<GemDevice?> = Result.success(device)
		},
		metadata = GemDeviceMetadata(
			platform = "android",
			platformStore = "googlePlay",
			os = "android",
			model = "test",
			token = "",
			locale = "en",
			version = "test",
			currency = "USD",
			isPushEnabled = false,
			subscriptionsVersion = 1,
		),
	)

	private fun coordinator(
		refresh: GemAuthoritativeRefresh = GemAuthoritativeRefresh { },
	) = GemRuntimeCoordinator(
		deviceRegistration = testDeviceRegistrationCoordinator(),
		walletRegistry = GemWalletRegistry(InMemoryRegistryStorage()),
		keystoreDeleter = GemKeystoreDeleter { },
		subscriptionRepository = GemSubscriptionRepository(RefreshSubscriptionBackend(Result.success(null))),
		webSocketClient = GemWebSocketClient(
			client = OkHttpClient.Builder().build(),
			signer = GemRequestSigner { _, _, _, _ -> "authorization" },
		),
		refresh = refresh,
	)
}

private class InMemoryRegistryStorage : GemWalletRegistryStorage {
	private var value: String? = null

	override fun read(): String? = value

	override fun write(value: String): Boolean {
		this.value = value
		return true
	}

	override fun delete(): Boolean {
		value = null
		return true
	}
}

private class RefreshSubscriptionBackend(
	private val result: Result<List<GemWalletSubscriptionChains>?>,
) : GemSubscriptionBackend {
	var getSubscriptionsCalls = 0

	override suspend fun getSubscriptions(): Result<List<GemWalletSubscriptionChains>?> {
		getSubscriptionsCalls++
		return result
	}

	override suspend fun addSubscriptions(subscriptions: List<GemWalletSubscription>): Result<Int> =
		Result.success(subscriptions.size)

	override suspend fun deleteSubscriptions(subscriptions: List<GemWalletSubscriptionChains>): Result<Int> =
		Result.success(subscriptions.size)
}

private class BlockingRefreshBackend : GemSubscriptionBackend {
	val firstSyncStarted = CompletableDeferred<Unit>()
	val release = CompletableDeferred<Unit>()

	override suspend fun getSubscriptions(): Result<List<GemWalletSubscriptionChains>?> {
		firstSyncStarted.complete(Unit)
		release.await()
		return Result.success(null)
	}

	override suspend fun addSubscriptions(subscriptions: List<GemWalletSubscription>): Result<Int> {
		return Result.success(subscriptions.size)
	}

	override suspend fun deleteSubscriptions(subscriptions: List<GemWalletSubscriptionChains>): Result<Int> =
		Result.success(subscriptions.size)
}
