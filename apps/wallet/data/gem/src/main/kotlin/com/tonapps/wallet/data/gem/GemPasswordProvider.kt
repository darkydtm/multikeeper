package com.tonapps.wallet.data.gem

import com.tonapps.extensions.putByteArray
import com.tonapps.security.Security
import com.tonapps.security.SecurityStorageBox

internal interface GemPasswordStorage {
	fun read(): ByteArray?

	fun write(password: ByteArray): Boolean
}

private class SecurityGemPasswordStorage(
	private val storage: SecurityStorageBox,
) : GemPasswordStorage {
	override fun read(): ByteArray? = storage.getByteArray(GEM_KEYSTORE_PASSWORD_STORAGE_KEY)

	override fun write(password: ByteArray): Boolean = storage.transaction {
		putByteArray(GEM_KEYSTORE_PASSWORD_STORAGE_KEY, password)
	}
}

class GemKeystorePasswordProvider internal constructor(
	private val storage: GemPasswordStorage,
	private val generatePassword: () -> ByteArray,
) : GemPasswordProvider {
	internal constructor(storage: GemPasswordStorage) : this(
		storage = storage,
		generatePassword = { Security.randomBytes(GEM_KEYSTORE_PASSWORD_SIZE) },
	)

	@Synchronized
	override fun passwordBytes(): ByteArray {
		storage.read()?.let { password ->
			return try {
				validate(password)
				password
			} catch (error: Throwable) {
				password.fill(0)
				throw error
			}
		}

		val generated = generatePassword()
		return try {
			validate(generated)
			check(storage.write(generated)) { "Failed to save Gem keystore password" }
			generated.copyOf()
		} finally {
			generated.fill(0)
		}
	}

	private fun validate(password: ByteArray) {
		check(password.size == GEM_KEYSTORE_PASSWORD_SIZE) { "Invalid Gem keystore password size" }
	}
}

internal class GemKeystorePasswordStorage(
	storage: SecurityStorageBox,
) : GemPasswordStorage by SecurityGemPasswordStorage(storage)

internal const val GEM_KEYSTORE_PASSWORD_SIZE = 32
private const val GEM_KEYSTORE_PASSWORD_STORAGE_KEY = "gem_keystore_password"
