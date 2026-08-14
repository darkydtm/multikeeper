package com.tonapps.wallet.data.gem

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
internal data class Device(
	val id: String,
	val platform: String,
	val platformStore: String,
	val os: String,
	val model: String,
	val token: String,
	val locale: String,
	val version: String,
	val currency: String,
	val isPushEnabled: Boolean,
	val isPriceAlertsEnabled: Boolean? = null,
	val subscriptionsVersion: Int,
)

@Serializable
internal data class DeviceToken(
	val token: String,
	val expiresAt: ULong,
)

@Serializable
internal data class AddressChains(
	val address: String,
	val chains: List<String>,
)

@Serializable
internal data class WalletSubscription(
	val walletId: String,
	val source: String? = null,
	val subscriptions: List<AddressChains>,
)

@Serializable
internal data class WalletSubscriptionChains(
	val walletId: String,
	val chains: List<String>,
)

@Serializable
internal data class TransactionsResponse(
	val transactions: List<Transaction>,
	val addressNames: List<AddressName>,
)

@Serializable
internal data class Transaction(
	val id: String,
	val assetId: String,
	val from: String,
	val to: String,
	val contract: String? = null,
	val type: String,
	val state: String,
	val blockNumber: String? = null,
	val sequence: String? = null,
	val fee: String,
	val feeAssetId: String,
	val value: String,
	val memo: String? = null,
	val direction: String,
	val utxoInputs: List<TransactionUtxoInput>? = null,
	val utxoOutputs: List<TransactionUtxoInput>? = null,
	val metadata: JsonElement? = null,
	val createdAt: String,
)

@Serializable
internal data class TransactionUtxoInput(
	val address: String,
	val value: String,
)

@Serializable
internal data class AddressName(
	val chain: String,
	val address: String,
	val name: String,
	val type: String,
	val status: String,
)

@Serializable
internal data class PortfolioAsset(
	val assetId: String,
	val value: String,
)

@Serializable
internal data class PortfolioAssetsRequest(
	val assets: List<PortfolioAsset>,
)

@Serializable
internal data class PortfolioAssets(
	val totalValue: JsonPrimitive,
	val values: List<ChartValue>,
	val allTimeHigh: ChartValuePercentage? = null,
	val allTimeLow: ChartValuePercentage? = null,
	val allocation: List<PortfolioAllocation>,
)

@Serializable
internal data class ChartValue(
	val timestamp: Int,
	val value: JsonPrimitive,
)

@Serializable
internal data class ChartValuePercentage(
	val date: String,
	val value: JsonPrimitive,
	val percentage: Float,
)

@Serializable
internal data class PortfolioAllocation(
	val assetId: String,
	val percentage: Float,
	val value: JsonPrimitive,
)

@Serializable
internal data class ChainAddress(
	val chain: String,
	val address: String,
)

@Serializable
internal data class WalletConfiguration(
	val multiSignatureAccounts: List<ChainAddress>,
)

@Serializable
internal data class WalletConfigurationResult(
	val walletId: String,
	val configuration: WalletConfiguration,
)

@Serializable
internal data class ScanAddressTarget(
	val assetId: String,
	val address: String,
)

@Serializable
internal data class ScanTransactionPayload(
	val origin: ScanAddressTarget,
	val target: ScanAddressTarget,
	val website: String? = null,
	val type: String,
)

@Serializable
internal data class ScanTransaction(
	val isMalicious: Boolean,
	val isMemoRequired: Boolean,
)
