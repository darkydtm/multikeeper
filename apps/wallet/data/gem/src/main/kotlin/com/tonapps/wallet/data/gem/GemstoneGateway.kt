package com.tonapps.wallet.data.gem

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uniffi.gemstone.AlienException
import uniffi.gemstone.AlienProvider
import uniffi.gemstone.AlienResponse
import uniffi.gemstone.AlienTarget
import uniffi.gemstone.Chain as GemChain
import uniffi.gemstone.GemGateway
import uniffi.gemstone.GemPreferences
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val GEM_NODE_TOKEN_KEY = "gem_node_token"
private const val GEM_NODE_TOKEN_EXPIRY_KEY = "gem_node_token_expiry"

private const val GEM_NODE_BASE_URL = "https://gemnodes.com"

class GemstoneAlienProvider(
	private val client: OkHttpClient,
	private val tokenProvider: GemNodeTokenProvider? = null,
	private val baseUrl: String = GEM_NODE_BASE_URL,
) : AlienProvider {
	private val trustedBaseUrl = baseUrl.toHttpUrl()
	private val credentialedClient = client.newBuilder()
		.followSslRedirects(false)
		.build()

	override fun getEndpoint(chain: GemChain): String = "$baseUrl/${chain.trimStart('/')}"

	override suspend fun request(target: AlienTarget): AlienResponse = withContext(Dispatchers.IO) {
		val request = Request.Builder()
			.url(target.url)
			.method(target.method.name, target.body?.toRequestBody())
			.apply {
				target.headers.orEmpty().forEach { (name, value) -> addHeader(name, value) }
			}
			.build()
		try {
			val isTrustedGemNode = request.isTrustedGemNode(trustedBaseUrl)
			val token = if (isTrustedGemNode) {
				tokenProvider?.getToken()?.takeIf(String::isNotBlank)
			} else {
				null
			}
			val response = credentialedClient.newCall(request.withGemNodeToken(token, trustedBaseUrl)).execute()
			val retriedResponse = if (response.code == 401 && tokenProvider != null && token != null) {
				response.close()
				tokenProvider.invalidate(token)
				credentialedClient.newCall(request.withGemNodeToken(tokenProvider.getToken(), trustedBaseUrl)).execute()
			} else {
				response
			}
			retriedResponse.use { response ->
				AlienResponse(response.code.toUShort(), response.body?.bytes() ?: ByteArray(0))
			}
		} catch (error: CancellationException) {
			throw error
		} catch (error: IOException) {
			throw AlienException.RequestException(error.message.orEmpty())
		}
	}
}

internal fun Request.isTrustedGemNode(trustedBaseUrl: HttpUrl): Boolean =
	trustedBaseUrl.isHttps && url.isHttps && url.host == trustedBaseUrl.host && url.port == trustedBaseUrl.port

internal fun Request.withGemNodeToken(token: String?, trustedBaseUrl: HttpUrl): Request {
	if (!isTrustedGemNode(trustedBaseUrl)) {
		return newBuilder().removeHeader("Authorization").build()
	}
	return token?.let { newBuilder().header("Authorization", "Bearer $it").build() } ?: this
}

class GemNodeTokenProvider(
	private val backend: GemDeviceTokenBackend,
	private val storage: com.tonapps.security.SecurityStorageBox,
	private val nowSeconds: () -> ULong = { (System.currentTimeMillis() / 1_000).toULong() },
) {
	private val mutex = Mutex()

	suspend fun getToken(): String? = mutex.withLock {
		val token = storage.get(GEM_NODE_TOKEN_KEY)
		val expiry = storage.get(GEM_NODE_TOKEN_EXPIRY_KEY)?.toULongOrNull() ?: 0uL
		if (!token.isNullOrBlank() && expiry > nowSeconds() + 60uL) {
			return@withLock token
		}
		val fresh = backend.getDeviceToken().getOrElse { error ->
			throw AlienException.RequestException(error.message.orEmpty())
		}
		check(fresh.token.isNotBlank()) { "Gem node token is empty" }
		storage.transaction {
			putString(GEM_NODE_TOKEN_KEY, fresh.token)
			putString(GEM_NODE_TOKEN_EXPIRY_KEY, fresh.expiresAtSeconds.toString())
		}
		fresh.token
	}

	suspend fun invalidate(token: String) = mutex.withLock {
		if (storage.get(GEM_NODE_TOKEN_KEY) == token) {
			storage.transaction {
				remove(GEM_NODE_TOKEN_KEY)
				remove(GEM_NODE_TOKEN_EXPIRY_KEY)
			}
		}
	}
}

class GemstonePreferences(
	private val storage: com.tonapps.security.SecurityStorageBox,
) : GemPreferences {
	override fun get(key: String): String? = storage.get(key)

	override fun set(key: String, value: String) {
		storage.put(key, value)
	}

	override fun remove(key: String) {
		storage.transaction { remove(key) }
	}
}

interface GemBalanceReader {
	suspend fun getNativeBalance(chain: Chain, address: String): Result<String>
	suspend fun getTokenBalances(chain: Chain, address: String, tokenIds: List<String>): Result<Map<String, String>> =
		Result.success(emptyMap())
}

class GemstoneBalanceReader(
	private val gateway: GemGateway,
) : GemBalanceReader {
	override suspend fun getNativeBalance(chain: Chain, address: String): Result<String> = try {
		Result.success(gateway.getBalanceCoin(chain.key, address).balance.available)
	} catch (error: CancellationException) {
		throw error
	} catch (error: Throwable) {
		Result.failure(error)
	}

	override suspend fun getTokenBalances(
		chain: Chain,
		address: String,
		tokenIds: List<String>,
	): Result<Map<String, String>> = try {
		Result.success(
			gateway.getBalanceTokens(chain.key, address, tokenIds)
				.associate { it.assetId to it.balance.available },
		)
	} catch (error: CancellationException) {
		throw error
	} catch (error: Throwable) {
		Result.failure(error)
	}
}
