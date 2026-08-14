package com.tonapps.wallet.data.gem

import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class GemDeviceMetadata(
	val platform: String,
	val platformStore: String,
	val os: String,
	val model: String,
	val token: String,
	val locale: String,
	val version: String,
	val currency: String,
	val isPushEnabled: Boolean,
	val isPriceAlertsEnabled: Boolean? = null,
	val subscriptionsVersion: Int,
) {
	fun toDevice(id: String): GemDevice = GemDevice(
		id = id,
		platform = platform,
		platformStore = platformStore,
		os = os,
		model = model,
		token = token,
		locale = locale,
		version = version,
		currency = currency,
		isPushEnabled = isPushEnabled,
		isPriceAlertsEnabled = isPriceAlertsEnabled,
		subscriptionsVersion = subscriptionsVersion,
	)
}

interface GemDeviceIdentityProvider {
	fun getDeviceId(): String
}

interface GemDeviceBackend {
	suspend fun getDevice(): Result<GemDevice?>

	suspend fun registerDevice(device: GemDevice): Result<GemDevice?>

	suspend fun updateDevice(device: GemDevice): Result<GemDevice?>
}

class GemDeviceRegistrationCoordinator(
	private val identity: GemDeviceIdentityProvider,
	private val backend: GemDeviceBackend,
	private val metadata: GemDeviceMetadata,
) {
	private val mutex = Mutex()

	suspend fun ensureRegistered(): Result<GemDevice> = mutex.withLock {
		try {
			val desired = metadata.toDevice(identity.getDeviceId())
			val currentResult = backend.getDevice()
			if (currentResult.isFailure) {
				val error = currentResult.exceptionOrNull()!!
				if (error is CancellationException) throw error
				return@withLock Result.failure(error.toGemFailure())
			}
			val current = currentResult.getOrNull()
			when {
				current == null -> backend.registerDevice(desired).toDeviceResult(desired)
				current != desired -> backend.updateDevice(desired).toDeviceResult(desired)
				else -> Result.success(current)
			}
		} catch (error: CancellationException) {
			throw error
		} catch (error: Exception) {
			Result.failure(error.toGemFailure())
		}
	}

	private fun Result<GemDevice?>.toDeviceResult(fallback: GemDevice): Result<GemDevice> = fold(
		onSuccess = { Result.success(it ?: fallback) },
		onFailure = { Result.failure(it.toGemFailure()) },
	)

	private fun Throwable.toGemFailure(): WalletDataSourceException = WalletDataSourceException(
		when (this) {
			is WalletDataSourceException -> error
			is GemBackendException -> gemError.sanitized()
			is IOException -> GemError.NetworkUnavailable()
			else -> GemError.BackendRejected("unknown")
		},
	)

	private fun GemError.sanitized(): GemError = when (this) {
		GemError.AuthenticationRequired -> GemError.AuthenticationRequired
		GemError.DeviceRegistrationRequired -> GemError.DeviceRegistrationRequired
		is GemError.NetworkUnavailable -> GemError.NetworkUnavailable()
		is GemError.BackendRejected -> GemError.BackendRejected(code)
		else -> GemError.BackendRejected("unknown")
	}
}
