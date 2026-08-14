package com.tonapps.wallet.data.gem

import com.tonapps.extensions.putByteArray
import com.tonapps.security.SecurityStorageBox
import com.tonapps.security.clear
import uniffi.gemstone.devicePublicKey as gemstoneDevicePublicKey
import uniffi.gemstone.generateDeviceKeyPair as gemstoneGenerateDeviceKeyPair

private const val PRIVATE_KEY_STORAGE_KEY = "gem_device_private_key"

interface GemDeviceCrypto {
	fun generatePrivateKey(): ByteArray

	fun devicePublicKey(privateKey: ByteArray): ByteArray
}

private object UniffiGemDeviceCrypto : GemDeviceCrypto {
	override fun generatePrivateKey(): ByteArray = gemstoneGenerateDeviceKeyPair().privateKey

	override fun devicePublicKey(privateKey: ByteArray): ByteArray = gemstoneDevicePublicKey(privateKey)
}

internal interface GemDeviceKeyStorage {
	fun readPrivateKey(): ByteArray?

	fun writePrivateKey(privateKey: ByteArray)
}

private class SecurityGemDeviceKeyStorage(
	private val storage: SecurityStorageBox,
) : GemDeviceKeyStorage {
	override fun readPrivateKey(): ByteArray? = storage.getByteArray(PRIVATE_KEY_STORAGE_KEY)

	override fun writePrivateKey(privateKey: ByteArray) {
		if (!storage.transaction { putByteArray(PRIVATE_KEY_STORAGE_KEY, privateKey) }) {
			throw IllegalStateException("Failed to save device private key")
		}
	}
}

class GemDeviceIdentity internal constructor(
	private val storage: GemDeviceKeyStorage,
	private val crypto: GemDeviceCrypto,
) : GemDeviceIdentityProvider {
	constructor(
		storage: SecurityStorageBox,
		crypto: GemDeviceCrypto = UniffiGemDeviceCrypto,
	) : this(SecurityGemDeviceKeyStorage(storage), crypto)

	@Synchronized
	override fun getDeviceId(): String {
		val privateKey = getOrCreatePrivateKey()
		return try {
			val publicKey = crypto.devicePublicKey(privateKey)
			try {
				validatePublicKey(publicKey)
				publicKey.toLowerHex()
			} finally {
				publicKey.clear()
			}
		} finally {
			privateKey.clear()
		}
	}

	private fun getOrCreatePrivateKey(): ByteArray {
		storage.readPrivateKey()?.let { storedKey ->
			return try {
				validatePrivateKey(storedKey)
				storedKey
			} catch (error: Throwable) {
				storedKey.clear()
				throw error
			}
		}

		val generatedKey = crypto.generatePrivateKey()
		return try {
			validatePrivateKey(generatedKey)
			storage.writePrivateKey(generatedKey)
			generatedKey.copyOf()
		} finally {
			generatedKey.clear()
		}
	}

	private fun validatePrivateKey(privateKey: ByteArray) {
		check(privateKey.size == DEVICE_KEY_SIZE) { "Invalid device private key size" }
	}

	private fun validatePublicKey(publicKey: ByteArray) {
		check(publicKey.size == DEVICE_KEY_SIZE) { "Invalid device public key size" }
	}

	private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
		for (byte in this@toLowerHex) {
			append(HEX_DIGITS[byte.toInt() ushr 4 and 0x0F])
			append(HEX_DIGITS[byte.toInt() and 0x0F])
		}
	}

	private companion object {
		const val DEVICE_KEY_SIZE = 32
		const val HEX_DIGITS = "0123456789abcdef"
	}
}
