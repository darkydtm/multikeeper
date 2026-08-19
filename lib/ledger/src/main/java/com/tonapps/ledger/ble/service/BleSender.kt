package com.tonapps.ledger.ble.service

import android.annotation.SuppressLint
import com.tonapps.ledger.ble.model.BleCommand
import com.tonapps.ledger.ble.model.BleDeviceService
import com.tonapps.ledger.ble.model.FrameCommand
import com.tonapps.ledger.ble.service.model.BlePendingRequest
import com.tonapps.log.L
import java.util.ArrayDeque
import java.util.Date
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue

@SuppressLint("MissingPermission")
class BleSender(
    private val gatt: GattInteractor,
    private val deviceAddress: String,
    val pushWaitingResponseState : (String) -> Unit,
    val pushErrorState: () -> Unit,
) {
    var isInitialized: Boolean = false

    var pendingApdu: Queue<BlePendingRequest> = ConcurrentLinkedQueue()
    private val commandQueue: ArrayDeque<FrameCommand> = ArrayDeque()
    var pendingCommand: FrameCommand? = null
    private lateinit var deviceService: BleDeviceService
    private var mtuSize: Int = 0

    fun queuApdu(apdu: ByteArray): String {
        val id = generateId(deviceAddress)
        pendingApdu.add(BlePendingRequest(id, apdu))
        return id
    }

    @Synchronized
    fun dequeuApdu() {
        if (!isInitialized) {
            throw IllegalStateException("Should not try to dequeu before initialize BleSender")
        }
        L.d("Try to dequeu pending request")
        L.d("pending request => ${pendingApdu.size}")
        if (pendingApdu.isNotEmpty() && pendingCommand == null) {
            L.d("Dequeu is possible")
            val pendingRequest = pendingApdu.remove()
            val command = BleCommand(pendingRequest.id, pendingRequest.apdu, mtuSize)
            sendCommands(command)
        } else {
            L.d("Dequeu is NOT possible")
        }
    }

    private fun sendCommands(command: BleCommand) {
        L.d("Need to send ${command.commands.size} frame")
        commandQueue.addAll(command.commands)
        val command = commandQueue.removeFirst()
        sendCommand(command)
    }

    private fun sendCommand(command: FrameCommand) {
        val commandInByte: ByteArray = command.bytes
        pendingCommand = command
        pushWaitingResponseState(command.id)
        when (gatt.sendBytes(deviceService, commandInByte)) {
            GattInteractor.WriteResult.Failed -> {
                pendingCommand = null
                commandQueue.clear()
                pushErrorState()
            }
            GattInteractor.WriteResult.Sent -> {
                if (commandQueue.isNotEmpty()) {
                    sendCommand(commandQueue.removeFirst())
                }
            }
            GattInteractor.WriteResult.AwaitingCallback -> Unit
        }
    }

    fun nextCommand() {
        if (commandQueue.isNotEmpty()) {
            val command = commandQueue.removeFirst()
            sendCommand(command)
        }
    }

    fun clearCommand() {
        pendingCommand = null
        commandQueue.clear()
    }

    fun initialized(mtu: Int, deviceService: BleDeviceService) {
        this.mtuSize = mtu
        this.deviceService = deviceService
        isInitialized = true
    }

    companion object {
        fun generateId(deviceName: String): String {
            return "${deviceName}_send_${Date().time}"
        }
    }

}
