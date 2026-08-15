package com.tonapps.wallet.data.gem

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resolves rich token attributes (name, symbol, decimals, icon) for a manually
 * imported token using the public CoinGecko API.
 *
 * The lookup is best-effort: when a resolution is not possible or the network
 * call fails, `null` is returned so the caller can fall back to human input.
 */
class CoinGeckoAttributesResolver(
	private val client: OkHttpClient = OkHttpClient.Builder().build(),
	private val endpoint: String = "api.coingecko.com",
) {
	private val cache = ConcurrentHashMap<String, ResolvedAttributes>()

	/** Resolves token attributes for [chain] and [contractAddress], caching the result. */
	open suspend fun resolve(chain: Chain, contractAddress: String): ResolvedAttributes? {
		val key = "${chain.key}:${contractAddress.lowercase()}"
		cache[key]?.let { return it }
		val resolved = fetch(chain, contractAddress) ?: return null
		cache[key] = resolved
		return resolved
	}

	private suspend fun fetch(chain: Chain, contractAddress: String): ResolvedAttributes? {
		val platformId = chain.toCoinGeckoPlatformId() ?: return null
		val url = HttpUrl.Builder()
			.scheme("https")
			.host(endpoint)
			.addPathSegments("api/v3/coins/$platformId/contract/$contractAddress")
			.addQueryParameter("localization", "false")
			.addQueryParameter("tickers", "false")
			.addQueryParameter("market_data", "false")
			.addQueryParameter("community_data", "false")
			.addQueryParameter("developer_data", "false")
			.addQueryParameter("sparkline", "false")
			.build()
		val request = Request.Builder().url(url).build()
		val body = withContext(Dispatchers.IO) {
			runCatching { client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null } }
				.getOrNull()
		} ?: return null
		return parse(body)
	}

	internal fun parse(response: String): ResolvedAttributes? = runCatching {
		val dto = json.decodeFromString<CoinGeckoTokenDto>(response)
		val decimals = dto.detailPlatforms
			?.values
			?.firstNotNullOfOrNull { it.decimalPlace }
			?: dto.decimalPlaces
		ResolvedAttributes(
			name = dto.name?.trim()?.takeIf { it.isNotBlank() },
			symbol = dto.symbol?.trim()?.uppercase()?.takeIf { it.isNotBlank() },
			decimals = decimals,
			imageUrl = dto.image?.let { image ->
				image.large ?: image.small ?: image.thumb
			}?.takeIf { it.isNotBlank() },
		)
	}.getOrNull()

	companion object {
		private val json = Json { ignoreUnknownKeys = true }
	}
}

data class ResolvedAttributes(
	val name: String?,
	val symbol: String?,
	val decimals: Int?,
	val imageUrl: String? = null,
)

@Serializable
private data class CoinGeckoTokenDto(
	val name: String? = null,
	val symbol: String? = null,
	val image: CoinGeckoImageDto? = null,
	val detailPlatforms: Map<String, CoinGeckoPlatformDto>? = null,
	val decimalPlaces: Int? = null,
)

@Serializable
private data class CoinGeckoImageDto(
	val thumb: String? = null,
	val small: String? = null,
	val large: String? = null,
)

@Serializable
private data class CoinGeckoPlatformDto(
	val decimalPlace: Int? = null,
)

private fun Chain.toCoinGeckoPlatformId(): String? = when (this) {
	Chain.Ethereum -> "ethereum"
	Chain.SmartChain -> "binance-smart-chain"
	Chain.Solana -> "solana"
	else -> null
}