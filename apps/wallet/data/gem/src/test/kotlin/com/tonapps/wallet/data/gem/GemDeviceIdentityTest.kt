package com.tonapps.wallet.data.gem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GemDeviceIdentityTest {
	@Test
	fun `generates and stores a private key when absent`() {
		val privateKey = ByteArray(32) { it.toByte() }
		val publicKey = ByteArray(32) { 0xAB.toByte() }
		val storage = InMemoryDeviceKeyStorage()
		val crypto = FakeDeviceCrypto(privateKey, publicKey)

		val deviceId = GemDeviceIdentity(storage, crypto).getDeviceId()

		assertEquals("ab".repeat(32), deviceId)
		assertEquals(1, crypto.generateCalls)
		assertEquals(1, crypto.derivedPrivateKeys.size)
		assertEquals(privateKey.toList(), crypto.derivedPrivateKeys.single().toList())
		assertEquals(privateKey.toList(), storage.privateKey?.toList())
	}

	@Test
	fun `uses the stored private key without generating another one`() {
		val privateKey = ByteArray(32) { (it + 1).toByte() }
		val publicKey = ByteArray(32) { 0xCD.toByte() }
		val storage = InMemoryDeviceKeyStorage(privateKey)
		val crypto = FakeDeviceCrypto(ByteArray(32), publicKey)

		val deviceId = GemDeviceIdentity(storage, crypto).getDeviceId()

		assertEquals("cd".repeat(32), deviceId)
		assertEquals(0, crypto.generateCalls)
		assertEquals(privateKey.toList(), crypto.derivedPrivateKeys.single().toList())
	}

	@Test
	fun `rejects a stored private key with the wrong size`() {
		val storage = InMemoryDeviceKeyStorage(ByteArray(31))

		assertThrows(IllegalStateException::class.java) {
			GemDeviceIdentity(storage, FakeDeviceCrypto(ByteArray(32), ByteArray(32))).getDeviceId()
		}
	}

	private class InMemoryDeviceKeyStorage(
		private var value: ByteArray? = null,
	) : GemDeviceKeyStorage {
		val privateKey: ByteArray?
			get() = value?.copyOf()

		override fun readPrivateKey(): ByteArray? = value?.copyOf()

		override fun writePrivateKey(privateKey: ByteArray) {
			value = privateKey.copyOf()
		}
	}

	private class FakeDeviceCrypto(
		private val generatedPrivateKey: ByteArray,
		private val publicKey: ByteArray,
	) : GemDeviceCrypto {
		var generateCalls = 0
		val derivedPrivateKeys = mutableListOf<ByteArray>()

		override fun generatePrivateKey(): ByteArray {
			generateCalls += 1
			return generatedPrivateKey.copyOf()
		}

		override fun devicePublicKey(privateKey: ByteArray): ByteArray {
			derivedPrivateKeys += privateKey.copyOf()
			return publicKey.copyOf()
		}
	}
}
