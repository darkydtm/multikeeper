package com.tonapps.wallet.data.gem

enum class Chain(
	val key: String,
	val provider: Provider,
) {
	Ton("ton", Provider.Ton),
	Bitcoin("bitcoin", Provider.Gem),
	Ethereum("ethereum", Provider.Gem),
	SmartChain("smartchain", Provider.Gem),
	Solana("solana", Provider.Gem),
}

enum class Provider {
	Ton,
	Gem,
}

@JvmInline
value class WalletId(val value: String) {
	override fun toString(): String = value
}

data class GemWalletLabel(
	val name: String,
	val emoji: String,
	val color: Int,
)

data class ChainAccount(
	val walletId: WalletId,
	val chain: Chain,
	val address: String,
	val publicKey: String? = null,
	val derivationPath: String? = null,
) {
	val identityKey: String
		get() = "${walletId.value}:${chain.key}:${chain.provider.key}:$address"
}

data class AssetId(
	val chain: Chain,
	val value: String,
) {
	val cacheKey: String
		get() = "${chain.key}:${chain.provider.key}:$value"
}

data class Asset(
	val id: AssetId,
	val metadata: AssetMetadata = AssetMetadata.Unknown,
)

sealed interface AssetMetadata {
	data object Unknown : AssetMetadata
	data class Known(
		val symbol: String,
		val name: String,
		val decimals: Int,
	) : AssetMetadata
}

data class Balance(
	val amount: String?,
	val available: String? = amount,
)

data class TransactionRecord(
	val walletId: WalletId,
	val chain: Chain,
	val assetId: AssetId,
	val provider: Provider = chain.provider,
	val gemId: String? = null,
	val hash: String? = null,
	val amount: String,
	val fee: String? = null,
	val timestamp: Long? = null,
	val state: TransactionState = TransactionState.Pending,
) {
	val cacheKey: String
		get() = "${walletId.value}:${chain.key}:${provider.key}:${hash ?: gemId}"
}

enum class TransactionState {
	Pending,
	InTransit,
	Confirmed,
	Failed,
	Reverted,
	Unknown,
}

data class TransactionDraft(
	val walletId: WalletId,
	val chain: Chain,
	val assetId: AssetId,
	val sender: String,
	val recipient: String,
	val amount: String,
	val assetMetadata: AssetMetadata = AssetMetadata.Unknown,
	val isMaxValue: Boolean = false,
	val fee: String? = null,
	val memo: String? = null,
	val contractData: ByteArray? = null,
)

sealed interface GemError {
	data class InvalidInput(val message: String) : GemError
	data class UnsupportedOperation(val message: String) : GemError
	data object AuthenticationRequired : GemError
	data object DeviceRegistrationRequired : GemError
	data class NetworkUnavailable(val cause: Throwable? = null) : GemError
	data class BackendRejected(val code: String, val message: String? = null) : GemError
	data object SigningCancelled : GemError
	data class SigningFailed(val message: String, val cause: Throwable? = null) : GemError
	data class BroadcastFailed(val message: String, val cause: Throwable? = null) : GemError
	data object StatusUnknown : GemError
}

private val Provider.key: String
	get() = when (this) {
		Provider.Ton -> "ton"
		Provider.Gem -> "gem"
	}
