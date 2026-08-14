package com.tonapps.wallet.data.gem

enum class GemBackendEnvironment(
	val baseUrl: String,
) {
	MAINNET("https://api.gemwallet.com"),
	TESTNET("https://api-testnet.gemwallet.com"),
;
	companion object {
		fun forBuild(isDebug: Boolean, debugEnvironment: GemBackendEnvironment = TESTNET): GemBackendEnvironment =
			if (isDebug) debugEnvironment else MAINNET
	}
}
