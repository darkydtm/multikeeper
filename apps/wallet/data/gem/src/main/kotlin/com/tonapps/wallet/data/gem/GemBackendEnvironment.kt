package com.tonapps.wallet.data.gem

import okhttp3.HttpUrl.Companion.toHttpUrl

enum class GemBackendEnvironment(
	val baseUrl: String,
) {
	MAINNET("https://api.gemwallet.com"),
	TESTNET(""),
;
	fun resolvedBaseUrl(testnetUrl: String?): String {
		if (this == MAINNET) {
			return baseUrl
		}

		val url = testnetUrl?.trim().orEmpty()
		require(url.isNotEmpty()) { "Gem testnet URL is not configured" }
		val parsed = runCatching { url.toHttpUrl() }
			.getOrElse { throw IllegalArgumentException("Gem testnet URL is invalid", it) }
		require(parsed.scheme == "https") { "Gem testnet URL must use HTTPS" }
		require(parsed.host.isNotEmpty()) { "Gem testnet URL must have a host" }
		require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
			"Gem testnet URL must not contain credentials"
		}
		require(parsed.query == null && parsed.fragment == null) {
			"Gem testnet URL must not contain query or fragment"
		}
		return parsed.toString().removeSuffix("/")
	}

	companion object {
		fun forBuild(isDebug: Boolean, debugEnvironment: GemBackendEnvironment = TESTNET): GemBackendEnvironment =
			if (isDebug) debugEnvironment else MAINNET
	}
}

internal data class GemBackendConfig(
	val baseUrl: String,
)
