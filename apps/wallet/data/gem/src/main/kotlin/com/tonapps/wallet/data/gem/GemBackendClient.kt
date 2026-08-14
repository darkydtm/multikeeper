package com.tonapps.wallet.data.gem

import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

class GemBackendClient(
	httpClient: OkHttpClient,
	signer: GemRequestSigner,
	private val environment: GemBackendEnvironment = GemBackendEnvironment.MAINNET,
	private val json: Json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = false
	},
) : GemSubscriptionBackend, GemDeviceBackend, GemBackendReader, GemDeviceTokenBackend {
	private val signedClient = httpClient.newBuilder()
		.addInterceptor(GemRequestSignerInterceptor(signer))
		.build()

	override suspend fun getDevice(): Result<GemDevice?> = execute<Device?, GemDevice?>(
		Request.Builder().get().url(url("/v2/devices")).build(),
		notFound = { null },
	) { it?.toModel() }

	override suspend fun getDeviceToken(): Result<GemDeviceToken> = execute<DeviceToken, GemDeviceToken>(
		Request.Builder().get().url(url("/v2/devices/token")).build(),
	) { GemDeviceToken(it.token, it.expiresAt) }

	override suspend fun registerDevice(device: GemDevice): Result<GemDevice?> = execute<Device?, GemDevice?>(
		Request.Builder().post(jsonBody(device.toDto())).url(url("/v2/devices")).build(),
	) { it?.toModel() }

	override suspend fun updateDevice(device: GemDevice): Result<GemDevice?> = execute<Device?, GemDevice?>(
		Request.Builder().put(jsonBody(device.toDto())).url(url("/v2/devices")).build(),
	) { it?.toModel() }

	override suspend fun getSubscriptions(): Result<List<GemWalletSubscriptionChains>?> = execute<List<WalletSubscriptionChains>?, List<GemWalletSubscriptionChains>?>(
		Request.Builder().get().url(url("/v2/devices/subscriptions")).build(),
	) { value -> value?.map(WalletSubscriptionChains::toModel) }

	override suspend fun addSubscriptions(subscriptions: List<GemWalletSubscription>): Result<Int> = execute<Int, Int>(
		Request.Builder()
			.post(jsonBody(subscriptions.map(GemWalletSubscription::toDto)))
			.url(url("/v2/devices/subscriptions"))
			.build(),
	) { it }

	override suspend fun deleteSubscriptions(subscriptions: List<GemWalletSubscriptionChains>): Result<Int> = execute<Int, Int>(
		Request.Builder()
			.delete(jsonBody(subscriptions.map(GemWalletSubscriptionChains::toDto)))
			.url(url("/v2/devices/subscriptions"))
			.build(),
	) { it }

	override suspend fun getAssets(walletId: WalletId, fromTimestamp: Long): Result<List<String>> = execute<List<String>, List<String>>(
		walletRequest(walletId, "GET") {
			url(url("/v2/devices/assets", "from_timestamp" to fromTimestamp.toString()))
		},
	) { it }

	override suspend fun getTransactions(
		walletId: WalletId,
		fromTimestamp: Long,
		assetId: String? = null,
	): Result<GemTransactionsResponse?> = execute<TransactionsResponse?, GemTransactionsResponse?>(
		walletRequest(walletId, "GET") {
			url(url(
				"/v2/devices/transactions",
				"from_timestamp" to fromTimestamp.toString(),
				*assetId?.let { arrayOf("asset_id" to it) }.orEmpty(),
			))
		},
	) { it?.toModel() }

	override suspend fun getTransaction(transactionId: String): Result<GemTransaction> = execute<Transaction, GemTransaction>(
		Request.Builder().get().url(transactionUrl(transactionId)).build(),
	) { it.toModel() }

	override suspend fun getPortfolioAssets(
		period: String,
		request: GemPortfolioAssetsRequest,
	): Result<GemPortfolioAssets> = execute<PortfolioAssets, GemPortfolioAssets>(
		Request.Builder()
			.post(jsonBody(request.toDto()))
			.url(url("/v2/devices/portfolio/assets", "period" to period))
			.build(),
	) { it.toModel() }

	suspend fun getWalletConfiguration(walletId: WalletId): Result<GemWalletConfigurationResult> = execute<WalletConfigurationResult, GemWalletConfigurationResult>(
		walletRequest(walletId, "GET") {
			url(url("/v2/devices/wallet_configuration"))
		},
	) { it.toModel() }

	suspend fun scanTransaction(payload: GemScanTransactionPayload): Result<GemScanTransaction> = execute<ScanTransaction, GemScanTransaction>(
		Request.Builder()
			.post(jsonBody(payload.toDto()))
			.url(url("/v2/devices/scan/transaction"))
			.build(),
	) { it.toModel() }

	private fun url(path: String, vararg query: Pair<String, String>): HttpUrl =
		environment.baseUrl.toHttpUrl().newBuilder().apply {
			addEncodedPathSegments(path.trimStart('/'))
			query.forEach { (name, value) -> addQueryParameter(name, value) }
		}.build()

	internal fun transactionUrl(transactionId: String): HttpUrl =
		url("/v2/devices/transaction").newBuilder().addPathSegment(transactionId).build()

	private fun walletRequest(
		walletId: WalletId,
		method: String,
		block: Request.Builder.() -> Request.Builder,
	): Request = Request.Builder()
		.method(method, null)
		.tag(GemWalletId::class.java, GemWalletId(walletId.value))
		.block()

	private suspend inline fun <reified T, R> execute(
		request: Request,
		notFound: (() -> R)? = null,
		map: (T) -> R,
	): Result<R> = withContext(Dispatchers.IO) {
		try {
			signedClient.newCall(request).execute().use { response ->
				val body = response.body?.string().orEmpty()
				if (!response.isSuccessful) {
					if (response.code == 404 && notFound != null) {
						return@withContext Result.success(notFound())
					}
					throw GemBackendException(response.code)
				}
				Result.success(map(json.decodeFromString<T>(body.ifBlank { "null" })))
			}
		} catch (error: CancellationException) {
			throw error
		} catch (error: GemBackendException) {
			Result.failure(error)
		} catch (error: IOException) {
			Result.failure(GemBackendException(null, GemError.NetworkUnavailable()))
		} catch (error: SerializationException) {
			Result.failure(GemBackendException(null, GemError.BackendRejected("invalid_response")))
		} catch (error: Exception) {
			Result.failure(GemBackendException(null, GemError.BackendRejected("invalid_response")))
		}
	}

	private inline fun <reified T> jsonBody(value: T) = json.encodeToString(value).toRequestBody(JSON_MEDIA_TYPE)

	private companion object {
		val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
	}
}

