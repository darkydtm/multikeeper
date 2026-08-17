package com.tonapps.network.interceptor

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response

class AuthorizationInterceptor(
    private val type: Type = Type.NONE,
    private val token: () -> String,
    private val allowDomains: () -> List<String>
): Interceptor {

    enum class Type {
        NONE,
        BASIC,
        BEARER
    }

    private val domains: Set<String>
        get() = allowDomains()
            .mapNotNull { it.toHttpUrlOrNull() }
            .filter { it.isHttps }
            .map { it.host }
            .toSet()

    private val headerValue: String?
        get() {
            val token = token()
            if (token.isBlank()) {
                return null
            }
            return when(type) {
                Type.BEARER -> "Bearer $token"
                Type.BASIC -> "Basic $token"
                else -> token
            }
        }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val trustedDomains = domains
        val authorization = if (isTrustedHttps(original.url, trustedDomains)) {
            headerValue
        } else {
            null
        }

        val request = authorization?.let {
            original.newBuilder()
                .header("Authorization", it)
                .method(original.method, original.body)
                .build()
        } ?: original.newBuilder()
            .removeHeader("Authorization")
            .build()

        val response = chain.proceed(request)
        if (authorization != null && response.isRedirect) {
            val location = response.header("Location")
            val redirectedUrl = location?.let { response.request.url.resolve(it) }
            if (redirectedUrl != null && !isTrustedHttps(redirectedUrl, trustedDomains)) {
                response.close()
                throw IOException("Refusing to redirect an authorized request to an untrusted URL")
            }
        }

        return response
    }

    private fun isTrustedHttps(url: HttpUrl, trustedDomains: Set<String>): Boolean {
        return url.isHttps && trustedDomains.contains(url.host)
    }

    companion object {
        fun bearer(
            token: () -> String,
            allowDomains: () -> List<String>
        ) = AuthorizationInterceptor(Type.BEARER, token, allowDomains)
    }
}
