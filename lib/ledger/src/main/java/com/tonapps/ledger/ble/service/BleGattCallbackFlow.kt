package com.tonapps.ledger.ble.service

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import com.tonapps.ledger.ble.extension.toHexString
import com.tonapps.ledger.ble.service.model.GattCallbackEvent
import com.tonapps.log.L
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.IdentityHashMap
import java.util.WeakHashMap

class BleGattCallbackFlow : BluetoothGattCallback() {

    private var gattChannel = Channel<GattCallbackEvent>(Channel.UNLIMITED)
    val gattFlow: Flow<GattCallbackEvent>
        get() = gattChannel.receiveAsFlow()
    private var deviceAddress: String? = null
    private var activeGatt: BluetoothGatt? = null
    private var discoveredGatt: BluetoothGatt? = null
    private var connectionGeneration = 0L
    private val retiredGatts = WeakHashMap<BluetoothGatt, Unit>()
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
        gattChannel.close()
        gattChannel = Channel(Channel.UNLIMITED)
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
            if (pendingDiscovery.remove(gatt) != null) {
                discoveredGatt = gatt
            }
            events.forEach { gattChannel.trySend(it) }
        }
    }

    private fun publish(gatt: BluetoothGatt, event: (Long) -> GattCallbackEvent) {
        synchronized(this) {
            publishLocked(gatt, event)
        }
    }

    private fun publishLocked(gatt: BluetoothGatt, event: (Long) -> GattCallbackEvent) {
        if (!gatt.device.address.equals(deviceAddress, ignoreCase = true) ||
            (activeGatt != null && activeGatt !== gatt) ||
            retiredGatts.containsKey(gatt)
        ) {
            return
        }
        val callbackEvent = event(connectionGeneration)
        if (activeGatt === gatt) {
            gattChannel.trySend(callbackEvent)
        } else {
            pendingEvents.getOrPut(gatt) { mutableListOf() }.add(callbackEvent)
        }
    }

    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
        L.d("GATT connection state change. state: $newState, status: $status")
        when (newState) {
            BluetoothProfile.STATE_CONNECTED -> {
                publish(gatt) { GattCallbackEvent.ConnectionState.Connected(status, it) }
            }
            BluetoothProfile.STATE_DISCONNECTED -> {
                publish(gatt) { GattCallbackEvent.ConnectionState.Disconnected(status, it) }
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
                    (activeGatt == null || activeGatt === gatt) &&
                    !retiredGatts.containsKey(gatt)
                ) {
                    pendingDiscovery[gatt] = Unit
                    publishLocked(gatt) { GattCallbackEvent.ServicesDiscovered(gatt.services, it) }
                }
            }
        } else {
            L.w("onServicesDiscovered received: $status")
            publish(gatt) { GattCallbackEvent.ConnectionState.Disconnected(status, it) }
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
                publish(gatt) { GattCallbackEvent.ConnectionState.Disconnected(status, it) }
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
            GattCallbackEvent.WriteCharacteristicAck(
                characteristic.uuid,
                status == BluetoothGatt.GATT_SUCCESS,
                characteristic.value?.clone() ?: ByteArray(0),
                it,
            )
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
        pendingEvents.keys.forEach { retiredGatts[it] = Unit }
        pendingDiscovery.keys.forEach { retiredGatts[it] = Unit }
        activeGatt = null
        discoveredGatt = null
        pendingEvents.clear()
        pendingDiscovery.clear()
        gattChannel.close()
    }
}