class GemBackendException(
	val statusCode: Int?,
	val gemError: GemError = statusCode.toGemError(),
	) : IOException("Gem backend request failed")

private fun Int?.toGemError(): GemError = when (this) {
		401, 403 -> GemError.AuthenticationRequired
		404 -> GemError.DeviceRegistrationRequired
		else -> GemError.BackendRejected(toString())
}

private fun GemDevice.toDto() = Device(
	id = id,
	platform = platform,
	platformStore = platformStore,
	os = os,
	model = model,
	token = token,
	locale = locale,
	version = version,
	currency = currency,
	isPushEnabled = isPushEnabled,
	isPriceAlertsEnabled = isPriceAlertsEnabled,
	subscriptionsVersion = subscriptionsVersion,
)

private fun Device.toModel() = GemDevice(
	id = id,
	platform = platform,
	platformStore = platformStore,
	os = os,
	model = model,
	token = token,
	locale = locale,
	version = version,
	currency = currency,
	isPushEnabled = isPushEnabled,
	isPriceAlertsEnabled = isPriceAlertsEnabled,
	subscriptionsVersion = subscriptionsVersion,
)

private fun GemWalletSubscription.toDto() = WalletSubscription(
	walletId = walletId,
	source = source,
	subscriptions = subscriptions.map { AddressChains(it.address, it.chains) },
)

private fun GemWalletSubscriptionChains.toDto() = WalletSubscriptionChains(walletId, chains)

private fun WalletSubscription.toModel() = GemWalletSubscription(
	walletId = walletId,
	source = source,
	subscriptions = subscriptions.map { GemAddressChains(it.address, it.chains) },
)

private fun WalletSubscriptionChains.toModel() = GemWalletSubscriptionChains(walletId, chains)

private fun TransactionsResponse.toModel() = GemTransactionsResponse(
	transactions = transactions.map(Transaction::toModel),
	addressNames = addressNames.map(AddressName::toModel),
)

private fun Transaction.toModel() = GemTransaction(
	id = id,
	assetId = assetId,
	from = from,
	to = to,
	contract = contract,
	type = type,
	state = state,
	blockNumber = blockNumber,
	sequence = sequence,
	fee = fee,
	feeAssetId = feeAssetId,
	value = value,
	memo = memo,
	direction = direction,
	utxoInputs = utxoInputs?.map { GemTransactionUtxoInput(it.address, it.value) },
	utxoOutputs = utxoOutputs?.map { GemTransactionUtxoInput(it.address, it.value) },
	metadata = metadata,
	createdAt = createdAt,
)

private fun AddressName.toModel() = GemAddressName(chain, address, name, type, status)

private fun GemPortfolioAssetsRequest.toDto() = PortfolioAssetsRequest(
	assets = assets.map { PortfolioAsset(it.assetId, it.value) },
)

private fun PortfolioAssets.toModel() = GemPortfolioAssets(
	totalValue = totalValue.content,
	values = values.map { GemChartValue(it.timestamp, it.value.content) },
	allTimeHigh = allTimeHigh?.let { GemChartValuePercentage(it.date, it.value.content, it.percentage) },
	allTimeLow = allTimeLow?.let { GemChartValuePercentage(it.date, it.value.content, it.percentage) },
	allocation = allocation.map { GemPortfolioAllocation(it.assetId, it.percentage, it.value.content) },
)

private fun WalletConfigurationResult.toModel() = GemWalletConfigurationResult(
	walletId = walletId,
	configuration = GemWalletConfiguration(configuration.multiSignatureAccounts.map { GemChainAddress(it.chain, it.address) }),
)

private fun GemScanTransactionPayload.toDto() = ScanTransactionPayload(
	origin = ScanAddressTarget(origin.assetId, origin.address),
	target = ScanAddressTarget(target.assetId, target.address),
	website = website,
	type = type,
)

private fun ScanTransaction.toModel() = GemScanTransaction(isMalicious, isMemoRequired)
