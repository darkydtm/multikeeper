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
import java.util.IdentityHashMap

class BleGattCallbackFlow : BluetoothGattCallback() {

    private val _gattFlow =
        MutableSharedFlow<GattCallbackEvent>(replay = 1, extraBufferCapacity = 0)
    val gattFlow: Flow<GattCallbackEvent>
        get() = _gattFlow

    private var deviceAddress: String? = null
    private var activeGatt: BluetoothGatt? = null
    private var discoveredGatt: BluetoothGatt? = null
    private var connectionGeneration = 0L
    private val pendingEvents = IdentityHashMap<BluetoothGatt, MutableList<GattCallbackEvent>>()

    @Synchronized
    fun bind(address: String): Long {
        connectionGeneration++
        deviceAddress = address
        activeGatt = null
        discoveredGatt = null
        pendingEvents.clear()
        _gattFlow.resetReplayCache()
        return connectionGeneration
    }

    fun attach(gatt: BluetoothGatt, generation: Long) {
        val events = synchronized(this) {
            if (generation != connectionGeneration ||
                !gatt.device.address.equals(deviceAddress, ignoreCase = true)
            ) {
                return@synchronized emptyList()
            }
            activeGatt = gatt
            pendingEvents.remove(gatt).orEmpty()
        }
        events.forEach(::pushEvent)
    }

    private fun publish(gatt: BluetoothGatt, event: (Long) -> GattCallbackEvent) {
        val callbackEvent = synchronized(this) {
            if (!gatt.device.address.equals(deviceAddress, ignoreCase = true) ||
                (activeGatt != null && activeGatt !== gatt)
            ) {
                return@synchronized null
            }
            if (activeGatt === gatt) {
                event(connectionGeneration)
            } else {
                pendingEvents.getOrPut(gatt) { mutableListOf() }.add(event(connectionGeneration))
                null
            }
        }
        callbackEvent?.let(::pushEvent)
    }

    private fun pushEvent(event: GattCallbackEvent) {
        runBlocking {
            _gattFlow.emit(event)
        }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        L.d("GATT connection state change. state: $newState, status: $status")
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                publish(gatt) { GattCallbackEvent.ConnectionState.Connected(it) }
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                publish(gatt) { GattCallbackEvent.ConnectionState.Disconnected(it) }
            }
        }
    }

    override fun onServicesDiscovered(
        gatt: BluetoothGatt,
        status: Int
    ) {
        L.d("------------- onServicesDiscovered status: $status")
        if (status == BluetoothGatt.GATT_SUCCESS) {
            synchronized(this) {
                if (gatt.device.address.equals(deviceAddress, ignoreCase = true) &&
                    (activeGatt == null || activeGatt === gatt)
                ) {
                    discoveredGatt = gatt
                }
            }
            publish(gatt) { GattCallbackEvent.ServicesDiscovered(gatt.services, it) }
        } else {
            L.w("onServicesDiscovered received: $status")
            publish(gatt) { GattCallbackEvent.ConnectionState.Disconnected(it) }
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
        super.onMtuChanged(gatt, mtu, status)
        if (gatt == null) return
        //Seems that the callback can be reached without calling gatt.requestMtu(...)
        val discovered = synchronized(this) { discoveredGatt === gatt }
        if (discovered) {
            L.d("------------ onMtuChanged => MTU new size: $mtu")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                publish(gatt) {
                    GattCallbackEvent.MtuNegociated(mtu - GattInteractor.GATT_HEADER_SIZE, it)
                }
            } else {
                L.w("onMtuChanged error with status : $status")
                publish(gatt) { GattCallbackEvent.ConnectionState.Disconnected(it) }
            }
        }
    }

    override fun onDescriptorWrite(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor?,
        status: Int
    ) {
        super.onDescriptorWrite(gatt, descriptor, status)
        L.d("------------- onDescriptorWrite status: $status")
        publish(gatt) {
            GattCallbackEvent.WriteDescriptorAck(descriptor?.uuid, status == BluetoothGatt.GATT_SUCCESS, it)
        }
    }

    override fun onCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        status: Int
    ) {
        L.d("------------- onCharacteristicWrite status: $status")
        publish(gatt) {
            GattCallbackEvent.WriteCharacteristicAck(characteristic.uuid, status == BluetoothGatt.GATT_SUCCESS, it)
        }

    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        L.d("------------- onCharacteristicChanged status: ${characteristic.value.toHexString()}")
        publish(gatt) {
            GattCallbackEvent.CharacteristicChanged(characteristic.uuid, characteristic.value, it)
        }
    }

    @Synchronized
    fun clear() {
        deviceAddress = null
        activeGatt = null
        discoveredGatt = null
        pendingEvents.clear()
        _gattFlow.resetReplayCache()
    }
}
