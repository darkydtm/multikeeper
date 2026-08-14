package com.tonapps.wallet.data.gem

data class GemDevice(
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

data class GemDeviceToken(
	val token: String,
	val expiresAtSeconds: ULong,
)

data class GemAddressChains(
	val address: String,
	val chains: List<String>,
)

data class GemWalletSubscription(
	val walletId: String,
	val source: String? = null,
	val subscriptions: List<GemAddressChains>,
)

data class GemWalletSubscriptionChains(
	val walletId: String,
	val chains: List<String>,
)

data class GemTransactionsResponse(
	val transactions: List<GemTransaction>,
	val addressNames: List<GemAddressName>,
)

data class GemTransaction(
	val id: String,
	val assetId: String,
	val from: String,
	val to: String,
	val contract: String?,
	val type: String,
	val state: String,
	val blockNumber: String?,
	val sequence: String?,
	val fee: String,
	val feeAssetId: String,
	val value: String,
	val memo: String?,
	val direction: String,
	val utxoInputs: List<GemTransactionUtxoInput>?,
	val utxoOutputs: List<GemTransactionUtxoInput>?,
	val metadata: kotlinx.serialization.json.JsonElement?,
	val createdAt: String,
)

data class GemTransactionUtxoInput(
	val address: String,
	val value: String,
)

data class GemAddressName(
	val chain: String,
	val address: String,
	val name: String,
	val type: String,
	val status: String,
)

data class GemPortfolioAsset(
	val assetId: String,
	val value: String,
)

data class GemPortfolioAssetsRequest(
	val assets: List<GemPortfolioAsset>,
)

data class GemPortfolioAssets(
	val totalValue: String,
	val values: List<GemChartValue>,
	val allTimeHigh: GemChartValuePercentage?,
	val allTimeLow: GemChartValuePercentage?,
	val allocation: List<GemPortfolioAllocation>,
)

data class GemChartValue(
	val timestamp: Int,
	val value: String,
)

data class GemChartValuePercentage(
	val date: String,
	val value: String,
	val percentage: Float,
)

data class GemPortfolioAllocation(
	val assetId: String,
	val percentage: Float,
	val value: String,
)

data class GemChainAddress(
	val chain: String,
	val address: String,
)

data class GemWalletConfiguration(
	val multiSignatureAccounts: List<GemChainAddress>,
)

data class GemWalletConfigurationResult(
	val walletId: String,
	val configuration: GemWalletConfiguration,
)

data class GemScanAddressTarget(
	val assetId: String,
	val address: String,
)

data class GemScanTransactionPayload(
	val origin: GemScanAddressTarget,
	val target: GemScanAddressTarget,
	val website: String? = null,
	val type: String,
)

data class GemScanTransaction(
	val isMalicious: Boolean,
	val isMemoRequired: Boolean,
)
