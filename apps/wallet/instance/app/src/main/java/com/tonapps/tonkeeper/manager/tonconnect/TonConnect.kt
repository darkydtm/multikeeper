package com.tonapps.tonkeeper.manager.tonconnect

import android.net.Uri
import android.os.Parcelable
import androidx.core.net.toUri
import com.tonapps.security.Security
import com.tonapps.security.hex
import kotlinx.parcelize.Parcelize

@Parcelize
data class TonConnect(
    val clientId: String,
    val request: ConnectRequest,
    val returnUri: Uri?,
    val fromQR: Boolean,
    val jsInject: Boolean,
    val origin: Uri?,
    val fromPackageName: String?
): Parcelable {

    val proofPayload: String?
        get() = request.proofPayload

    val manifestUrl: String
        get() = request.manifestUrl

    companion object {

        fun fromJsInject(
            request: ConnectRequest,
            webViewUri: Uri?,
        ): TonConnect {
            return TonConnect(
                clientId = hex(Security.randomBytes(16)),
                request = request,
                returnUri = null,
                fromQR = false,
                jsInject = true,
                origin = webViewUri,
                fromPackageName = null
            )
        }

        private fun isValidClientId(clientId: String?): Boolean {
            return !clientId.isNullOrBlank() && clientId.length == 64
        }

        fun parseReturn(
            value: String?,
            refSource: Uri?
        ): Uri? {
            val uri = if (value.isNullOrBlank() || value.equals("back", ignoreCase = true)) {
                refSource
            } else if (value.equals("none", ignoreCase = true)) {
                null
            } else {
                try {
                    value.toUri()
                } catch (e: Exception) {
                    throw TonConnectException.ReturnParsingError(value)
                }
            }
            return uri?.takeIf(::isSafeReturnUri)
        }

        fun isSafeReturnUri(uri: Uri?): Boolean {
            if (uri == null) {
                return false
            }

            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            return when {
                scheme == "tg" -> host == "resolve" && uri.userInfo == null && uri.port == -1 &&
                    uri.authority.equals(host, ignoreCase = true)
                scheme == "https" && host == "t.me" -> uri.userInfo == null &&
                    uri.port == -1 && uri.authority.equals(host, ignoreCase = true) &&
                    !uri.path.isNullOrBlank()
                scheme == "tc" -> host == "callback" && uri.userInfo == null && uri.port == -1 &&
                    uri.authority.equals(host, ignoreCase = true) && uri.path.isNullOrEmpty() &&
                    uri.query == null && uri.fragment == null
                else -> false
            }
        }

        fun parse(
            uri: Uri,
            refSource: Uri?,
            fromQR: Boolean,
            returnUri: Uri?,
            fromPackageName: String?
        ): TonConnect {
            val version = uri.getQueryParameter("v")?.toIntOrNull() ?: 0
            if (version != 2) {
                throw TonConnectException.UnsupportedVersion(version)
            }

            val clientId = uri.getQueryParameter("id")
            if (!isValidClientId(clientId)) {
                throw TonConnectException.WrongClientId(clientId)
            }

            val request = ConnectRequest.parse(uri.getQueryParameter("r"))

            return TonConnect(
                clientId = clientId!!,
                request = request,
                returnUri = returnUri,
                fromQR = fromQR,
                jsInject = false,
                origin = refSource,
                fromPackageName = fromPackageName
            )
        }
    }
}
