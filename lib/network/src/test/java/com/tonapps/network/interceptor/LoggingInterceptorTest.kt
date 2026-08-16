package com.tonapps.network.interceptor

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoggingInterceptorTest {

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
}
