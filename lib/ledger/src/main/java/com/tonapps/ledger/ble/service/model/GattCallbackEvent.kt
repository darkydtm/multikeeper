package com.tonapps.ledger.ble.service.model

import android.bluetooth.BluetoothGattService

sealed class GattCallbackEvent {
    sealed interface GenerationAware {
        val generation: Long
    }

    sealed class ConnectionState(
        override val generation: Long,
    ): GattCallbackEvent(), GenerationAware {
        data class Connected(override val generation: Long): ConnectionState(generation)
        data class Disconnected(override val generation: Long): ConnectionState(generation)
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
        override val generation: Long,
    ): GattCallbackEvent(), GenerationAware
}
