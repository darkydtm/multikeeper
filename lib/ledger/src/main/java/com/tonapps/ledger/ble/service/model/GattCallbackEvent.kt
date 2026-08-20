package com.tonapps.ledger.ble.service.model

import android.bluetooth.BluetoothGattService

sealed class GattCallbackEvent {
    sealed interface GenerationAware {
        val generation: Long
    }

    sealed class ConnectionState(
        val status: Int,
        open override val generation: Long,
    ): GattCallbackEvent(), GenerationAware {
        data class Connected(
            val callbackStatus: Int,
            override val generation: Long,
        ): ConnectionState(callbackStatus, generation)
        data class Disconnected(
            val callbackStatus: Int,
            override val generation: Long,
        ): ConnectionState(callbackStatus, generation)
    }

    data class MtuNegociated(
        val mtuSize: Int,
        override val generation: Long,
    ): GattCallbackEvent(), GenerationAware

    data class ServicesDiscovered(
        val services: List<BluetoothGattService>,
        override val generation: Long,
    ): GattCallbackEvent(), GenerationAware

    data class CharacteristicChanged(
        val characteristicUuid: java.util.UUID,
        val value: ByteArray,
        override val generation: Long,
    ): GattCallbackEvent(), GenerationAware

    data class WriteDescriptorAck(
        val descriptorUuid: java.util.UUID?,
        val isSuccess: Boolean,
        override val generation: Long,
    ): GattCallbackEvent(), GenerationAware

    data class WriteCharacteristicAck(
        val characteristicUuid: java.util.UUID,
        val isSuccess: Boolean,
        val value: ByteArray,
        override val generation: Long,
    ): GattCallbackEvent(), GenerationAware
}
