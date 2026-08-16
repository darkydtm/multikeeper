package com.tonapps.wallet.data.gem

import java.io.IOException
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface GemRuntimeState {
	data object Idle : GemRuntimeState
	data object Starting : GemRuntimeState
	data class Running(val error: GemError? = null) : GemRuntimeState
	data class Failed(val error: GemError) : GemRuntimeState
}

class GemRuntimeCoordinator(
	private val deviceRegistration: GemDeviceRegistrationCoordinator,
	private val walletRegistry: GemWalletRegistry,
	private val keystoreDeleter: GemKeystoreDeleter,
	private val subscriptionRepository: GemSubscriptionRepository,
	private val webSocketClient: GemWebSocketClient,
	private val refresh: GemAuthoritativeRefresh? = null,
) {
	private val _state = MutableStateFlow<GemRuntimeState>(GemRuntimeState.Idle)
	private val _events = MutableSharedFlow<GemWebSocketEvent>(extraBufferCapacity = 64)
	private val _refreshEvents = MutableSharedFlow<GemRefreshEvent>(replay = 64, extraBufferCapacity = 64)
	private val refreshLock = Mutex()
	private var started = false

	val state: StateFlow<GemRuntimeState> = _state.asStateFlow()
	val events: SharedFlow<GemWebSocketEvent> = _events.asSharedFlow()
	val refreshEvents: SharedFlow<GemRefreshEvent> = _refreshEvents.asSharedFlow()

	suspend fun persistWallet(wallet: GemWallet) = refreshLock.withLock {
		walletRegistry.persist(wallet)
	}

	suspend fun deleteWallet(wallet: GemWallet) = refreshLock.withLock {
		deleteWalletLocked(wallet.walletId, wallet.keystoreId)
	}

	suspend fun deleteWallet(walletId: String, keystoreId: String? = null) = refreshLock.withLock {
		deleteWalletLocked(WalletId(walletId), keystoreId)
	}

	suspend fun deleteAllWallets() = refreshLock.withLock {
		walletRegistry.load().forEach { wallet ->
			deleteWalletLocked(wallet.walletId, wallet.keystoreId)
		}
	}

	private fun deleteWalletLocked(walletId: WalletId, keystoreId: String?) {
		val storedKeystoreId = walletRegistry.load(walletId)?.keystoreId ?: keystoreId
		if (storedKeystoreId != null) {
			keystoreDeleter.deleteKeystore(storedKeystoreId)
		}
		walletRegistry.delete(walletId)
	}

	suspend fun refreshSubscriptions(): Result<Unit> = refreshLock.withLock {
		try {
			val accounts = walletRegistry.load().flatMap { it.accounts }
			val result = subscriptionRepository.sync(accounts)
			if (result.isSuccess) {
				_state.value = GemRuntimeState.Running()
				Result.success(Unit)
			} else {
				_state.value = GemRuntimeState.Running(result.exceptionOrNull().toDiagnosticError())
				Result.failure(result.exceptionOrNull() ?: IllegalStateException("Gem subscription sync failed"))
			}
		} catch (error: CancellationException) {
			throw error
		} catch (error: Throwable) {
			_state.value = GemRuntimeState.Running(error.toDiagnosticError())
			Result.failure(error)
		}
	}

	@Synchronized
	fun start(scope: CoroutineScope) {
		if (started) return
		started = true

		scope.launch {
			_state.value = GemRuntimeState.Starting
			try {
				val registration = deviceRegistration.ensureRegistered()
				if (registration.isFailure) {
					_state.value = GemRuntimeState.Failed(registration.exceptionOrNull().toDiagnosticError())
					return@launch
				}

				refreshSubscriptions()

				launch {
					try {
						webSocketClient.connect().collect { event ->
							try {
								processEvent(event)
							} catch (error: CancellationException) {
								throw error
							} catch (error: Throwable) {
								_state.value = GemRuntimeState.Running(error.toDiagnosticError())
							}
							_events.emit(event)
						}
					} catch (error: CancellationException) {
						throw error
					} catch (error: Throwable) {
						_state.value = GemRuntimeState.Running(error.toDiagnosticError())
					}
				}
			} catch (error: CancellationException) {
				throw error
			} catch (error: Throwable) {
				_state.value = GemRuntimeState.Failed(error.toDiagnosticError())
			}
		}
	}

	suspend fun processEvent(event: GemWebSocketEvent) {
		var refreshError: Throwable? = null
		try {
			refresh?.refresh(event)
		} catch (error: CancellationException) {
			throw error
		} catch (error: Throwable) {
			refreshError = error
		}
		when (event) {
			is GemWebSocketEvent.Prices -> Unit
			is GemWebSocketEvent.Balances -> event.updates
				.groupBy { WalletId(it.walletId) }
				.forEach { (walletId, updates) ->
					val chains = updates.mapNotNull { update ->
						Chain.entries.firstOrNull {
							it.provider == Provider.Gem && it.key == update.assetId.substringBefore('_')
						}
					}.toSet()
					if (chains.isNotEmpty()) {
						_refreshEvents.emit(GemRefreshEvent.Balances(walletId, chains))
					}
				}
			is GemWebSocketEvent.Transactions -> {
				if (event.transactionIds.isNotEmpty()) {
					_refreshEvents.emit(
						GemRefreshEvent.Transactions(WalletId(event.walletId), event.transactionIds.toSet()),
					)
				}
			}
		}
		refreshError?.let { throw it }
	}
}

