package com.tonapps.tonkeeper.manager.tonconnect

sealed class TonConnectException(message: String): Exception(message) {

    data class UnsupportedVersion(
        val version: Int
    ): TonConnectException("Unsupported TonConnect version: $version")

    data class WrongClientId(
        val clientId: String?
    ): TonConnectException("Wrong clientId")

    data class RequestParsingError(
        val data: String?
    ): TonConnectException("Invalid ConnectRequest data")

    data class ReturnParsingError(
        val data: String?
    ): TonConnectException("Invalid return data")
}
