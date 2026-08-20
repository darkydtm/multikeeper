package com.tonapps.wallet.data.gem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.OkHttpClient

class GemWebSocketClientTest {
	@Test
	fun `decodes valid prices and invalidation events while rejecting malformed payloads`() {
		val prices = GemWebSocketClient.parseEvent(
			"""
			{"event":"prices","data":{"prices":[{"assetId":"ethereum","price":12.5,"priceChangePercentage24h":-1.2,"updatedAt":"2024-01-23T12:00:00Z"}],"rates":[{"symbol":"USD","rate":1.0}]}}
			""".trimIndent(),
		)
		val balances = GemWebSocketClient.parseEvent(
			"{"event":"balances","data":[{"walletId":"wallet","assetId":"ethereum"}]}",
		)
		val transactions = GemWebSocketClient.parseEvent(
			"{"event":"transactions","data":{"walletId":"wallet","transactions":["ethereum_hash"]}}",
		)
		val invalid = GemWebSocketClient.parseEvent(
			"{"event":"prices","data":{"prices":[{"assetId":"ethereum","price":"bad","priceChangePercentage24h":0,"updatedAt":"now"}],"rates":[]}}",
		)

		assertTrue(prices.getOrNull() is GemWebSocketEvent.Prices)
		assertEquals(
			GemBalanceInvalidation("wallet", "ethereum"),
			(balances.getOrThrow() as GemWebSocketEvent.Balances).updates.single(),
		)
		assertEquals(
			listOf("ethereum_hash"),
			(transactions.getOrThrow() as GemWebSocketEvent.Transactions).transactionIds,
		)
		assertTrue(invalid.isFailure)
	}

	@Test
	fun `reconnect delay is exponential and bounded`() {
		assertEquals(1_000L, GemWebSocketClient.reconnectDelay(0))
		assertEquals(2_000L, GemWebSocketClient.reconnectDelay(1))
		assertEquals(30_000L, GemWebSocketClient.reconnectDelay(20))
		assertEquals(1_000L, GemWebSocketClient.reconnectDelay(-1))
	}

	@Test
	fun `serializes price subscription with tagged enum shape`() {
		assertEquals(
			"{\"type\":\"subscribePrices\",\"data\":{\"assets\":[\"bitcoin\",\"ethereum\"]}}",
			GemWebSocketClient.priceSubscriptionMessage(listOf("bitcoin", "ethereum")),
		)
	}

	@Test
	fun `does not serialize empty price subscription`() {
		assertEquals(null, GemWebSocketClient.priceSubscriptionMessage(emptyList()))
	}

	@Test
	fun `stream URL uses configured backend path`() {
		val client = GemWebSocketClient(
			client = OkHttpClient(),
			signer = GemRequestSigner { _, _, _, _ -> "signed" },
			baseUrl = "https://testnet.example/api",
		)

		assertEquals(
			"wss://testnet.example/api/v2/devices/stream",
			client.streamUrl(),
		)
	}
}
