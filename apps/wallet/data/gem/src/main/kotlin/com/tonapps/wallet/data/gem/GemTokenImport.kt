package com.tonapps.wallet.data.gem

data class GemTokenImport(
	val walletId: WalletId,
	val chain: Chain,
	val assetId: String,
	val name: String,
	val symbol: String,
	val decimals: String,
)

/**
 * Builds a [GemToken] from human import fields, auto-filling missing (blank) or
 * invalid name, symbol and decimals from the [CoinGeckoAttributesResolver].
 *
 * A token ID that cannot be resolved (or is blank/invalid/native) is rejected.
 */
suspend fun GemTokenImport.toGemToken(
	resolver: CoinGeckoAttributesResolver = CoinGeckoAttributesResolver(),
): Result<GemToken> {
	val normalizedAssetId = assetId.trim()
	if (chain == Chain.Bitcoin || chain == Chain.Ton) {
		return Result.failure(WalletDataSourceException(GemError.InvalidInput("Token imports are not supported for this chain")))
	}
	if (normalizedAssetId.isBlank() || normalizedAssetId == "native") {
		return Result.failure(WalletDataSourceException(GemError.InvalidInput("Token ID must not be native or blank")))
	}
	if (!normalizedAssetId.matches(tokenIdPattern(chain))) {
		return Result.failure(WalletDataSourceException(GemError.InvalidInput("Token ID does not match the selected chain")))
	}

	val resolved = resolver.resolve(chain, normalizedAssetId)
	val normalizedName = firstNonBlank(name, resolved?.name)
	val normalizedSymbol = firstNonBlank(symbol, resolved?.symbol)?.uppercase()
	val normalizedDecimals = decimals.trim().toIntOrNull() ?: resolved?.decimals

	if (normalizedName.isNullOrBlank() || normalizedSymbol.isNullOrBlank()) {
		return Result.failure(WalletDataSourceException(GemError.InvalidInput("Token name and symbol are required")))
	}
	if (normalizedDecimals == null || normalizedDecimals !in 0..255) {
		return Result.failure(WalletDataSourceException(GemError.InvalidInput("Token decimals must be between 0 and 255")))
	}
	return Result.success(
		GemToken(
			walletId = walletId,
			chain = chain,
			assetId = normalizedAssetId,
			metadata = AssetMetadata.Known(normalizedSymbol, normalizedName, normalizedDecimals, resolved?.imageUrl),
		),
	)
}

private fun firstNonBlank(vararg candidates: String?): String? =
	candidates.firstOrNull { !it.isNullOrBlank() }?.trim()

private fun tokenIdPattern(chain: Chain): Regex = when (chain) {
	Chain.Ethereum, Chain.SmartChain -> Regex("0x[a-fA-F0-9]{40}")
	Chain.Solana -> Regex("[1-9A-HJ-NP-Za-km-z]{32,44}")
	else -> Regex("a^", RegexOption.IGNORE_CASE)
}
