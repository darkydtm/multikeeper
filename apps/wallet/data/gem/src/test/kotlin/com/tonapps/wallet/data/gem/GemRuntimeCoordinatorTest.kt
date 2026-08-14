package com.tonapps.wallet.data.gem

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GemRuntimeCoordinatorTest {
	@Test
	fun `authoritative refresh stores balance invalidations`() = runBlocking {
		val store = GemAuthoritativeRefreshStore(object : GemBackendReader {
			override suspend fun getAssets(walletId: WalletId, fromTimestamp: Long): Result<List<String>> = Result.success(emptyList())

			override suspend fun getTransactions(
				walletId: WalletId,
				fromTimestamp: Long,
				assetId: String?,
			): Result<GemTransactionsResponse?> = Result.success(null)

			override suspend fun getTransaction(transactionId: String): Result<GemTransaction> =
				Result.failure(IllegalArgumentException("Transaction not found"))

			override suspend fun getPortfolioAssets(
				period: String,
				request: GemPortfolioAssetsRequest,
			): Result<GemPortfolioAssets> = Result.failure(IllegalArgumentException("Portfolio not found"))
		})
		val update = GemBalanceInvalidation("wallet", "ethereum")

		store.refresh(GemWebSocketEvent.Balances(listOf(update)))

		assertEquals(setOf(update), store.cache.value.balanceInvalidations)
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
