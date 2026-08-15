package com.tonapps.wallet.data.gem

import com.tonapps.security.SecurityStorageBox
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val GEM_TOKEN_STORAGE_KEY = "gem_wallet_tokens"
private const val GEM_TOKEN_SCHEMA_VERSION = 2

data class GemToken(
	val walletId: WalletId,
	val chain: Chain,
	val assetId: String,
	val metadata: AssetMetadata.Known,
)

class GemTokenRepository internal constructor(
	private val storage: GemTokenStorage,
) {
	constructor(storage: SecurityStorageBox) : this(SecurityGemTokenStorage(storage))

	@Synchronized
	fun get(walletId: WalletId, chain: Chain): List<GemToken> = read().tokens
		.filter { it.walletId == walletId.value && it.chain == chain.key }
		.map { it.toDomain() }

	@Synchronized
	fun upsert(token: GemToken) {
		validate(token)
		val tokens = read().tokens.toMutableList()
		val dto = token.toDto()
		val index = tokens.indexOfFirst {
			it.walletId == dto.walletId && it.chain == dto.chain && it.assetId == dto.assetId
		}
		if (index == -1) {
			tokens += dto
		} else {
			tokens[index] = dto
		}
		write(TokenRegistryDto(GEM_TOKEN_SCHEMA_VERSION, tokens))
	}

	@Synchronized
	fun delete(walletId: WalletId, chain: Chain, assetId: String) {
		val registry = read()
		val tokens = registry.tokens.filterNot {
			it.walletId == walletId.value && it.chain == chain.key && it.assetId == assetId
		}
		if (tokens.size != registry.tokens.size) {
			write(TokenRegistryDto(GEM_TOKEN_SCHEMA_VERSION, tokens))
		}
	}

	private fun read(): TokenRegistryDto = storage.read()?.let {
		json.decodeFromString<TokenRegistryDto>(it).also { registry ->
			check(registry.schemaVersion == GEM_TOKEN_SCHEMA_VERSION) {
				"Unsupported Gem token registry schema: ${registry.schemaVersion}"
			}
		}
	} ?: TokenRegistryDto(GEM_TOKEN_SCHEMA_VERSION, emptyList())

	private fun write(registry: TokenRegistryDto) {
		check(storage.write(json.encodeToString(registry))) { "Failed to save Gem token registry" }
	}

	private fun validate(token: GemToken) {
		check(token.walletId.value.isNotBlank()) { "Gem token wallet ID must not be blank" }
		check(token.chain.provider == Provider.Gem) { "Unsupported Gem token chain: ${token.chain.key}" }
		check(token.assetId.isNotBlank() && token.assetId != "native") { "Gem token asset ID is invalid" }
		check(token.metadata.decimals >= 0) { "Gem token decimals must not be negative" }
	}

	private companion object {
		val json = Json {
			encodeDefaults = true
			ignoreUnknownKeys = false
			isLenient = false
			coerceInputValues = false
		}
	}
}

internal interface GemTokenStorage {
	fun read(): String?
	fun write(value: String): Boolean
}

private class SecurityGemTokenStorage(
	private val storage: SecurityStorageBox,
) : GemTokenStorage {
	override fun read(): String? = storage.get(GEM_TOKEN_STORAGE_KEY)

	override fun write(value: String): Boolean = storage.transaction {
		putString(GEM_TOKEN_STORAGE_KEY, value)
	}
}

@Serializable
private data class TokenRegistryDto(
	val schemaVersion: Int,
	val tokens: List<GemTokenDto>,
)

@Serializable
private data class GemTokenDto(
	val walletId: String,
	val chain: String,
	val assetId: String,
	val symbol: String,
	val name: String,
	val decimals: Int,
	val imageUrl: String? = null,
)

private fun GemToken.toDto() = GemTokenDto(
	walletId = walletId.value,
	chain = chain.key,
	assetId = assetId,
	symbol = metadata.symbol,
	name = metadata.name,
	decimals = metadata.decimals,
	imageUrl = metadata.imageUrl,
)

private fun GemTokenDto.toDomain(): GemToken {
	val tokenChain = Chain.entries.firstOrNull { it.key == chain && it.provider == Provider.Gem }
		?: throw IllegalArgumentException("Unsupported Gem token chain: $chain")
	return GemToken(
		walletId = WalletId(walletId),
		chain = tokenChain,
		assetId = assetId,
		metadata = AssetMetadata.Known(symbol, name, decimals, imageUrl),
	)
}
