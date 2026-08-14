package com.tonapps.wallet.data.gem

import uniffi.gemstone.GemImportType
import uniffi.gemstone.GemKeystore
import uniffi.gemstone.GemKeystoreAccount
import uniffi.gemstone.GemMnemonic
import uniffi.gemstone.GemStoredWallet

fun interface GemPasswordProvider {
	fun passwordBytes(): ByteArray
}

fun interface GemKeystoreDeleter {
	fun deleteKeystore(keystoreId: String)
}

class GemWalletBridge(
	private val baseDir: String,
	private val passwordProvider: GemPasswordProvider,
	private val mnemonicWords: () -> List<String> = {
		GemMnemonic().use { mnemonic -> mnemonic.generate(wordCount = 12u) }
	},
	private val mnemonicValidator: (List<String>) -> Boolean = { words ->
		GemMnemonic().use { mnemonic ->
			mnemonic.findInvalidWords(words).isEmpty() && mnemonic.isValid(words)
		}
	},
) : GemKeystoreDeleter {
	fun createMnemonicWords(): List<String> = mnemonicWords()

	fun isValidMnemonic(words: List<String>): Boolean = mnemonicValidator(words)

	fun suggestMnemonicWords(query: String): List<String> = GemMnemonic().use { mnemonic ->
		mnemonic.suggestWords(query, null)
	}

	fun createMnemonic(): MnemonicHandle =
		MnemonicHandle(
			GemImportType.MulticoinPhrase(
				words = mnemonicWords(),
				chains = supportedGemChains,
			),
		)

	fun importMnemonic(mnemonic: MnemonicHandle): GemWallet {
		var import: GemImportType? = mnemonic.consumeImportType()
		try {
			return GemKeystore(baseDir).use { keystore ->
				keystore.previewImport(checkNotNull(import))
				val password = passwordProvider.passwordBytes()
				try {
					keystore.createStore(checkNotNull(import), password).toGemWallet()
				} finally {
					password.fill(0)
				}
			}
		} finally {
			import = null
		}
	}

	fun importMnemonic(words: List<String>): GemWallet = importMnemonic(
		MnemonicHandle(
			GemImportType.MulticoinPhrase(
				words = words.toList(),
				chains = supportedGemChains,
			),
		),
	)

	override fun deleteKeystore(keystoreId: String) {
		GemKeystore(baseDir).use { keystore ->
			if (!keystore.delete(keystoreId)) {
				throw IllegalStateException("Failed to delete Gem keystore")
			}
		}
	}

	private fun Chain.toGemChain(): uniffi.gemstone.Chain = when (this) {
		Chain.Bitcoin -> GEM_BITCOIN
		Chain.Ethereum -> GEM_ETHEREUM
		Chain.SmartChain -> GEM_SMART_CHAIN
		Chain.Solana -> GEM_SOLANA
		else -> throw IllegalArgumentException("Unsupported Gemstone chain: ${key}")
	}

	private fun GemKeystoreAccount.toChainAccount(walletId: String): ChainAccount = ChainAccount(
		walletId = WalletId(walletId),
		chain = chain.toChain(),
		address = address,
		publicKey = publicKey,
		derivationPath = derivationPath,
	)

	private fun uniffi.gemstone.Chain.toChain(): Chain = when (this) {
		GEM_BITCOIN -> Chain.Bitcoin
		GEM_ETHEREUM -> Chain.Ethereum
		GEM_SMART_CHAIN -> Chain.SmartChain
		GEM_SOLANA -> Chain.Solana
		else -> throw IllegalArgumentException("Unsupported Gemstone chain: $this")
	}

	private fun GemStoredWallet.toGemWallet(): GemWallet = GemWallet(
		walletId = WalletId(walletId),
		keystoreId = keystoreId,
		accounts = accounts.map { it.toChainAccount(walletId) },
	)

	private companion object {
		const val GEM_BITCOIN: uniffi.gemstone.Chain = "bitcoin"
		const val GEM_ETHEREUM: uniffi.gemstone.Chain = "ethereum"
		const val GEM_SMART_CHAIN: uniffi.gemstone.Chain = "smartchain"
		const val GEM_SOLANA: uniffi.gemstone.Chain = "solana"
		val supportedGemChains = listOf(
			Chain.Bitcoin.toGemChain(),
			Chain.Ethereum.toGemChain(),
			Chain.SmartChain.toGemChain(),
			Chain.Solana.toGemChain(),
		)
	}
}

class MnemonicHandle internal constructor(
	private var import: GemImportType?,
) {
	internal fun consumeImportType(): GemImportType = synchronized(this) {
		import?.also { import = null }
			?: throw IllegalStateException("Mnemonic handle has already been consumed")
	}
}

data class GemWallet(
	val walletId: WalletId,
	val keystoreId: String,
	val accounts: List<ChainAccount>,
	val label: GemWalletLabel? = null,
)
