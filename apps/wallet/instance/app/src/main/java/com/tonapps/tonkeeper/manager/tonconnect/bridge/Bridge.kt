package com.tonapps.tonkeeper.manager.tonconnect.bridge

import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.tonapps.base64.encodeBase64
import com.tonapps.blockchain.ton.connect.TONProof
import com.tonapps.extensions.optStringCompatJS
import com.tonapps.security.CryptoBox
import com.tonapps.security.hex
import com.tonapps.tonkeeper.core.DevSettings
import com.tonapps.tonkeeper.manager.tonconnect.bridge.model.BridgeError
import com.tonapps.tonkeeper.manager.tonconnect.bridge.model.BridgeEvent
import com.tonapps.tonkeeper.manager.tonconnect.bridge.model.SignDataRequestPayload
import com.tonapps.wallet.api.API
import com.tonapps.wallet.data.dapps.entities.AppConnectEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext

internal class Bridge(private val api: API) {

    suspend fun sendDisconnectResponseSuccess(
        connection: AppConnectEntity,
        id: Long
    ): String {
        val message = JsonBuilder.responseDisconnect(id).toString()
        DevSettings.tonConnectLog("Sent disconnect response for request $id")
        send(connection, message)
        return message
    }

    suspend fun sendSignDataResponseSuccess(
        connection: AppConnectEntity,
        proof: TONProof.Result,
        address: String,
        payload: SignDataRequestPayload,
        id: Long,
    ): String {
        val message = JsonBuilder.responseSignData(id, proof, address, payload).toString()
        DevSettings.tonConnectLog("Sent sign-data response for request $id")
        send(connection, message)
        return message
    }

    suspend fun sendTransactionResponseSuccess(
        connection: AppConnectEntity,
        boc: String,
        id: Long
    ): String {
        val message = JsonBuilder.responseSendTransaction(id, boc).toString()
        DevSettings.tonConnectLog("Sent transaction response for request $id")
        send(connection, message)
        return message
    }

    suspend fun sendError(
        connection: AppConnectEntity,
        error: BridgeError,
        id: Long,
    ): String {
        val message = JsonBuilder.responseError(id, error).toString()
        DevSettings.tonConnectLog("Sent error response for request ${id} (code ${error.code})", error = true)
        send(connection, message)
        return message
    }

    suspend fun sendDisconnect(connection: AppConnectEntity): String {
        val message = JsonBuilder.disconnectEvent().toString()
        DevSettings.tonConnectLog("Sent disconnect event")
        send(connection, message)
        return message
    }

    suspend fun send(
        connection: AppConnectEntity,
        message: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (connection.type != AppConnectEntity.Type.Internal) {
            send(connection.clientId, connection.keyPair, message)
        } else {
            true
        }
    }

    suspend fun send(
        clientId: String,
        keyPair: CryptoBox.KeyPair,
        unencryptedMessage: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val encryptedMessage = AppConnectEntity.encryptMessage(clientId.hex(), keyPair.privateKey, unencryptedMessage.toByteArray())
            api.tonconnectSend(
                publicKeyHex = hex(keyPair.publicKey),
                clientId = clientId,
                body = encryptedMessage.encodeBase64()
            )
            true
        } catch (e: Throwable) {
            DevSettings.tonConnectLog("Failed to send TonConnect message (${e::class.simpleName})", error = true)
            FirebaseCrashlytics.getInstance().recordException(e)
            false
        }
    }

    fun eventsFlow(
        connections: List<AppConnectEntity>,
        lastEventId: Long,
    ): Flow<BridgeEvent> {
        DevSettings.tonConnectLog("Started listening for bridge events from ${connections.size} connections at event $lastEventId")
        val publicKeys = connections.map { it.publicKeyHex }
        return api.tonconnectEvents(publicKeys, lastEventId, onFailure = null)
            .mapNotNull { event ->
                DevSettings.tonConnectLog("Received bridge event ${event.id}")
                val from = event.json.optStringCompatJS("from") ?: throw BridgeException(
                    message = "Event \"from\" is missing"
                )
                val connection = connections.find { it.clientId == from } ?: throw BridgeException(
                    message = "Connection not found"
                )
                val id = event.id?.toLongOrNull() ?: throw BridgeException(message = "Event \"id\" is missing")
                val message = event.json.optStringCompatJS("message") ?: throw BridgeException(
                    connect = connection,
                    message = "Field \"message\" is required"
                )
                val json = try {
                    connection.decryptEventMessage(message)
                } catch (e: Throwable) {
                    throw BridgeException(
                        connect = connection,
                        cause = e,
                        message = "Failed to decrypt event from \"message\" field"
                    )
                }
                val decryptedMessage = try {
                    BridgeEvent.Message(json)
                } catch (e: Throwable) {
                    throw BridgeException(
                        connect = connection,
                        cause = e
                    )
                }
                DevSettings.tonConnectLog("Parsed bridge message ${decryptedMessage.method} with id ${decryptedMessage.id}")
                BridgeEvent(
                    eventId = id,
                    message = decryptedMessage,
                    connection = connection.copy(),
                )
            }
            .catch {
                DevSettings.tonConnectLog("Failed processing bridge event (${it::class.simpleName})", error = true)
                FirebaseCrashlytics.getInstance().recordException(it)
                val connect = (it as? BridgeException)?.connect ?: return@catch
                sendError(connect, BridgeError.badRequest(it.message), 0)
            }
            .flowOn(Dispatchers.IO)
    }
}
