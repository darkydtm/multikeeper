package com.tonapps.tonkeeper.manager.tonconnect.bridge.model

import android.os.Parcelable
import com.tonapps.base64.decodeBase64
import com.tonapps.base64.encodeBase64
import com.tonapps.blockchain.ton.extensions.cellFromBase64
import com.tonapps.tonkeeper.core.DevSettings
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import org.json.JSONObject
import org.ton.tlb.AbstractTlbConstructor

abstract class SignDataRequestPayload(val type: String): Parcelable {

    abstract fun toJSON(): JSONObject

    companion object {

        fun parse(value: String): SignDataRequestPayload? {
            return try {
                parse(JSONObject(value))
            } catch (e: Throwable) {
                DevSettings.tonConnectLog("Failed to parse sign-data payload (${e::class.simpleName})", error = true)
                null
            }
        }

        fun parse(json: JSONObject): SignDataRequestPayload {
            return when (val type = json.getString("type")) {
                "text" -> Text(json)
                "binary" -> Binary(json)
                "cell" -> Cell(json)
                else -> throw IllegalArgumentException("Unknown type: $type")
            }
        }
    }

    @Parcelize
    data class Text(val layout: String?, val text: String): SignDataRequestPayload("text") {

        constructor(json: JSONObject) : this(
            layout = json.optString("layout"),
            text = json.getString("text")
        )

        override fun toJSON() = JSONObject().apply {
            put("type", type)
            if (!layout.isNullOrBlank()) {
                put("layout", layout)
            }
            put("text", text)
        }
    }

    @Parcelize
    data class Binary(val bytesBase64: String): SignDataRequestPayload("binary") {

        @IgnoredOnParcel
        val bytes: ByteArray by lazy {
            bytesBase64.decodeBase64()
        }

        constructor(json: JSONObject): this(json.getString("bytes"))

        override fun toJSON() = JSONObject().apply {
            put("type", type)
            put("bytes", bytes.encodeBase64())
        }
    }

    @Parcelize
    data class Cell(val schema: String, val cellBase64: String): SignDataRequestPayload("cell") {

        @IgnoredOnParcel
        val value: org.ton.cell.Cell by lazy {
            cellBase64.cellFromBase64()
        }

        @IgnoredOnParcel
        val formatSchema: String by lazy {
            AbstractTlbConstructor.Companion.formatSchema(schema)
        }

        constructor(json: JSONObject): this(
            schema = json.getString("schema"),
            cellBase64 = json.getString("cell")
        )

        fun print(): String {
            /*val ref = CellRef(cell = value, AnyTlbConstructor)

            return ref.print().toString()*/

            /*val tlbConstructor = object : TlbConstructor<org.ton.cell.Cell>(schema = schema) {

            }
            val printer = TlbPrettyPrinter()
            printer.type(tlbConstructor)
            return printer.toString()*/
            return value.toString()
        }

        override fun toJSON() = JSONObject().apply {
            put("type", type)
            put("schema", schema)
            put("cell", cellBase64)
        }
    }
}
