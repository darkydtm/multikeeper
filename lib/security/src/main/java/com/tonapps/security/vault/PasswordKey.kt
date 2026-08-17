package com.tonapps.security.vault

import android.annotation.SuppressLint
import com.tonapps.extensions.putByteArray
import com.tonapps.security.KeyHelperException
import com.tonapps.security.Security
import com.tonapps.security.SecurityStorageBox
import com.tonapps.security.clear
import com.tonapps.security.safeDestroy
import com.tonapps.security.spec.SimpleSecretSpec
import java.security.MessageDigest
import javax.crypto.SecretKey

internal class PasswordKey(
    private val prefs: SecurityStorageBox,
) {

    internal fun isEmpty(): Boolean {
        return !prefs.contains(SALT_KEY) || !prefs.contains(VERIFICATION_KEY)
    }

    /**
     * Quickly check valid password without need to decrypt master key.
     * Only for UI and other non-sensitive operations.
     */
    internal fun isValid(password: CharArray): Boolean {
        var currentVerification: ByteArray? = null
        var secret: SecretKey? = null
        var verification: ByteArray? = null

        return try {
            try {
                currentVerification = prefs.getByteArray(VERIFICATION_KEY) ?: throw IllegalStateException("verification is null")
                secret = create(password) ?: throw IllegalStateException("failed to create secret key")
                verification = calcVerification(secret.encoded)
                MessageDigest.isEqual(currentVerification, verification)
            } catch (e: Throwable) {
                false
            }
        } finally {
            secret?.safeDestroy()
            clear(currentVerification, verification)
        }
    }

    internal fun create(password: CharArray): SecretKey? {
        var salt: ByteArray? = null
        return try {
            salt = prefs.getByteArray(SALT_KEY)
            if (salt == null) {
                null
            } else {
                generateSecretKey(password, salt!!)
            }
        } finally {
            password.clear()
            salt?.clear()
        }
    }

    internal fun set(password: CharArray): SecretKey? {
        var salt: ByteArray? = null
        var secret: SecretKey? = null
        var keepSecret = false

        return try {
            salt = generateSalt()
            secret = generateSecretKey(password, salt!!)
            if (secret == null) {
                null
            } else {
                val verification = calcVerification(secret!!.encoded)
                setSaltAndVerification(salt!!, verification)
                keepSecret = true
                secret
            }
        } finally {
            password.clear()
            salt?.clear()
            if (!keepSecret) {
                secret?.safeDestroy()
            }
        }
    }

    @SuppressLint("ApplySharedPref")
    internal fun setSaltAndVerification(salt: ByteArray, verification: ByteArray) {
        try {
            val saved = prefs.transaction {
                putByteArray(SALT_KEY, salt)
                putByteArray(VERIFICATION_KEY, verification)
            }

            if (!saved) {
                throw KeyHelperException.Save("failed to save salt and verification")
            }
        } finally {
            clear(salt, verification)
        }
    }

    companion object {
        private const val SALT_KEY = "password_salt"
        private const val SALT_SIZE = 32

        private const val VERIFICATION_KEY = "password_verification"
        private const val VERIFICATION_SIZE = 4

        fun generateSalt() = Security.randomBytes(SALT_SIZE)

        fun generateSecretKey(password: CharArray, salt: ByteArray): SecretKey? {
            var hash: ByteArray? = null
            return try {
                hash = Security.argon2Hash(password, salt)
                hash?.let(::SimpleSecretSpec)
            } finally {
                password.clear()
                hash?.clear()
            }
        }

        fun calcVerification(input: ByteArray): ByteArray {
            return try {
                Security.calcVerification(input, VERIFICATION_SIZE)
            } finally {
                input.clear()
            }
        }
    }
}
