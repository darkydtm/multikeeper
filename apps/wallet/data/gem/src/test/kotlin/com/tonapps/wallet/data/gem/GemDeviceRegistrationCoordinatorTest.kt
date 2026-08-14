package com.tonapps.wallet.data.gem

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GemDeviceRegistrationCoordinatorTest {
	@Test
	fun `creates a device after obtaining identity and stays idempotent`() = runBlocking {
		val events = mutableListOf<String>()
		val metadata = testMetadata()
		val expected = metadata.toDevice("device-id")
		val backend = FakeDeviceBackend(events = events)
		val coordinator = GemDeviceRegistrationCoordinator(
			identity = FakeDeviceIdentity(events, "device-id"),
			backend = backend,
			metadata = metadata,
		)

		assertEquals(expected, coordinator.ensureRegistered().getOrThrow())
		assertEquals(expected, coordinator.ensureRegistered().getOrThrow())
		assertEquals(listOf("identity", "get", "register", "identity", "get"), events)
		assertEquals(1, backend.registered.size)
		assertTrue(backend.updated.isEmpty())
	}

	@Test
	fun `updates a device when identity or metadata is stale`() = runBlocking {
		val metadata = testMetadata()
		val backend = FakeDeviceBackend(
			current = metadata.toDevice("old-device").copy(model = "old-model"),
		)
		val coordinator = GemDeviceRegistrationCoordinator(
			identity = FakeDeviceIdentity(deviceId = "device-id"),
			backend = backend,
			metadata = metadata,
		)

		assertEquals(metadata.toDevice("device-id"), coordinator.ensureRegistered().getOrThrow())
		assertTrue(backend.registered.isEmpty())
		assertEquals(listOf(metadata.toDevice("device-id")), backend.updated)
	}

	@Test
	fun `maps authentication failure to GemError`() = runBlocking {
		val result = coordinatorWith(
			getResult = Result.failure(GemBackendException(401)),
		).ensureRegistered()

		assertEquals(GemError.AuthenticationRequired, result.gemError())
	}

	@Test
	fun `maps registration required failure to GemError`() = runBlocking {
		val result = coordinatorWith(
			getResult = Result.failure(
				GemBackendException(404),
			),
		).ensureRegistered()

		assertEquals(GemError.DeviceRegistrationRequired, result.gemError())
	}

	@Test
	fun `maps registration failure to GemError`() = runBlocking {
		val result = GemDeviceRegistrationCoordinator(
			identity = FakeDeviceIdentity(deviceId = "device-id"),
			backend = FakeDeviceBackend(
				registerResult = Result.failure(GemBackendException(401)),
			),
			metadata = testMetadata(),
		).ensureRegistered()

		assertEquals(GemError.AuthenticationRequired, result.gemError())
	}

	@Test
	fun `maps network failure to GemError`() = runBlocking {
		val result = coordinatorWith(
			getResult = Result.failure(IOException("offline")),
		).ensureRegistered()

		assertTrue(result.gemError() is GemError.NetworkUnavailable)
	}

	private fun coordinatorWith(
		getResult: Result<GemDevice?>,
	): GemDeviceRegistrationCoordinator = GemDeviceRegistrationCoordinator(
		identity = FakeDeviceIdentity(deviceId = "device-id"),
		backend = FakeDeviceBackend(getResult = getResult),
		metadata = testMetadata(),
	)

	private fun testMetadata() = GemDeviceMetadata(
		platform = "android",
		platformStore = "googlePlay",
		os = "android 15",
		model = "Pixel",
		token = "",
		locale = "en",
		version = "1.0",
		currency = "USD",
		isPushEnabled = false,
		isPriceAlertsEnabled = false,
		subscriptionsVersion = 1,
	)

	private fun <T> Result<T>.gemError(): GemError = (exceptionOrNull() as WalletDataSourceException).error
}

private class FakeDeviceIdentity(
	private val events: MutableList<String> = mutableListOf(),
	private val deviceId: String,
) : GemDeviceIdentityProvider {
	init {
		check(deviceId.isNotBlank())
	}

	override fun getDeviceId(): String {
		events += "identity"
		return deviceId
	}
}

private class FakeDeviceBackend(
	private var current: GemDevice? = null,
	private val getResult: Result<GemDevice?>? = null,
	private val registerResult: Result<GemDevice?>? = null,
	private val events: MutableList<String> = mutableListOf(),
) : GemDeviceBackend {
	val registered = mutableListOf<GemDevice>()
	val updated = mutableListOf<GemDevice>()

	override suspend fun getDevice(): Result<GemDevice?> {
		events += "get"
		return getResult ?: Result.success(current)
	}

	override suspend fun registerDevice(device: GemDevice): Result<GemDevice?> {
		events += "register"
		registered += device
		current = device
		return registerResult ?: Result.success(null)
	}

	override suspend fun updateDevice(device: GemDevice): Result<GemDevice?> {
		events += "update"
		updated += device
		current = device
		return Result.success(device)
	}
}
