package com.tonapps.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun redactUrl(url: HttpUrl): String {
	val builder = url.newBuilder()
		.username("")
		.password("")
		.fragment(null)

	url.queryParameterNames.forEach { name ->
		val values = url.queryParameterValues(name)
		builder.removeAllQueryParameters(name)
		values.forEach { builder.addQueryParameter(name, "<redacted>") }
	}

	return builder.build().toString()
}

internal fun redactUrl(url: String): String =
	url.toHttpUrlOrNull()?.let(::redactUrl) ?: "<invalid-url>"
