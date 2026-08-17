package com.tonapps.wallet.data.gem

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
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
	fun `coalesces only the initial snapshot and preserves live invalidations`() = runBlocking {
		val coordinator = coordinator()
		val first = GemRefreshEvent.Balances(WalletId("wallet"), setOf(Chain.Ethereum))
		coordinator.processEvent(
			GemWebSocketEvent.Balances(
				listOf(GemBalanceInvalidation("wallet", "ethereum_native")),
			),
		)

		val deliveries = async(start = CoroutineStart.UNDISPATCHED) {
			coordinator.refreshEventsForConsumer().take(3).toList()
		}
		coordinator.processEvent(
			GemWebSocketEvent.Balances(
				listOf(GemBalanceInvalidation("wallet", "ethereum_native")),
			),
		)
		coordinator.processEvent(
			GemWebSocketEvent.Balances(
				listOf(GemBalanceInvalidation("wallet", "ethereum_native")),
			),
		)

		assertEquals(
			listOf(
				GemRefreshDelivery.Initial(listOf(first)),
				GemRefreshDelivery.Live(first),
				GemRefreshDelivery.Live(first),
			),
			deliveries.await(),
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
	fun `retries startup after device registration failure`() = runBlocking {
		var registrationCalls = 0
		val coordinator = GemRuntimeCoordinator(
			deviceRegistration = GemDeviceRegistrationCoordinator(
				identity = object : GemDeviceIdentityProvider {
					override fun getDeviceId(): String = "device"
				},
				backend = object : GemDeviceBackend {
					override suspend fun getDevice(): Result<GemDevice?> = Result.success(null)
					override suspend fun registerDevice(device: GemDevice): Result<GemDevice?> {
						registrationCalls++
						return if (registrationCalls == 1) {
							Result.failure(IOException("offline"))
						} else {
							Result.success(device)
						}
					}
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
			),
			walletRegistry = GemWalletRegistry(InMemoryRegistryStorage()),
			keystoreDeleter = GemKeystoreDeleter { },
			subscriptionRepository = GemSubscriptionRepository(RefreshSubscriptionBackend(Result.success(null))),
			webSocketClient = GemWebSocketClient(
				client = OkHttpClient.Builder().build(),
				signer = GemRequestSigner { _, _, _, _ -> "authorization" },
			),
		)
		val scope = CoroutineScope(Job())

		coordinator.start(scope)
		assertTrue(coordinator.state.first { it is GemRuntimeState.Running } is GemRuntimeState.Running)
		assertEquals(2, registrationCalls)

		scope.cancel()
	}

	@Test
	fun `bounds startup retries after repeated registration failures`() = runBlocking {
		var registrationCalls = 0
		val coordinator = coordinator(
			deviceRegistration = testDeviceRegistrationCoordinator {
				registrationCalls++
				Result.failure(IOException("offline"))
			},
		)
		val scope = CoroutineScope(Job())

		coordinator.start(scope)

		assertTrue(coordinator.state.first { it is GemRuntimeState.Failed } is GemRuntimeState.Failed)
		assertEquals(3, registrationCalls)

		val secondFailure = async(start = CoroutineStart.UNDISPATCHED) {
			coordinator.state.drop(1).first { it is GemRuntimeState.Failed }
		}
		coordinator.start(scope)
		assertTrue(secondFailure.await() is GemRuntimeState.Failed)
		assertEquals(6, registrationCalls)

		scope.cancel()
	}

	@Test
	fun `allows startup again after the startup scope is cancelled`() = runBlocking {
		var registrationCalls = 0
		val firstRegistrationStarted = CompletableDeferred<Unit>()
		val releaseFirstRegistration = CompletableDeferred<Unit>()
		val coordinator = coordinator(
			deviceRegistration = testDeviceRegistrationCoordinator { device ->
				registrationCalls++
				if (registrationCalls == 1) {
					firstRegistrationStarted.complete(Unit)
					releaseFirstRegistration.await()
				}
				Result.failure(IOException("offline"))
			},
		)
		val firstJob = Job()
		val firstScope = CoroutineScope(firstJob)

		coordinator.start(firstScope)
		firstRegistrationStarted.await()
		firstScope.cancel()

		val secondScope = CoroutineScope(Job())
		coordinator.start(secondScope)
		releaseFirstRegistration.complete(Unit)
		firstJob.join()

		assertTrue(coordinator.state.first { it is GemRuntimeState.Failed } is GemRuntimeState.Failed)
		assertEquals(4, registrationCalls)

		secondScope.cancel()
	}

	@Test
	fun `does not report running while startup subscription sync is failing`() = runBlocking {
		val coordinator = coordinator(
			subscriptionBackend = RefreshSubscriptionBackend(Result.failure(IOException("offline"))),
		)
		val states = async(start = CoroutineStart.UNDISPATCHED) {
			coordinator.state.takeWhile { state ->
				state !is GemRuntimeState.Failed
			}.toList()
		}
		val scope = CoroutineScope(Job())

		coordinator.start(scope)

		val observedStates = states.await()
		assertTrue(observedStates.none { it is GemRuntimeState.Running })
		assertTrue(coordinator.state.value is GemRuntimeState.Failed)
		scope.cancel()
	}

	@Test
	fun `refresh does not overwrite a failed startup`() = runBlocking {
		val registrationsFinished = CompletableDeferred<Unit>()
		var registrationCalls = 0
		val backend = BlockingRefreshBackend()
		val coordinator = coordinator(
			deviceRegistration = testDeviceRegistrationCoordinator {
				registrationCalls++
				if (registrationCalls == 3) registrationsFinished.complete(Unit)
				Result.failure(IOException("offline"))
			},
			subscriptionBackend = backend,
		)
		val scope = CoroutineScope(Job())

		val refresh = async(start = CoroutineStart.UNDISPATCHED) { coordinator.refreshSubscriptions() }
		backend.firstSyncStarted.await()
		coordinator.start(scope)
		registrationsFinished.await()
		assertTrue(coordinator.state.first { it is GemRuntimeState.Failed } is GemRuntimeState.Failed)
		backend.release.complete(Unit)

		assertTrue(refresh.await().isSuccess)
		assertTrue(coordinator.state.value is GemRuntimeState.Failed)
		scope.cancel()
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

	private fun testDeviceRegistrationCoordinator(
		registerDevice: suspend (GemDevice) -> Result<GemDevice?> = { device -> Result.success(device) },
	) = GemDeviceRegistrationCoordinator(
		identity = object : GemDeviceIdentityProvider {
			override fun getDeviceId(): String = "device"
		},
		backend = object : GemDeviceBackend {
			override suspend fun getDevice(): Result<GemDevice?> = Result.success(null)
			override suspend fun registerDevice(device: GemDevice): Result<GemDevice?> = registerDevice(device)
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
		deviceRegistration: GemDeviceRegistrationCoordinator = testDeviceRegistrationCoordinator(),
		subscriptionBackend: GemSubscriptionBackend = RefreshSubscriptionBackend(Result.success(null)),
		refresh: GemAuthoritativeRefresh = GemAuthoritativeRefresh { },
	) = GemRuntimeCoordinator(
		deviceRegistration = deviceRegistration,
		walletRegistry = GemWalletRegistry(InMemoryRegistryStorage()),
		keystoreDeleter = GemKeystoreDeleter { },
		subscriptionRepository = GemSubscriptionRepository(subscriptionBackend),
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
