package com.tonapps.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class HttpsOrigin private constructor(
	private val host: String,
) {
	fun matches(url: HttpUrl): Boolean {
		return url.isHttps && url.port == DEFAULT_PORT && url.host == host
	}

	companion object {
		private const val DEFAULT_PORT = 443

		fun parse(value: String): HttpsOrigin? {
			val url = value.toHttpUrlOrNull() ?: return null
			return url.takeIf {
				it.isHttps && it.port == DEFAULT_PORT && it.username.isEmpty() && it.password.isEmpty()
			}?.let { HttpsOrigin(it.host) }
		}

		fun matches(url: HttpUrl, allowedOrigins: Collection<String>): Boolean {
			return allowedOrigins.asSequence()
				.mapNotNull(::parse)
				.any { it.matches(url) }
		}
	}
}
