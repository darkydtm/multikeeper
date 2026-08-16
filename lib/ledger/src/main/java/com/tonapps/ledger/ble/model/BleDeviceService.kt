package com.tonapps.ledger.ble.model

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import java.util.*

data class BleDeviceService(
    val uuid: UUID,
    val writeCharacteristic: BluetoothGattCharacteristic,
    val writeNoAnswerCharacteristic: BluetoothGattCharacteristic?,
    val notifyCharacteristic: BluetoothGattCharacteristic
) {
    class Builder(private val uuid: UUID) {
        private lateinit var writeCharacteristic: BluetoothGattCharacteristic
        private var writeNoAnswerCharacteristic: BluetoothGattCharacteristic? = null
        private lateinit var notifyCharacteristic: BluetoothGattCharacteristic

        fun setWriteCharacteristic(characteristic: BluetoothGattCharacteristic): Builder {
            writeCharacteristic = characteristic
            return this
        }

        fun setWriteNoAnswerCharacteristic(characteristic: BluetoothGattCharacteristic): Builder {
            writeNoAnswerCharacteristic = characteristic
            return this
        }

        fun setNotifyCharacteristic(characteristic: BluetoothGattCharacteristic): Builder {
            notifyCharacteristic = characteristic
            return this
        }

        fun build(): BleDeviceService {
            check(::writeCharacteristic.isInitialized && ::notifyCharacteristic.isInitialized) {
                "Required BLE characteristics are missing"
            }
            check(writeCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                "BLE write characteristic does not support write"
            }
            check(notifyCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                "BLE notify characteristic does not support notifications"
            }
            check(notifyCharacteristic.descriptors.any {
                it.uuid == BluetoothGattDescriptor.UUID_CLIENT_CHARACTERISTIC_CONFIG
            }) {
                "BLE notify characteristic has no client configuration descriptor"
            }
            writeNoAnswerCharacteristic?.let {
                check(it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                    "BLE command characteristic does not support write without response"
                }
            }
            return BleDeviceService(
                uuid = uuid,
                writeCharacteristic = writeCharacteristic,
                writeNoAnswerCharacteristic = writeNoAnswerCharacteristic,
                notifyCharacteristic = notifyCharacteristic,
            )
        }
    }
}
