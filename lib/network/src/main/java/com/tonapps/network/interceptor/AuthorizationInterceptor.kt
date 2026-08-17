package com.tonapps.network.interceptor

import com.tonapps.network.HttpsOrigin
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.HttpUrl
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

    private val origins: Set<HttpsOrigin>
        get() = allowDomains()
            .mapNotNull(HttpsOrigin::parse)
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
        val trustedOrigins = origins
        val authorization = if (isTrustedHttps(original.url, trustedOrigins)) {
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
            if (redirectedUrl != null && !isTrustedHttps(redirectedUrl, trustedOrigins)) {
                response.close()
                throw IOException("Refusing to redirect an authorized request to an untrusted URL")
            }
        }

        return response
    }

    private fun isTrustedHttps(url: HttpUrl, trustedOrigins: Set<HttpsOrigin>): Boolean {
        return trustedOrigins.any { it.matches(url) }
    }

    companion object {
        fun bearer(
            token: () -> String,
            allowDomains: () -> List<String>
        ) = AuthorizationInterceptor(Type.BEARER, token, allowDomains)
    }
}
