package com.tonapps.wallet.data.gem

import com.tonapps.security.SecurityStorageBox
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val GEM_WALLET_REGISTRY_STORAGE_KEY = "gem_wallet_registry"
private const val GEM_WALLET_REGISTRY_SCHEMA_VERSION = 2
private const val GEM_WALLET_REGISTRY_LEGACY_SCHEMA_VERSION = 1

class GemWalletRegistry internal constructor(
	private val storage: GemWalletRegistryStorage,
) {
	constructor(storage: SecurityStorageBox) : this(SecurityGemWalletRegistryStorage(storage))

	@Synchronized
	fun persist(wallet: GemWallet) {
		val walletDto = wallet.toDto()
		val registry = read()
		val wallets = registry.wallets.toMutableList()
		val index = wallets.indexOfFirst { it.walletId == walletDto.walletId }
		if (index == -1) {
			wallets += walletDto
		} else {
			wallets[index] = walletDto
		}
		write(RegistryDto(GEM_WALLET_REGISTRY_SCHEMA_VERSION, wallets))
	}

	@Synchronized
	fun load(): List<GemWallet> = read().wallets.map { it.toDomain() }

	@Synchronized
	fun load(walletId: WalletId): GemWallet? = load().firstOrNull { it.walletId == walletId }

	@Synchronized
	fun delete(walletId: WalletId) {
		val registry = read()
		val wallets = registry.wallets.filterNot { it.walletId == walletId.value }
		if (wallets.size == registry.wallets.size) {
			return
		}
		if (wallets.isEmpty()) {
			if (!storage.delete()) {
				throw IllegalStateException("Failed to delete Gem wallet registry")
			}
			return
		}
		write(RegistryDto(GEM_WALLET_REGISTRY_SCHEMA_VERSION, wallets))
	}

	private fun read(): RegistryDto = storage.read()?.let { encoded ->
		json.decodeFromString<RegistryDto>(encoded).let { registry ->
			when (registry.schemaVersion) {
				GEM_WALLET_REGISTRY_LEGACY_SCHEMA_VERSION -> registry.copy(
					schemaVersion = GEM_WALLET_REGISTRY_SCHEMA_VERSION,
					wallets = registry.wallets.map { it.copy(label = null) },
				)
				GEM_WALLET_REGISTRY_SCHEMA_VERSION -> registry
				else -> throw IllegalArgumentException("Unsupported Gem wallet registry schema: ${registry.schemaVersion}")
			}
		}
	} ?: RegistryDto(GEM_WALLET_REGISTRY_SCHEMA_VERSION, emptyList())

	private fun write(registry: RegistryDto) {
		if (!storage.write(json.encodeToString(registry))) {
			throw IllegalStateException("Failed to save Gem wallet registry")
		}
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

internal interface GemWalletRegistryStorage {
	fun read(): String?

	fun write(value: String): Boolean

	fun delete(): Boolean
}

private class SecurityGemWalletRegistryStorage(
	private val storage: SecurityStorageBox,
) : GemWalletRegistryStorage {
	override fun read(): String? = storage.get(GEM_WALLET_REGISTRY_STORAGE_KEY)

	override fun write(value: String): Boolean = storage.transaction {
		putString(GEM_WALLET_REGISTRY_STORAGE_KEY, value)
	}

	override fun delete(): Boolean = storage.transaction {
		remove(GEM_WALLET_REGISTRY_STORAGE_KEY)
	}
}

@Serializable
private data class RegistryDto(
	val schemaVersion: Int,
	val wallets: List<GemWalletDto>,
)

@Serializable
private data class GemWalletDto(
	val walletId: String,
	val keystoreId: String,
	val accounts: List<ChainAccountDto>,
	val label: GemWalletLabelDto? = null,
)

@Serializable
private data class GemWalletLabelDto(
	val name: String,
	val emoji: String,
	val color: Int,
)

@Serializable
private data class ChainAccountDto(
	val chain: String,
	val address: String,
	val publicKey: String?,
	val derivationPath: String?,
)

private fun GemWallet.toDto(): GemWalletDto {
	check(walletId.value.isNotBlank()) { "Gem wallet ID must not be blank" }
	check(keystoreId.isNotBlank()) { "Gem keystore ID must not be blank" }
	accounts.forEach { account ->
		check(account.walletId == walletId) { "Gem account wallet ID does not match its wallet" }
		check(account.address.isNotBlank()) { "Gem account address must not be blank" }
		check(account.chain.provider == Provider.Gem) { "Unsupported Gem wallet chain: ${account.chain.key}" }
	}
	return GemWalletDto(
		walletId = walletId.value,
		keystoreId = keystoreId,
		accounts = accounts.map { account ->
			ChainAccountDto(
				chain = account.chain.key,
				address = account.address,
				publicKey = account.publicKey,
				derivationPath = account.derivationPath,
			)
		},
		label = label?.let {
			GemWalletLabelDto(
				name = it.name,
				emoji = it.emoji,
				color = it.color,
			)
		},
	)
}

private fun GemWalletDto.toDomain(): GemWallet {
	check(walletId.isNotBlank()) { "Gem wallet ID must not be blank" }
	check(keystoreId.isNotBlank()) { "Gem keystore ID must not be blank" }
	return GemWallet(
		walletId = WalletId(walletId),
		keystoreId = keystoreId,
		accounts = accounts.map { account ->
			val chain = Chain.entries.firstOrNull { it.key == account.chain && it.provider == Provider.Gem }
				?: throw IllegalArgumentException("Unsupported Gem wallet chain: ${account.chain}")
			check(account.address.isNotBlank()) { "Gem account address must not be blank" }
			ChainAccount(
				walletId = WalletId(walletId),
				chain = chain,
				address = account.address,
				publicKey = account.publicKey,
				derivationPath = account.derivationPath,
			)
		},
		label = label?.let {
			GemWalletLabel(
				name = it.name,
				emoji = it.emoji,
				color = it.color,
			)
		},
	)
}
