package com.tonapps.ledger.ble.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.tonapps.ledger.ble.service.model.BlePairingEvent

class BlePairingCallbackFlow(
    private val context: Context,
    private val deviceAddress: String,
    private val connectionGeneration: Long,
    private val onEvent: (BlePairingEvent) -> Unit,
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
                BluetoothDevice.BOND_NONE -> pushEvent(BlePairingEvent.None(connectionGeneration))
                BluetoothDevice.BOND_BONDING -> pushEvent(BlePairingEvent.Pairing(connectionGeneration))
                BluetoothDevice.BOND_BONDED -> pushEvent(BlePairingEvent.Paired(connectionGeneration))
            }
        }
    }

    private var isBound = false

    @Volatile
    var isPairing = false
        private set

    fun bind() {
        if (isBound) return
        context.registerReceiver(
            pairingReceiver,
            IntentFilter().apply {
                addAction("android.bluetooth.device.action.PAIRING_REQUEST")
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            }
        )
        isBound = true
    }

    fun unbind() {
        if (!isBound) return
        context.unregisterReceiver(pairingReceiver)
        isBound = false
    }

    private fun pushEvent(event: BlePairingEvent) {
        isPairing = event is BlePairingEvent.Pairing
        onEvent(event)
    }
}
