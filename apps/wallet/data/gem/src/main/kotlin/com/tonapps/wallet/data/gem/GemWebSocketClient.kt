package com.tonapps.wallet.data.gem

import android.util.Log
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.awaitClose
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl

sealed interface GemWebSocketEvent {
	data class Prices(
		val prices: List<GemPrice>,
		val rates: List<GemFiatRate>,
	) : GemWebSocketEvent

	data class Balances(val updates: List<GemBalanceInvalidation>) : GemWebSocketEvent

	data class Transactions(
		val walletId: String,
		val transactionIds: List<String>,
	) : GemWebSocketEvent
}

data class GemPrice(
	val assetId: String,
	val price: Double,
	val priceChangePercentage24h: Double,
	val updatedAt: Instant,
)

data class GemFiatRate(
	val symbol: String,
	val rate: Double,
)

data class GemBalanceInvalidation(
	val walletId: String,
	val assetId: String,
)

fun interface GemWebSocketSource {
	fun connect(): Flow<GemWebSocketEvent>
}

@Suppress("ClassOrdering")
class GemWebSocketClient(
	private val client: OkHttpClient,
	private val signer: GemRequestSigner,
	private val baseUrl: String = GemBackendEnvironment.MAINNET.baseUrl,
	private val priceAssets: List<String> = emptyList(),
) : GemWebSocketSource {
	private val webSocketClient = client.newBuilder()
		.followSslRedirects(false)
		.pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
		.build()

	override fun connect(): Flow<GemWebSocketEvent> = flow {
		var attempt = 0
		while (currentCoroutineContext().isActive) {
			var connected = false
			try {
				observeSession {
					connected = true
				}.collect { emit(it) }
			} catch (error: Throwable) {
				if (!currentCoroutineContext().isActive) throw error
			}
			if (connected) attempt = 0
			delay(reconnectDelay(attempt))
			attempt++
		}
	}

	private suspend fun observeSession(onOpen: () -> Unit): Flow<GemWebSocketEvent> = callbackFlow {
		Log.d(LOG_TAG, "stream connect url=${streamUrl()}")
		val request = Request.Builder()
			.url(streamUrl())
			.header("Authorization", signer.sign("GET", STREAM_PATH, ByteArray(0), ""))
			.build()
		val webSocket = webSocketClient.newWebSocket(request, object : WebSocketListener() {
			 override fun onOpen(webSocket: WebSocket, response: Response) {
				Log.d(LOG_TAG, "stream opened code=${response.code}")
				priceSubscriptionMessage(priceAssets)?.let(webSocket::send)
				onOpen()
			}

			override fun onMessage(webSocket: WebSocket, text: String) {
				parseEvent(text)
					.onSuccess {
						Log.d(LOG_TAG, "stream event type=${it::class.simpleName}")
						trySend(it)
					}
					.onFailure { error -> Log.e(LOG_TAG, "stream event parse failed", error) }
			}

			override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
				webSocket.close(code, reason)
			}

			override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
				Log.d(LOG_TAG, "stream closed code=$code reason=$reason")
				close()
			}

			override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
				Log.e(LOG_TAG, "stream failure code=${response?.code}", t)
				close(t)
			}
		})

		awaitClose { webSocket.cancel() }
	}

	internal fun streamUrl(): String =
		baseUrl.toHttpUrl().newBuilder()
			.scheme("wss")
			.addPathSegment("v2")
			.addPathSegment("devices")
			.addPathSegment("stream")
			.build()
			.toString()

	companion object {
		private const val LOG_TAG = "GemStream"
		private const val STREAM_PATH = "/v2/devices/stream"
		private const val PING_INTERVAL_MS = 30_000L
		private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
		private const val MAX_RECONNECT_DELAY_MS = 30_000L

		internal fun reconnectDelay(attempt: Int): Long {
			val shift = attempt.coerceAtLeast(0).coerceAtMost(5)
			return (INITIAL_RECONNECT_DELAY_MS shl shift).coerceAtMost(MAX_RECONNECT_DELAY_MS)
		}

		internal fun priceSubscriptionMessage(priceAssets: List<String>): String? = priceAssets
			.takeIf { it.isNotEmpty() }
			?.let {
				STREAM_JSON.encodeToString(
					GemPriceSubscriptionMessage(type = "subscribePrices", data = GemPriceSubscription(it)),
				)
			}

		internal fun parseEvent(text: String): Result<GemWebSocketEvent> = runCatching {
			val envelope = STREAM_JSON.decodeFromString<GemEventEnvelope>(text)
			when (envelope.event) {
				"prices" -> decodePrices(envelope.data)
				"balances", "balance" -> GemWebSocketEvent.Balances(decodeBalances(envelope.data))
				"transactions" -> decodeTransactions(envelope.data)
				else -> throw IllegalArgumentException("Unsupported Gem stream event")
			}
		}

		private fun decodePrices(data: JsonElement): GemWebSocketEvent.Prices {
			val value = STREAM_JSON.decodeFromJsonElement<GemPricesPayload>(data)
			val prices = value.prices.map {
				require(it.assetId.isNotBlank())
				require(it.price.isFinite())
				require(it.price >= 0)
				require(it.priceChangePercentage24h.isFinite())
				GemPrice(it.assetId, it.price, it.priceChangePercentage24h, Instant.parse(it.updatedAt))
			}
			val rates = value.rates.map {
				require(it.symbol.isNotBlank())
				require(it.rate.isFinite())
				GemFiatRate(it.symbol, it.rate)
			}
			return GemWebSocketEvent.Prices(prices, rates)
		}

		private fun decodeBalances(data: JsonElement): List<GemBalanceInvalidation> {
			val elements = if (data is kotlinx.serialization.json.JsonArray) data else listOf(data)
			return elements.map { element ->
				val value = element.jsonObject
				GemBalanceInvalidation(
					walletId = value.string("walletId", "wallet_id"),
					assetId = value.string("assetId", "asset_id"),
				)
			}
		}

		private fun decodeTransactions(data: JsonElement): GemWebSocketEvent.Transactions {
			val value = data.jsonObject
			val walletId = value.string("walletId", "wallet_id")
			val transactionIds = value.firstValue("transactions", "transactionIds", "transaction_ids")
				.jsonArray
				.map { it.jsonPrimitive.content }
				.onEach { require(it.isNotBlank()) }
			return GemWebSocketEvent.Transactions(walletId, transactionIds)
		}

		private fun JsonObject.string(vararg names: String): String = firstValue(*names).jsonPrimitive.content.also { require(it.isNotBlank()) }

		private fun JsonObject.firstValue(vararg names: String): JsonElement = names.firstNotNullOfOrNull(::get)
			?: throw IllegalArgumentException("Missing stream field")

		private val STREAM_JSON = Json { ignoreUnknownKeys = true }
	}
}

@Serializable
private data class GemEventEnvelope(
	val event: String,
	val data: JsonElement,
)

@Serializable
private data class GemPricesPayload(
	val prices: List<GemPricePayload>,
	val rates: List<GemRatePayload>,
)

@Serializable
private data class GemPricePayload(
	val assetId: String,
	val price: Double,
	val priceChangePercentage24h: Double,
	val updatedAt: String,
)

@Serializable
private data class GemRatePayload(
	val symbol: String,
	val rate: Double,
)

@Serializable
private data class GemPriceSubscriptionMessage(
	val type: String,
	val data: GemPriceSubscription,
)

@Serializable
private data class GemPriceSubscription(
	val assets: List<String>,
)
