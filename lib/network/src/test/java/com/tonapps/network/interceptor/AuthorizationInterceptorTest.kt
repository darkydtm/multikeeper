package com.tonapps.network.interceptor

import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthorizationInterceptorTest {

	@Test
	fun `adds bearer token only to allowed domains`() {
		val interceptor = AuthorizationInterceptor.bearer(
			token = { "tonapi-secret" },
			allowDomains = { listOf("https://keeper.tonapi.io", "https://rt.tonapi.io") },
		)

		val tonApiRequest = RecordingChain(
			Request.Builder().url("https://keeper.tonapi.io/v2/accounts").build(),
		)
		interceptor.intercept(tonApiRequest)

		val bridgeRequest = RecordingChain(
			Request.Builder().url("https://bridge.tonapi.io/bridge/events").build(),
		)
		interceptor.intercept(bridgeRequest)

		assertEquals("Bearer tonapi-secret", tonApiRequest.proceededRequest?.header("Authorization"))
		assertNull(bridgeRequest.proceededRequest?.header("Authorization"))
	}
}

private class RecordingChain(
	private val originalRequest: Request,
) : Interceptor.Chain {
	var proceededRequest: Request? = null

	override fun request(): Request = originalRequest

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
