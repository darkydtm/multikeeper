package com.tonapps.network.interceptor

import com.tonapps.network.HttpsOrigin
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class AuthorizationInterceptorTest {

	@Test
	fun `matches only HTTPS origins on the default port`() {
		assertEquals(
			true,
			HttpsOrigin.matches(
				Request.Builder().url("https://api.example.com").build().url,
				listOf("https://api.example.com"),
			),
		)
		assertEquals(
			true,
			HttpsOrigin.matches(
				Request.Builder().url("https://api.example.com:443").build().url,
				listOf("https://api.example.com"),
			),
		)
		assertEquals(
			false,
			HttpsOrigin.matches(
				Request.Builder().url("https://api.example.com:8443").build().url,
				listOf("https://api.example.com"),
			),
		)
	}

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

	@Test
	fun `does not attach credentials for empty or invalid allowlists`() {
		listOf(emptyList(), listOf("http://api.example.com", "not a URL")).forEach { allowlist ->
			val chain = RecordingChain(
				Request.Builder()
					.url("https://api.example.com")
					.header("Authorization", "Bearer existing")
					.build(),
			)

			AuthorizationInterceptor.bearer(
				token = { "secret" },
				allowDomains = { allowlist },
			).intercept(chain)

			assertNull(chain.proceededRequest?.header("Authorization"))
		}
	}

	@Test
	fun `attaches credentials only to trusted HTTPS hosts`() {
		val interceptor = AuthorizationInterceptor.bearer(
			token = { "secret" },
			allowDomains = { listOf("https://api.example.com") },
		)

		val httpsChain = RecordingChain(Request.Builder().url("https://api.example.com").build())
		interceptor.intercept(httpsChain)
		assertEquals("Bearer secret", httpsChain.proceededRequest?.header("Authorization"))

		val httpChain = RecordingChain(
			Request.Builder()
				.url("http://api.example.com")
				.header("Authorization", "Bearer existing")
				.build(),
		)
		interceptor.intercept(httpChain)
		assertNull(httpChain.proceededRequest?.header("Authorization"))
	}

	@Test
	fun `does not attach credentials to a trusted host alternate port`() {
		val chain = RecordingChain(
			Request.Builder().url("https://api.example.com:8443").build(),
		)

		AuthorizationInterceptor.bearer(
			token = { "secret" },
			allowDomains = { listOf("https://api.example.com") },
		).intercept(chain)

		assertNull(chain.proceededRequest?.header("Authorization"))
	}

	@Test
	fun `attaches credentials to a trusted host on the standard HTTPS port`() {
		val chain = RecordingChain(
			Request.Builder().url("https://api.example.com:443").build(),
		)

		AuthorizationInterceptor.bearer(
			token = { "secret" },
			allowDomains = { listOf("https://api.example.com") },
		).intercept(chain)

		assertEquals("Bearer secret", chain.proceededRequest?.header("Authorization"))
	}

	@Test
	fun `blocks HTTPS redirect to HTTP for authorized requests`() {
		assertUnsafeRedirectIsBlocked("http://api.example.com/next")
	}

	@Test
	fun `blocks HTTPS redirect to an untrusted host for authorized requests`() {
		assertUnsafeRedirectIsBlocked("https://evil.example.com/next")
	}

	@Test
	fun `does not reject redirects when credentials are not attached`() {
		val chain = RecordingChain(
			request = Request.Builder().url("https://api.example.com").build(),
			response = { request ->
				Response.Builder()
					.request(request)
					.protocol(Protocol.HTTP_1_1)
					.code(302)
					.message("Found")
					.header("Location", "http://api.example.com/next")
					.build()
			},
		)

		val response = AuthorizationInterceptor.bearer(
			token = { "secret" },
			allowDomains = { emptyList() },
		).intercept(chain)

		assertEquals(302, response.code)
	}

	private fun assertUnsafeRedirectIsBlocked(location: String) {
		val chain = RecordingChain(
			request = Request.Builder().url("https://api.example.com").build(),
			response = { request ->
				Response.Builder()
					.request(request)
					.protocol(Protocol.HTTP_1_1)
					.code(302)
					.message("Found")
					.header("Location", location)
					.build()
			},
		)

		try {
			AuthorizationInterceptor.bearer(
				token = { "secret" },
				allowDomains = { listOf("https://api.example.com") },
			).intercept(chain)
			fail("Expected unsafe redirect to be rejected")
		} catch (_: IOException) {
		}
	}
}

private class RecordingChain(
	private val request: Request,
	private val response: (Request) -> Response = { currentRequest ->
		Response.Builder()
			.request(currentRequest)
			.protocol(Protocol.HTTP_1_1)
			.code(200)
			.message("OK")
			.build()
	},
) : Interceptor.Chain {
	var proceededRequest: Request? = null

	override fun request(): Request = request

	override fun proceed(request: Request): Response {
		proceededRequest = request
		return response(request)
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
