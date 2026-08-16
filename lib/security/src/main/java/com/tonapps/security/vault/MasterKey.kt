package com.tonapps.security.vault

import android.annotation.SuppressLint
import com.tonapps.extensions.putByteArray
import com.tonapps.security.KeyHelperException
import com.tonapps.security.Security
import com.tonapps.security.SecurityStorageBox
import com.tonapps.security.clear
import com.tonapps.security.decrypt
import com.tonapps.security.encrypt
import com.tonapps.security.safeDestroy
import com.tonapps.security.spec.SimpleSecretSpec
import javax.crypto.SecretKey

internal class MasterKey(
    private val prefs: SecurityStorageBox
) {

    internal fun getSecret(passwordSecret: SecretKey): SecretKey? {
        var iv: ByteArray? = null
        var encrypted: ByteArray? = null
        var key: ByteArray? = null

        return try {
            iv = prefs.getByteArray(IV_KEY) ?: throw Exception("No iv")
            encrypted = prefs.getByteArray(BODY_KEY) ?: throw Exception("No body")
            key = passwordSecret.decrypt(iv!!, encrypted!!)
                ?: throw Exception("Failed to decrypt")
            SimpleSecretSpec(key!!)
        } catch (e: Throwable) {
            null
        } finally {
            passwordSecret.safeDestroy()
            clear(iv, encrypted, key)
        }
    }

    internal fun newSecret(passwordSecret: SecretKey): SecretKey? {
        var secretKey: SecretKey? = null
        var secretEncoded: ByteArray? = null
        var iv: ByteArray? = null
        var encrypted: ByteArray? = null
        var keepSecret = false

        return try {
            secretKey = Security.generatePrivateKey(KEY_SIZE)
            secretEncoded = secretKey!!.encoded
            iv = Security.randomBytes(IV_SIZE)
            encrypted = passwordSecret.encrypt(iv!!, secretEncoded!!)
            if (encrypted == null) {
                null
            } else {
                put(iv!!, encrypted!!)
                keepSecret = true
                secretKey
            }
        } finally {
            passwordSecret.safeDestroy()
            clear(secretEncoded, iv, encrypted)
            if (!keepSecret) {
                secretKey?.safeDestroy()
            }
        }
    }

    internal fun reEncryptSecret(oldPasswordSecret: SecretKey, newPasswordSecret: SecretKey): Boolean {
        var currentSecret: SecretKey? = null
        var currentSecretEncoded: ByteArray? = null
        var newIv: ByteArray? = null
        var encrypted: ByteArray? = null

        return try {
            currentSecret = getSecret(oldPasswordSecret)
            if (currentSecret == null) {
                false
            } else {
                currentSecretEncoded = currentSecret!!.encoded
                newIv = Security.randomBytes(IV_SIZE)
                encrypted = newPasswordSecret.encrypt(newIv!!, currentSecretEncoded!!)
                if (encrypted == null) {
                    false
                } else {
                    put(newIv!!, encrypted!!)
                    true
                }
            }
        } finally {
            currentSecret?.safeDestroy()
            newPasswordSecret.safeDestroy()
            clear(currentSecretEncoded, newIv, encrypted)
        }
    }

    @SuppressLint("ApplySharedPref")
    private fun put(iv: ByteArray, encrypted: ByteArray) {
        try {
            val saved = prefs.transaction {
                putByteArray(IV_KEY, iv)
                putByteArray(BODY_KEY, encrypted)
            }
            if (!saved) {
                throw KeyHelperException.Save("Failed to save master IV and Body")
            }
        } finally {
            clear(iv, encrypted)
        }
    }

    private companion object {
        private const val KEY_SIZE = 32
        private const val IV_SIZE = 16

        private const val BODY_KEY = "master_body"
        private const val IV_KEY = "master_iv"
    }
}
