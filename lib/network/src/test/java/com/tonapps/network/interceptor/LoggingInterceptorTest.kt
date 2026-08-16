package com.tonapps.network.interceptor

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
			"proxy-authorization",
			"COOKIE",
			"Set-Cookie",
			"X-Authorization",
			"X-TonConnect-Auth",
			"X-Api-Key",
			"access_token",
			"client-secret",
		).forEach { assertTrue(isSensitiveHeader(it)) }

		assertFalse(isSensitiveHeader("Content-Type"))
		assertFalse(isSensitiveHeader("X-Request-Id"))
	}
}
