package com.tonapps.security.vault

import com.tonapps.security.SecurityStorageBox
import com.tonapps.security.clear
import com.tonapps.security.safeDestroy
import com.tonapps.security.tryCallGC
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import javax.crypto.SecretKey

open class Vault(
    private val prefs: SecurityStorageBox
) {

    private val coroutineContext = Dispatchers.IO + SupervisorJob()
    private val passwordKey = PasswordKey(prefs)
    private val masterKey = MasterKey(prefs)
    private val storage = Storage(prefs)

    suspend fun deleteAll() = withContext(coroutineContext) {
        if (!prefs.clear()) {
            throw IllegalStateException("failed to clear")
        }
    }

    suspend fun hasPassword(): Boolean = withContext(coroutineContext) {
        !passwordKey.isEmpty()
    }

    suspend fun isValidPassword(password: CharArray): Boolean = withContext(coroutineContext) {
        try {
            val valid = passwordKey.isValid(password)
            tryCallGC()
            valid
        } finally {
            password.clear()
        }
    }

    suspend fun get(secret: SecretKey, id: Long): ByteArray = withContext(coroutineContext) {
        storage.get(id, secret)
    }

    suspend fun put(secret: SecretKey, id: Long, data: ByteArray) = withContext(coroutineContext) {
        storage.put(secret, id, data)
    }

    suspend fun delete(secret: SecretKey, id: Long) = withContext(coroutineContext) {
        storage.put(secret, id, ByteArray(0))
    }

    suspend fun getMasterSecret(password: CharArray): SecretKey = withContext(coroutineContext) {
        val passwordSecret = passwordKey.create(password) ?: throw IllegalStateException("Password secret is null")
        try {
            val masterSecret = masterKey.getSecret(passwordSecret)
            tryCallGC()
            masterSecret ?: throw IllegalStateException("Master secret is null")
        } finally {
            passwordSecret.safeDestroy()
            password.clear()
        }
    }

    suspend fun createMasterSecret(password: CharArray): SecretKey = withContext(coroutineContext) {
        val passwordSecret = passwordKey.set(password) ?: throw IllegalStateException("Password secret is null")
        try {
            val masterSecret = masterKey.newSecret(passwordSecret)
            tryCallGC()
            masterSecret ?: throw IllegalStateException("Master secret is null")
        } finally {
            passwordSecret.safeDestroy()
            password.clear()
        }
    }

    suspend fun changePassword(
        newPassword: CharArray,
        oldPassword: CharArray
    ): Boolean = withContext(coroutineContext) {
        var oldPasswordSecret: SecretKey? = null
        var newPasswordSalt: ByteArray? = null
        var newPasswordSecret: SecretKey? = null
        var newPasswordVerification: ByteArray? = null

        try {
            oldPasswordSecret = passwordKey.create(oldPassword)
            if (oldPasswordSecret == null) {
                return@withContext false
            }

            newPasswordSalt = PasswordKey.generateSalt()
            newPasswordSecret = PasswordKey.generateSecretKey(newPassword, newPasswordSalt!!)
            if (newPasswordSecret == null) {
                return@withContext false
            }

            newPasswordVerification = PasswordKey.calcVerification(newPasswordSecret!!.encoded)
            val reEncrypted = masterKey.reEncryptSecret(oldPasswordSecret!!, newPasswordSecret!!)
            if (reEncrypted) {
                passwordKey.setSaltAndVerification(newPasswordSalt!!, newPasswordVerification!!)
            }

            tryCallGC()
            reEncrypted
        } finally {
            oldPasswordSecret?.safeDestroy()
            newPasswordSecret?.safeDestroy()
            clear(newPasswordSalt, newPasswordVerification)
            oldPassword.clear()
            newPassword.clear()
        }
    }
}
