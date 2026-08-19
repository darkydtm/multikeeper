package com.tonapps.ledger.ble.service

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import com.tonapps.ledger.ble.extension.toHexString
import com.tonapps.ledger.ble.service.model.GattCallbackEvent
import com.tonapps.log.L
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking

class BleGattCallbackFlow : BluetoothGattCallback() {

    private val _gattFlow =
        MutableSharedFlow<GattCallbackEvent>(replay = 0, extraBufferCapacity = 0)
    val gattFlow: Flow<GattCallbackEvent>
        get() = _gattFlow

    @Volatile
    private var hasDiscoveredService: Boolean = false
    private var deviceAddress: String? = null
    private var activeGatt: BluetoothGatt? = null
    private var connectionGeneration = 0L

    @Synchronized
    fun bind(address: String): Long {
        connectionGeneration++
        deviceAddress = address
        activeGatt = null
        hasDiscoveredService = false
        _gattFlow.resetReplayCache()
        return connectionGeneration
    }

    @Synchronized
    fun attach(gatt: BluetoothGatt) {
        activeGatt = gatt
    }

    @Synchronized
    private fun generationFor(gatt: BluetoothGatt): Long? {
        return if (activeGatt === gatt && deviceAddress != null &&
            gatt.device.address.equals(deviceAddress, ignoreCase = true)
        ) {
            connectionGeneration
        } else {
            null
        }
    }

    private fun pushEvent(event: GattCallbackEvent) {
        runBlocking {
            _gattFlow.emit(event)
        }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        val generation = generationFor(gatt) ?: return
        L.d("GATT connection state change. state: $newState, status: $status")
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                pushEvent(GattCallbackEvent.ConnectionState.Connected(generation))
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                pushEvent(GattCallbackEvent.ConnectionState.Disconnected(generation))
            }
        }
    }

    override fun onServicesDiscovered(
        gatt: BluetoothGatt,
        status: Int
    ) {
        val generation = generationFor(gatt) ?: return
        L.d("------------- onServicesDiscovered status: $status")
        if (status == BluetoothGatt.GATT_SUCCESS) {
            hasDiscoveredService = true
            pushEvent(
                GattCallbackEvent.ServicesDiscovered(gatt.services, generation)
            )
        } else {
            L.w("onServicesDiscovered received: $status")
            pushEvent(GattCallbackEvent.ConnectionState.Disconnected(generation))
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
        super.onMtuChanged(gatt, mtu, status)
        if (gatt == null) return
        val generation = generationFor(gatt) ?: return
        //Seems that the callback can be reached without calling gatt.requestMtu(...)
        if (hasDiscoveredService) {
            L.d("------------ onMtuChanged => MTU new size: $mtu")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pushEvent(GattCallbackEvent.MtuNegociated(mtu - GattInteractor.GATT_HEADER_SIZE, generation))
            } else {
                L.w("onMtuChanged error with status : $status")
                pushEvent(GattCallbackEvent.ConnectionState.Disconnected(generation))
            }
        }
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor?,
        status: Int
    ) {
        val generation = generationFor(gatt) ?: return
        super.onDescriptorWrite(gatt, descriptor, status)
        L.d("------------- onDescriptorWrite status: $status")
        pushEvent(GattCallbackEvent.WriteDescriptorAck(descriptor?.uuid, status == BluetoothGatt.GATT_SUCCESS, generation))
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        val generation = generationFor(gatt) ?: return
        L.d("------------- onCharacteristicWrite status: $status")
        pushEvent(GattCallbackEvent.WriteCharacteristicAck(characteristic.uuid, status == BluetoothGatt.GATT_SUCCESS, generation))

    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        val generation = generationFor(gatt) ?: return
        L.d("------------- onCharacteristicChanged status: ${characteristic.value.toHexString()}")
        pushEvent(GattCallbackEvent.CharacteristicChanged(characteristic.uuid, characteristic.value, generation))
    }

    @Synchronized
    fun clear() {
        hasDiscoveredService = false
        deviceAddress = null
        activeGatt = null
        _gattFlow.resetReplayCache()
    }
}
