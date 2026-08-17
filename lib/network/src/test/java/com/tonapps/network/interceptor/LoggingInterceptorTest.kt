package com.tonapps.network.interceptor

import okhttp3.Call
import okhttp3.Connection
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class LoggingInterceptorTest {

	private val enabledDelegate = object : LoggingInterceptor.Delegate {
		override fun isEnabled(): Boolean = true
	}

	@Test
	fun `redacts URL credentials fragments and query values`() {
		val url = "https://user:password@example.com/path?api_key=key&safe=value#fragment".toHttpUrl()

		assertEquals(
			"https://example.com/path?api_key=%3Credacted%3E&safe=%3Credacted%3E",
			redactUrl(url)
		)
	}

	@Test
	fun `redacts sensitive headers case insensitively`() {
		listOf(
			"Authorization",
			"AUTHENTICATION",
			"X-Auth",
			"proxy-authorization",
			"COOKIE",
			"Set-Cookie",
			"X-Credential",
			"X-Password",
			"X-Session-Id",
			"X-Authorization",
			"X-TonConnect-Auth",
			"X-Api-Key",
			"access_token",
			"client-secret",
		).forEach { assertTrue(isSensitiveHeader(it)) }

		assertFalse(isSensitiveHeader("Content-Type"))
		assertFalse(isSensitiveHeader("X-Request-Id"))
	}

	@Test
	fun `does not retain query values in redacted URL`() {
		val url = "https://example.com/path?TOKEN=credential&safe=public&safe=second".toHttpUrl()

		val redacted = redactUrl(url)

		assertFalse(redacted.contains("credential"))
		assertFalse(redacted.contains("public"))
		assertFalse(redacted.contains("second"))
		assertNotEquals(url.toString(), redacted)
	}

	@Test
	fun `does not include exception message in network diagnostic`() {
		val diagnostic = formatNetworkError(
			requestId = 1,
			url = "https://example.com".toHttpUrl(),
			throwable = IllegalStateException("Bearer credential=secret"),
		).joinToString("\n")

		assertFalse(diagnostic.contains("Bearer credential=secret"))
		assertTrue(diagnostic.contains("network request failed"))
	}

	@Test
	fun `logs redacted request and response details without bodies`() {
		val secret = "secret-value"
		val request = Request.Builder()
			.url("https://user:password@example.com/path?token=$secret&safe=public#fragment")
			.header("Authorization", "Bearer $secret")
			.header("X-Request-Id", "request-id")
			.post("request body $secret".toRequestBody("text/plain".toMediaType()))
			.build()
		val response = Response.Builder()
			.request(request)
			.protocol(Protocol.HTTP_1_1)
			.code(200)
			.message("OK")
			.header("Set-Cookie", "session=$secret")
			.header("X-Response", "response")
			.body("response body $secret".toResponseBody("text/plain".toMediaType()))
			.build()
		val logs = mutableListOf<Pair<Boolean, String>>()

		LoggingInterceptor(
			delegate = enabledDelegate,
			logger = { isError, message -> logs += isError to message },
			now = { 1L },
		).intercept(RecordingChain(request, response))

		val output = logs.joinToString("\n") { it.second }
		assertTrue(output.contains("POST https://example.com/path?token=%3Credacted%3E&safe=%3Credacted%3E"))
		assertTrue(output.contains("Authorization: <hidden>"))
		assertTrue(output.contains("X-Request-Id: request-id"))
		assertTrue(output.contains("Request body: <hidden>"))
		assertTrue(output.contains("Set-Cookie: <hidden>"))
		assertTrue(output.contains("X-Response: response"))
		assertTrue(output.contains("<body hidden>"))
		assertFalse(output.contains(secret))
		assertFalse(logs.any { it.first })
	}

	@Test
	fun `logs empty markers for requests and responses without bodies`() {
		val request = Request.Builder()
			.url("https://example.com/empty")
			.build()
		val response = Response.Builder()
			.request(request)
			.protocol(Protocol.HTTP_1_1)
			.code(204)
			.message("No Content")
			.build()
		val logs = mutableListOf<String>()

		LoggingInterceptor(
			delegate = enabledDelegate,
			logger = { _, message -> logs += message },
			now = { 1L },
		).intercept(RecordingChain(request, response))

		val output = logs.joinToString("\n")
		assertTrue(output.contains("<empty>"))
		assertTrue(output.contains("Response body:\n<empty>"))
	}

	@Test
	fun `logs sanitized error and rethrows network failure`() {
		val secret = "credential=secret"
		val request = Request.Builder()
			.url("https://example.com/path?token=$secret")
			.build()
		val logs = mutableListOf<Pair<Boolean, String>>()

		assertThrows(IllegalStateException::class.java) {
			LoggingInterceptor(
				delegate = enabledDelegate,
				logger = { isError, message -> logs += isError to message },
				now = { 1L },
			).intercept(RecordingChain(request, failure = IllegalStateException(secret)))
		}

		val error = logs.single { it.first }.second
		assertTrue(error.contains("https://example.com/path?token=%3Credacted%3E"))
		assertTrue(error.contains("network request failed"))
		assertFalse(error.contains(secret))
	}

	private class RecordingChain(
		private val request: Request,
		private val response: Response? = null,
		private val failure: Throwable? = null,
	) : Interceptor.Chain {
		override fun request(): Request = request

		override fun proceed(request: Request): Response {
			failure?.let { throw it }
			return response!!.newBuilder().request(request).build()
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
}
