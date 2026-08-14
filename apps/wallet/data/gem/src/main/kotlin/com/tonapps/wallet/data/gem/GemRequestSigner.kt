package com.tonapps.wallet.data.gem

import com.tonapps.security.SecurityStorageBox
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import uniffi.gemstone.signDeviceAuth

fun interface GemRequestSigner {
	fun sign(method: String, encodedPath: String, exactBodyBytes: ByteArray, walletId: String): String
}

class GemDeviceAuthSigner(
	private val storage: SecurityStorageBox,
	private val deviceIdentity: GemDeviceIdentity,
	private val timestamp: () -> ULong = { System.currentTimeMillis().toULong() },
) : GemRequestSigner {
	override fun sign(method: String, encodedPath: String, exactBodyBytes: ByteArray, walletId: String): String {
		deviceIdentity.getDeviceId()
		val privateKey = storage.getByteArray(DEVICE_PRIVATE_KEY_STORAGE_KEY)
			?: error("Gem device private key is unavailable")
		return try {
			signDeviceAuth(privateKey, method, encodedPath, walletId, exactBodyBytes, timestamp())
		} finally {
			privateKey.fill(0)
		}
	}

	private companion object {
		const val DEVICE_PRIVATE_KEY_STORAGE_KEY = "gem_device_private_key"
	}
}

class GemRequestSignerInterceptor(
	private val signer: GemRequestSigner,
) : Interceptor {
	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		val body = request.body?.let {
			val buffer = Buffer()
			it.writeTo(buffer)
			buffer.readByteArray()
		} ?: ByteArray(0)
		val authorization = signer.sign(
			method = request.method,
			encodedPath = request.url.encodedPath,
			exactBodyBytes = body,
			walletId = request.tag(GemWalletId::class.java)?.value.orEmpty(),
		)
		return chain.proceed(request.newBuilder().header("Authorization", authorization).build())
	}
}

internal data class GemWalletId(val value: String)
