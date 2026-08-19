package com.tonapps.ledger.ble.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.tonapps.ledger.ble.service.model.BlePairingEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class BlePairingCallbackFlow(
    private val context: Context,
    private val deviceAddress: String,
) {
    private val pairingReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (!device.address.equals(deviceAddress, ignoreCase = true)) return
            when (device.bondState) {
                BluetoothDevice.BOND_NONE -> pushEvent(BlePairingEvent.None)
                BluetoothDevice.BOND_BONDING -> pushEvent(BlePairingEvent.Pairing)
                BluetoothDevice.BOND_BONDED -> pushEvent(BlePairingEvent.Paired)
            }
        }
    }

    private val _gattFlow =
        MutableSharedFlow(
            replay = 1,
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val gattFlow: Flow<BlePairingEvent>
        get() = _gattFlow

    fun bind() {
        context.registerReceiver(
            pairingReceiver,
            IntentFilter("android.bluetooth.device.action.PAIRING_REQUEST")
        )
    }

    fun unbind() {
        context.unregisterReceiver(pairingReceiver)
    }

    private fun pushEvent(event: BlePairingEvent) {
        _gattFlow.tryEmit(event)
    }
}
