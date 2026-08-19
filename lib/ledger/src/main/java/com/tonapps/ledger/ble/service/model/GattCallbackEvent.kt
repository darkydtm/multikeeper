package com.tonapps.ledger.ble.service.model

import android.bluetooth.BluetoothGattService

sealed class GattCallbackEvent {
    sealed class ConnectionState(open val generation: Long): GattCallbackEvent() {
        data class Connected(override val generation: Long): ConnectionState(generation)
        data class Disconnected(override val generation: Long): ConnectionState(generation)
    }

    data class MtuNegociated(
        val mtuSize: Int,
        val generation: Long,
    ): GattCallbackEvent()

    data class ServicesDiscovered(
        val services: List<BluetoothGattService>,
        val generation: Long,
    ): GattCallbackEvent()

    data class CharacteristicChanged(
        val characteristicUuid: java.util.UUID,
        val value: ByteArray,
        val generation: Long,
    ): GattCallbackEvent()

    data class WriteDescriptorAck(
        val descriptorUuid: java.util.UUID?,
        val isSuccess: Boolean,
        val generation: Long,
    ): GattCallbackEvent()

    data class WriteCharacteristicAck(
        val characteristicUuid: java.util.UUID,
        val isSuccess: Boolean,
        val generation: Long,
    ): GattCallbackEvent()
}
