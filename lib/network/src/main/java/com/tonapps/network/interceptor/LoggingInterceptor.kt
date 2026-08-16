package com.tonapps.network.interceptor

import android.os.SystemClock
import com.tonapps.log.L
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.http.promisesBody
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class LoggingInterceptor(
    private val delegate: Delegate
) : Interceptor {

    interface Delegate {
        fun isEnabled(): Boolean
    }

    private val requestIdGenerator = AtomicInteger(1)

    private val prefixer = LoggingPrefixer()
    private val prefix = ThreadLocal<String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        if (!delegate.isEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()

        return runBlocking {
            prefix.set(prefixer.getPrefix())
            val requestId = requestIdGenerator.getAndIncrement()
            val response = interceptWithDetailedLog(requestId, request, chain)

            response
        }
    }

    private fun interceptWithDetailedLog(requestId: Int, request: Request, chain: Interceptor.Chain): Response {
        val requestLog = mutableListOf<String>()
        requestLog.add("----> [$requestId] =============== Request ===============")
        requestLog.add("${request.method} ${redactUrl(request.url)}")

        if (request.headers.size > 0) {
            request.headers.forEach { (header, values) ->
                if (isSensitiveHeader(header)) {
                    requestLog.add("$header: <hidden>")
                } else {
                    requestLog.add("$header: $values")
                }
            }
        }

        when (request.body) {
            null -> requestLog.add("<empty>")
            else -> requestLog.add("Request body: <hidden>")
        }

        requestLog.add("----> [$requestId] End of request")
        netLog(requestLog)

        try {
            val timeStartMs = SystemClock.elapsedRealtime()
            val response = chain.proceed(request)
            val timeEndMs = SystemClock.elapsedRealtime()
            val duration = timeEndMs - timeStartMs

            val responseLog = mutableListOf<String>()
            responseLog.add("<---- [$requestId] =============== Response ===============")
            responseLog.add("${response.code} ${response.message} ${redactUrl(request.url)} (${duration}ms)")

            if (response.headers.size > 0) {
                response.headers.forEach { (header, values) ->
                    if (isSensitiveHeader(header)) {
                        responseLog.add("$header: <hidden>")
                    } else {
                        responseLog.add("$header: $values")
                    }
                }
            }

            responseLog.add("")
            responseLog.add("Response body:")

            val isGzip = "gzip".equals(response.header("content-encoding"), ignoreCase = true)
            val isStreaming = "text/event-stream".equals(
                response.header("content-type")?.substringBefore(';'),
                ignoreCase = true
            )
            if (isStreaming) {
                responseLog.add("<streaming: text/event-stream>")
            } else if (response.promisesBody() && isGzip) {
                responseLog.add("<compressed body>")
            } else if (response.promisesBody()) {
                responseLog.add("<body hidden>")
            } else {
                responseLog.add("<empty>")
            }

            responseLog.add("<---- [$requestId] End of Response")
            netLog(responseLog)

            return response
        } catch (th: Throwable) {
            logError(requestId, request, th)
            throw th
        }
    }

    private fun logError(requestId: Int, request: Request, th: Throwable) {
        val responseLog = mutableListOf<String>().apply {
            add("<---- [$requestId] Response")
            add(redactUrl(request.url))
            add("${th::class.java.name}: network request failed")
            addAll(th.stackTrace.map { "\tat $it" })
            add("<---- [$requestId] End of Response")
        }
        netErr(responseLog)
    }

    private fun netLog(lines: List<String>) {
        val log = lines.joinToString("\n")
        if (log.isNotBlank()) L.d("NetLog", "${prefix.get()} ${log.trimEnd()}")
    }

    private fun netErr(lines: List<String>) {
        val log = lines.joinToString("\n")
        if (log.isNotBlank()) L.e("NetLog", "${prefix.get()} ${log.trimEnd()}")
    }

    private class LoggingPrefixer {

        private val startEmoji: Int = 129292 // 🤌
        private val endEmoji: Int = 129535 // 🧾
        private var lastUsedEmoji = startEmoji

        private val mutex = Mutex()

        suspend fun getPrefix(): String {
            mutex.withLock {
                if (lastUsedEmoji > endEmoji) {
                    lastUsedEmoji = startEmoji
                }
                return String(Character.toChars(lastUsedEmoji++))
            }
        }
    }
}

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

internal fun isSensitiveHeader(header: String): Boolean {
    val normalized = header.lowercase(Locale.US)
    val compact = normalized.replace("-", "").replace("_", "")
    return compact in setOf(
        "authorization",
        "proxyauthorization",
        "cookie",
        "setcookie",
        "xauthorization",
        "xtonconnectauth",
    ) || compact.contains("apikey") || compact.contains("token") || compact.contains("secret")
}
