package com.tonapps.ledger.ble.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.tonapps.ledger.ble.service.model.BlePairingEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

class BlePairingCallbackFlow(
    private val context: Context,
    private val deviceAddress: String,
) {
    private val pairingReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (!device.address.equals(deviceAddress, ignoreCase = true)) return
            val bondState = intent.getIntExtra(
                BluetoothDevice.EXTRA_BOND_STATE,
                device.bondState,
            )
            when (bondState) {
                BluetoothDevice.BOND_NONE -> pushEvent(BlePairingEvent.None)
                BluetoothDevice.BOND_BONDING -> pushEvent(BlePairingEvent.Pairing)
                BluetoothDevice.BOND_BONDED -> pushEvent(BlePairingEvent.Paired)
            }
        }
    }

    private val _gattFlow = Channel<BlePairingEvent>(Channel.UNLIMITED)
    val gattFlow: Flow<BlePairingEvent>
        get() = _gattFlow.receiveAsFlow()

    fun bind() {
        context.registerReceiver(
            pairingReceiver,
            IntentFilter().apply {
                addAction("android.bluetooth.device.action.PAIRING_REQUEST")
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
        )
    }

    fun unbind() {
        context.unregisterReceiver(pairingReceiver)
    }

    private fun pushEvent(event: BlePairingEvent) {
        _gattFlow.trySend(event)
    }
}
