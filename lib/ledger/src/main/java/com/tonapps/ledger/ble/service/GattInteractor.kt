package com.tonapps.ledger.ble.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import com.tonapps.ledger.ble.extension.fromHexStringToBytes
import com.tonapps.ledger.ble.model.CLIENT_CHARACTERISTIC_CONFIG_UUID
import com.tonapps.ledger.ble.model.BleDeviceService
import com.tonapps.log.L

@SuppressLint("MissingPermission")
class GattInteractor(val gatt: BluetoothGatt) {

    init {
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    }

    fun discoverService(): Boolean {
        L.d("Try discover services")
        return gatt.discoverServices()
    }

    fun enableNotification(deviceService: BleDeviceService): Boolean {
        L.d("Enable Notification")
        val notificationEnabled = gatt.setCharacteristicNotification(deviceService.notifyCharacteristic, true)
        val descriptor = deviceService.notifyCharacteristic.descriptors.firstOrNull {
            it.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID
        } ?: return false
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return notificationEnabled && gatt.writeDescriptor(descriptor)
    }

    fun negotiateMtu(): Boolean {
        L.d("Megociate MTU")
        return gatt.requestMtu(MAX_MTU_VALUE)
    }

    fun askMtu(deviceService: BleDeviceService): Boolean {
        L.d("Ask MTU size")
        deviceService.writeCharacteristic.value = BleService.MTU_HANDSHAKE_COMMAND.fromHexStringToBytes()
        return gatt.writeCharacteristic(deviceService.writeCharacteristic)
    }

    fun sendBytes(deviceService: BleDeviceService, bytes: ByteArray): WriteResult {
        deviceService.let {
            if (it.writeNoAnswerCharacteristic != null) {
                it.writeNoAnswerCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                it.writeNoAnswerCharacteristic.value = bytes
                return if (gatt.writeCharacteristic(it.writeNoAnswerCharacteristic)) {
                    WriteResult.Sent
                } else {
                    WriteResult.Failed
                }
            } else {
                it.writeCharacteristic.value = bytes
                return if (gatt.writeCharacteristic(it.writeCharacteristic)) {
                    WriteResult.AwaitingCallback
                } else {
                    WriteResult.Failed
                }
            }
        }
    }

    enum class WriteResult {
        Failed,
        AwaitingCallback,
        Sent,
    }

    companion object{
        private const val MAX_MTU_VALUE = 512
        const val GATT_HEADER_SIZE = 3
    }
}