fun interface GemAuthoritativeRefresh {
	suspend fun refresh(event: GemWebSocketEvent)
}

sealed interface GemRefreshEvent {
	data class Balances(val walletId: WalletId, val chains: Set<Chain>) : GemRefreshEvent
	data class Transactions(val walletId: WalletId, val transactionIds: Set<String>) : GemRefreshEvent
}

data class GemRuntimeCache(
	val transactions: Map<String, GemTransaction> = emptyMap(),
)

class GemAuthoritativeRefreshStore(
	private val backend: GemBackendReader,
) : GemAuthoritativeRefresh {
	private val _cache = MutableStateFlow(GemRuntimeCache())

	val cache: StateFlow<GemRuntimeCache> = _cache.asStateFlow()

		override suspend fun refresh(event: GemWebSocketEvent) {
		when (event) {
			is GemWebSocketEvent.Prices -> Unit
			is GemWebSocketEvent.Balances -> Unit
			is GemWebSocketEvent.Transactions -> {
				val transactions = event.transactionIds.associateWith { transactionId ->
					backend.getTransaction(transactionId).getOrThrow()
				}
				_cache.update { current ->
					val bounded = LinkedHashMap<String, GemTransaction>(MAX_CACHE_ENTRIES)
					current.transactions.forEach { (id, transaction) -> bounded[id] = transaction }
					transactions.forEach { (id, transaction) ->
						bounded.remove(id)
						bounded[id] = transaction
					}
					current.copy(
						transactions = bounded.entries
							.takeLast(MAX_CACHE_ENTRIES)
							.associate { it.toPair() },
					)
				}
			}
		}
	}

	private companion object {
		const val MAX_CACHE_ENTRIES = 100
	}
}

private fun Throwable?.toDiagnosticError(): GemError = when (this) {
	is WalletDataSourceException -> error.toDiagnosticError()
	is GemBackendException -> gemError.toDiagnosticError()
	is IOException -> GemError.NetworkUnavailable()
	null -> GemError.BackendRejected("unknown")
	else -> GemError.BackendRejected("unknown")
}

private fun GemError.toDiagnosticError(): GemError = when (this) {
	GemError.AuthenticationRequired -> GemError.AuthenticationRequired
	GemError.DeviceRegistrationRequired -> GemError.DeviceRegistrationRequired
	is GemError.NetworkUnavailable -> GemError.NetworkUnavailable()
	is GemError.BackendRejected -> GemError.BackendRejected(code)
	is GemError.InvalidInput -> GemError.InvalidInput("invalid_input")
	is GemError.UnsupportedOperation -> GemError.UnsupportedOperation("unsupported_operation")
	GemError.SigningCancelled -> GemError.SigningCancelled
	is GemError.SigningFailed -> GemError.SigningFailed("signing_failed")
	is GemError.BroadcastFailed -> GemError.BroadcastFailed("broadcast_failed")
	GemError.StatusUnknown -> GemError.StatusUnknown
}
