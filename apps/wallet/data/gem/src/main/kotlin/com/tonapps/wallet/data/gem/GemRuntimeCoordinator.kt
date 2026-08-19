package com.tonapps.wallet.data.gem

import java.io.IOException
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
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
	private val webSocketClient: GemWebSocketSource,
	private val refresh: GemAuthoritativeRefresh? = null,
) {
	private val _state = MutableStateFlow<GemRuntimeState>(GemRuntimeState.Idle)
	private val _events = MutableSharedFlow<GemWebSocketEvent>(extraBufferCapacity = 64)
	private val _refreshEvents = MutableSharedFlow<SequencedRefreshEvent>(replay = 64, extraBufferCapacity = 64)
	private val refreshLock = Mutex()
	private val refreshEventLock = Mutex()
	private val startedMonitor = Any()
	private var refreshSequence = 0L
	private var nextStartupId = 0L
	private var activeStartupId: Long? = null
	private var activeStartupOwner: Job? = null
	private var runtimeJob: Job? = null

	val state: StateFlow<GemRuntimeState> = _state.asStateFlow()
	val events: SharedFlow<GemWebSocketEvent> = _events.asSharedFlow()
	val refreshEvents: Flow<GemRefreshEvent> = _refreshEvents.asSharedFlow().map { it.event }

	fun refreshEventsForConsumer(): Flow<GemRefreshDelivery> {
		val initial = _refreshEvents.replayCache
		val sequence = initial.lastOrNull()?.sequence ?: 0L
		return flow {
			if (initial.isNotEmpty()) {
				emit(GemRefreshDelivery.Initial(initial.map { it.event }))
			}
			_refreshEvents
				.filter { it.sequence > sequence }
				.collect { emit(GemRefreshDelivery.Live(it.event)) }
		}
	}

	suspend fun persistWallet(wallet: GemWallet) = refreshLock.withLock {
		walletRegistry.persist(wallet)
	}

	suspend fun deleteWallet(wallet: GemWallet): Result<Unit> = refreshLock.withLock {
		deleteWalletLocked(wallet.walletId, wallet.keystoreId)
		val result = refreshSubscriptionsLocked()
		if (walletRegistry.load().isEmpty()) stop()
		result
	}

	suspend fun deleteWallet(walletId: String, keystoreId: String? = null): Result<Unit> = refreshLock.withLock {
		deleteWalletLocked(WalletId(walletId), keystoreId)
		val result = refreshSubscriptionsLocked()
		if (walletRegistry.load().isEmpty()) stop()
		result
	}

	suspend fun deleteAllWallets(): Result<Unit> = refreshLock.withLock {
		walletRegistry.load().forEach { wallet ->
			deleteWalletLocked(wallet.walletId, wallet.keystoreId)
		}
		val result = refreshSubscriptionsLocked()
		stop()
		result
	}

	private fun deleteWalletLocked(walletId: WalletId, keystoreId: String?) {
		val storedKeystoreId = walletRegistry.load(walletId)?.keystoreId ?: keystoreId
		if (storedKeystoreId != null) {
			keystoreDeleter.deleteKeystore(storedKeystoreId)
		}
		walletRegistry.delete(walletId)
	}

	suspend fun refreshSubscriptions(): Result<Unit> {
		val (startupId, startupGeneration, stateAtStart) = synchronized(startedMonitor) {
			Triple(activeStartupId, nextStartupId, _state.value)
		}
		val result = syncSubscriptions()
		updateStateAfterRefresh(startupId, startupGeneration, stateAtStart, result)
		return result
	}

	private suspend fun syncSubscriptions(): Result<Unit> = refreshLock.withLock {
		refreshSubscriptionsLocked()
	}

	private suspend fun refreshSubscriptionsLocked(): Result<Unit> {
		try {
			val accounts = walletRegistry.load().flatMap { it.accounts }
			val result = subscriptionRepository.sync(accounts)
			if (result.isSuccess) {
				Result.success(Unit)
			} else {
				Result.failure(result.exceptionOrNull() ?: IllegalStateException("Gem subscription sync failed"))
			}
		} catch (error: CancellationException) {
			throw error
		} catch (error: Throwable) {
			Result.failure(error)
		}
	}

	fun start(scope: CoroutineScope) {
		val startupId = synchronized(startedMonitor) {
			if (activeStartupId != null) {
				if (activeStartupOwner?.isCancelled != true) return
				cancelStartup(activeStartupId!!)
			}
			nextStartupId++
			activeStartupId = nextStartupId
			activeStartupOwner = scope.coroutineContext[Job]
			nextStartupId
		}
		scope.launch startup@{
			try {
				var lastError: Throwable? = null
				for (attempt in 0 until MAX_START_ATTEMPTS) {
					if (!setStateIfCurrent(startupId, GemRuntimeState.Starting)) return@startup
					try {
						val registration = deviceRegistration.ensureRegistered()
						if (registration.isFailure) {
							lastError = registration.exceptionOrNull()
						} else {
							if (!isCurrentStartup(startupId)) return@startup
							val subscriptions = syncSubscriptions()
							if (subscriptions.isSuccess) {
								if (!isCurrentStartup(startupId)) return@startup
								val websocketJob = launch(start = CoroutineStart.UNDISPATCHED) {
									try {
										webSocketClient.connect().collect { event ->
											try {
												processEvent(event)
											} catch (error: CancellationException) {
												throw error
											} catch (error: Throwable) {
												setRunningIfCurrent(startupId, error.toDiagnosticError())
											}
											_events.emit(event)
										}
									} catch (error: CancellationException) {
										throw error
									} catch (error: Throwable) {
										setRunningIfCurrent(startupId, error.toDiagnosticError())
									}
									}
								synchronized(startedMonitor) {
									if (activeStartupId == startupId) runtimeJob = websocketJob
								}
								websocketJob.invokeOnCompletion { cause ->
									if (cause is CancellationException) cancelStartup(startupId)
								}
								setRunningIfStarting(startupId)
								return@startup
							}
							lastError = subscriptions.exceptionOrNull()
						}
					} catch (error: CancellationException) {
						throw error
					} catch (error: Throwable) {
						lastError = error
					}
					if (attempt + 1 < MAX_START_ATTEMPTS) {
						delay(STARTUP_RETRY_DELAY_MS)
					}
				}
				failStartup(startupId, lastError.toDiagnosticError())
			} catch (error: CancellationException) {
				cancelStartup(startupId)
				throw error
			} finally {
				clearStartup(startupId)
			}
		}.also { job ->
			job.invokeOnCompletion { cause ->
				if (cause is CancellationException) {
					cancelStartup(startupId)
				} else {
					clearStartup(startupId)
				}
			}
		}
	}

	private fun isCurrentStartup(startupId: Long): Boolean = synchronized(startedMonitor) {
		activeStartupId == startupId
	}

	private fun setStateIfCurrent(startupId: Long, state: GemRuntimeState): Boolean = synchronized(startedMonitor) {
		if (activeStartupId != startupId) {
			false
		} else {
			_state.value = state
			true
		}
	}

	private fun setRunningIfCurrent(startupId: Long, error: GemError) {
		setStateIfCurrent(startupId, GemRuntimeState.Running(error))
	}

	private fun setRunningIfStarting(startupId: Long) = synchronized(startedMonitor) {
		if (activeStartupId == startupId && _state.value is GemRuntimeState.Starting) {
			_state.value = GemRuntimeState.Running()
		}
	}

	private fun failStartup(startupId: Long, error: GemError) {
		synchronized(startedMonitor) {
			if (activeStartupId == startupId) {
				activeStartupId = null
				activeStartupOwner = null
				_state.value = GemRuntimeState.Failed(error)
			}
		}
	}

	private fun cancelStartup(startupId: Long) {
		synchronized(startedMonitor) {
			if (activeStartupId == startupId) {
				activeStartupId = null
				activeStartupOwner = null
				if (_state.value is GemRuntimeState.Starting) {
					_state.value = GemRuntimeState.Idle
				}
			}
		}
	}

	private fun clearStartup(startupId: Long) {
		synchronized(startedMonitor) {
			if (activeStartupId == startupId) {
				activeStartupId = null
				activeStartupOwner = null
			}
		}
	}

	fun stop() {
		synchronized(startedMonitor) {
			runtimeJob?.cancel()
			runtimeJob = null
			activeStartupId = null
			activeStartupOwner = null
			nextStartupId++
			_state.value = GemRuntimeState.Idle
		}
	}

	private fun updateStateAfterRefresh(
		startupId: Long?,
		startupGeneration: Long,
		stateAtStart: GemRuntimeState,
		result: Result<Unit>,
	) = synchronized(startedMonitor) {
		if (
			startupGeneration != nextStartupId ||
			startupId == null ||
			startupId != activeStartupId ||
			_state.value is GemRuntimeState.Starting ||
			_state.value is GemRuntimeState.Failed ||
			stateAtStart is GemRuntimeState.Failed
		) {
			return@synchronized
		}
		_state.value = if (result.isSuccess) {
			GemRuntimeState.Running()
		} else {
			GemRuntimeState.Running(result.exceptionOrNull().toDiagnosticError())
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
						emitRefreshEvent(GemRefreshEvent.Balances(walletId, chains))
					}
				}
			is GemWebSocketEvent.Transactions -> {
				if (event.transactionIds.isNotEmpty()) {
					emitRefreshEvent(
						GemRefreshEvent.Transactions(WalletId(event.walletId), event.transactionIds.toSet()),
					)
				}
			}
		}
		refreshError?.let { throw it }
	}

	private suspend fun emitRefreshEvent(event: GemRefreshEvent) = refreshEventLock.withLock {
		_refreshEvents.emit(SequencedRefreshEvent(++refreshSequence, event))
	}

	private companion object {
		const val MAX_START_ATTEMPTS = 3
		const val STARTUP_RETRY_DELAY_MS = 1_000L
	}
}

fun interface GemAuthoritativeRefresh {
	suspend fun refresh(event: GemWebSocketEvent)
}

sealed interface GemRefreshEvent {
	data class Balances(val walletId: WalletId, val chains: Set<Chain>) : GemRefreshEvent
	data class Transactions(val walletId: WalletId, val transactionIds: Set<String>) : GemRefreshEvent
}

sealed interface GemRefreshDelivery {
	data class Initial(val events: List<GemRefreshEvent>) : GemRefreshDelivery
	data class Live(val event: GemRefreshEvent) : GemRefreshDelivery
}

private data class SequencedRefreshEvent(
	val sequence: Long,
	val event: GemRefreshEvent,
)

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
						transactions = bounded.entries.toList()
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
