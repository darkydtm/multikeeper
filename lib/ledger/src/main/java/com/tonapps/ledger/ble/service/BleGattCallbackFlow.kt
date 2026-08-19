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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.IdentityHashMap

class BleGattCallbackFlow : BluetoothGattCallback() {

    val gattFlow: Flow<GattCallbackEvent>
        get() = gattChannel.receiveAsFlow()

    private var gattChannel = Channel<GattCallbackEvent>(Channel.UNLIMITED)
    private var deviceAddress: String? = null
    private var activeGatt: BluetoothGatt? = null
    private var discoveredGatt: BluetoothGatt? = null
    private var connectionGeneration = 0L
    private val retiredGatts = IdentityHashMap<BluetoothGatt, Unit>()
    private val pendingEvents = IdentityHashMap<BluetoothGatt, MutableList<GattCallbackEvent>>()
    private val pendingDiscovery = IdentityHashMap<BluetoothGatt, Unit>()

    @Synchronized
    fun bind(address: String): Long {
        connectionGeneration++
        deviceAddress = address
        activeGatt?.let { retiredGatts[it] = Unit }
        pendingEvents.keys.forEach { retiredGatts[it] = Unit }
        pendingDiscovery.keys.forEach { retiredGatts[it] = Unit }
        activeGatt = null
        discoveredGatt = null
        pendingEvents.clear()
        pendingDiscovery.clear()
        return connectionGeneration
    }

    fun attach(gatt: BluetoothGatt, generation: Long) {
        synchronized(this) {
            if (generation != connectionGeneration ||
                !gatt.device.address.equals(deviceAddress, ignoreCase = true) ||
                retiredGatts.containsKey(gatt)
            ) {
                return
            }
            activeGatt = gatt
            val events = pendingEvents.remove(gatt).orEmpty()
            if (pendingDiscovery.remove(gatt) != null ||
                events.any { it is GattCallbackEvent.ServicesDiscovered }
            ) {
                discoveredGatt = gatt
            }
            events.forEach { gattChannel.trySend(it) }
        }
    }

    private fun publish(gatt: BluetoothGatt, event: (Long) -> GattCallbackEvent) {
        synchronized(this) {
            if (!gatt.device.address.equals(deviceAddress, ignoreCase = true) ||
                (activeGatt != null && activeGatt !== gatt) ||
                retiredGatts.containsKey(gatt)
            ) {
                return@synchronized Unit
            }
            val callbackEvent = event(connectionGeneration)
            if (activeGatt === gatt) {
                gattChannel.trySend(callbackEvent)
            } else {
                pendingEvents.getOrPut(gatt) { mutableListOf() }.add(callbackEvent)
            }
            Unit
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
            val accepted = synchronized(this) {
                if (!gatt.device.address.equals(deviceAddress, ignoreCase = true) ||
                    (activeGatt != null && activeGatt !== gatt) ||
                    retiredGatts.containsKey(gatt)
                ) {
                    false
                } else {
                    pendingDiscovery[gatt] = Unit
                    true
                }
            }
            if (accepted) {
                publish(gatt) { GattCallbackEvent.ServicesDiscovered(gatt.services, it) }
            }
        } else {
            L.w("onServicesDiscovered received: $status")
            publish(gatt) { GattCallbackEvent.ConnectionState.Disconnected(it) }
        }
    }

    override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
        super.onMtuChanged(gatt, mtu, status)
        if (gatt == null) return
        //Seems that the callback can be reached without calling gatt.requestMtu(...)
        val discovered = synchronized(this) {
            discoveredGatt === gatt || pendingDiscovery.containsKey(gatt)
        }
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
        activeGatt?.let { retiredGatts[it] = Unit }
        activeGatt = null
        discoveredGatt = null
        pendingEvents.clear()
        pendingDiscovery.clear()
    }
}
