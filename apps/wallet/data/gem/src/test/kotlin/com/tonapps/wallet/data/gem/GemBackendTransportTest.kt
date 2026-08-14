package com.tonapps.wallet.data.gem

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GemBackendTransportTest {
	@Test
	fun `signer receives exact request path body and wallet id`() {
		var signedMethod: String? = null
		var signedPath: String? = null
		var signedBody: ByteArray? = null
		var signedWalletId: String? = null
		val signer = GemRequestSigner { method, path, body, walletId ->
			signedMethod = method
			signedPath = path
			signedBody = body
			signedWalletId = walletId
			"signed"
		}
		val body = "{\"assetId\":\"ton\",\"value\":\"1.25\"}"
		val request = Request.Builder()
			.url("https://api.gemwallet.com/v2/devices/portfolio/assets?period=all%20time")
			.post(body.toRequestBody("application/json".toMediaType()))
			.tag(GemWalletId::class.java, GemWalletId("wallet-123"))
			.build()

		val chain = RecordingChain(request)
		GemRequestSignerInterceptor(signer).intercept(chain)

		assertEquals("POST", signedMethod)
		assertEquals("/v2/devices/portfolio/assets", signedPath)
		assertEquals(body, signedBody!!.toString(Charsets.UTF_8))
		assertEquals("wallet-123", signedWalletId)
		assertEquals("signed", chain.proceededRequest!!.header("Authorization"))
	}

	@Test
	fun `release selection always uses mainnet`() {
		assertEquals(
			GemBackendEnvironment.MAINNET,
			GemBackendEnvironment.forBuild(isDebug = false, debugEnvironment = GemBackendEnvironment.TESTNET),
		)
		assertEquals(
			GemBackendEnvironment.TESTNET,
			GemBackendEnvironment.forBuild(isDebug = true, debugEnvironment = GemBackendEnvironment.TESTNET),
		)
		assertTrue(GemBackendEnvironment.MAINNET.baseUrl.startsWith("https://"))
	}

	@Test
	fun `transaction id is encoded as one path segment`() {
		val client = GemBackendClient(
			httpClient = OkHttpClient(),
			signer = GemRequestSigner { _, _, _, _ -> "signed" },
		)

		assertEquals(
			"/v2/devices/transaction/tx%2Fwith%3Freserved%25chars",
			client.transactionUrl("tx/with?reserved%chars").encodedPath,
		)
	}

	@Test
	fun `device lookup maps not found to missing device`() = runBlocking {
		val result = clientWithResponse(404).getDevice()

		assertEquals(null, result.getOrThrow())
	}

	@Test
	fun `non-device lookup 404 remains a failure`() = runBlocking {
		val result = clientWithResponse(404).getSubscriptions()

		assertTrue(result.isFailure)
		assertEquals(404, (result.exceptionOrNull() as GemBackendException).statusCode)
	}

	@Test
	fun `device registration 404 remains a failure`() = runBlocking {
		val result = clientWithResponse(404).registerDevice(testDevice())

		assertTrue(result.isFailure)
		assertEquals(404, (result.exceptionOrNull() as GemBackendException).statusCode)
	}

	@Test
	fun `device registration uses official payload values`() = runBlocking {
		var payload: Device? = null
		clientWithResponse(200) { request ->
			val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
			payload = Json.decodeFromString(body)
		}.registerDevice(testDevice())

		assertEquals("googlePlay", payload!!.platformStore)
		assertEquals("en", payload!!.locale)
	}

	@Test
	fun `device token response preserves token and expiry`() = runBlocking {
		val result = clientWithResponse(
			code = 200,
			body = "{\"token\":\"jwt\",\"expiresAt\":12345}",
		).getDeviceToken()

		assertEquals(GemDeviceToken("jwt", 12345uL), result.getOrThrow())
	}

	private fun testDevice() = GemDevice(
		id = "device-id",
		platform = "android",
		platformStore = "googlePlay",
		os = "android 15",
		model = "Pixel",
		token = "",
		locale = "en",
		version = "1.0",
		currency = "USD",
		isPushEnabled = false,
		subscriptionsVersion = 1,
	)

	private fun clientWithResponse(
		code: Int,
		body: String? = null,
		onRequest: (Request) -> Unit = {},
	): GemBackendClient = GemBackendClient(
		httpClient = OkHttpClient.Builder()
			.addInterceptor { chain ->
				onRequest(chain.request())
				Response.Builder()
					.request(chain.request())
					.protocol(Protocol.HTTP_1_1)
					.code(code)
					.message("response")
					.body(body?.toResponseBody("application/json".toMediaType()))
					.build()
			}
			.build(),
		signer = GemRequestSigner { _, _, _, _ -> "signed" },
	)
}

private class RecordingChain(
	private val request: Request,
) : Interceptor.Chain {
	var proceededRequest: Request? = null

	override fun request(): Request = request

	override fun proceed(request: Request): Response {
		proceededRequest = request
		return Response.Builder()
			.request(request)
			.protocol(Protocol.HTTP_1_1)
			.code(200)
			.message("OK")
			.build()
	}

	override fun connection(): Connection? = null
	override fun call(): Call = error("not used")
	override fun connectTimeoutMillis(): Int = 0
	override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
	override fun readTimeoutMillis(): Int = 0
	override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
	override fun writeTimeoutMillis(): Int = 0
	override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
}
