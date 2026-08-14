package com.tonapps.wallet.data.gem

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GemPasswordProviderTest {
	@Test
	fun `generates and persists a 32 byte password`() {
		val storage = InMemoryGemPasswordStorage()
		val expected = ByteArray(GEM_KEYSTORE_PASSWORD_SIZE) { it.toByte() }

		val first = GemKeystorePasswordProvider(storage, { expected.copyOf() }).passwordBytes()
		first.fill(0)

		assertArrayEquals(expected, requireNotNull(storage.value))
		assertEquals(GEM_KEYSTORE_PASSWORD_SIZE, first.size)
	}

	@Test
	fun `returns the persisted password without generating another one`() {
		val storage = InMemoryGemPasswordStorage(
			ByteArray(GEM_KEYSTORE_PASSWORD_SIZE) { (it + 1).toByte() },
		)
		val provider = GemKeystorePasswordProvider(storage, {
			throw AssertionError("password must be read from storage")
		})

		val password = provider.passwordBytes()

		assertArrayEquals(requireNotNull(storage.value), password)
		password.fill(0)
		assertArrayEquals(
			ByteArray(GEM_KEYSTORE_PASSWORD_SIZE) { (it + 1).toByte() },
			requireNotNull(storage.value),
		)
	}

	@Test
	fun `rejects passwords with an invalid length`() {
		val storage = InMemoryGemPasswordStorage()

		assertThrows(IllegalStateException::class.java) {
			GemKeystorePasswordProvider(storage, { ByteArray(GEM_KEYSTORE_PASSWORD_SIZE - 1) }).passwordBytes()
		}
	}
}

private class InMemoryGemPasswordStorage(
	var value: ByteArray? = null,
) : GemPasswordStorage {
	override fun read(): ByteArray? = value?.copyOf()

	override fun write(password: ByteArray): Boolean {
		value = password.copyOf()
		return true
	}
}
