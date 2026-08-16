package com.tonapps.ledger.ble.service

import com.tonapps.ledger.ble.extension.toHexString
import com.tonapps.ledger.ble.model.FrameCommand
import com.tonapps.ledger.ble.service.model.BleAnswer

class BleReceiver {
    private var pendingAnswers: MutableList<FrameCommand> = mutableListOf()
    fun handleAnswer(id: String, hexAnswer: String): BleAnswer? {
        val command: FrameCommand = FrameCommand.fromHex(id, hexAnswer)
        if (command.index == 0) {
            pendingAnswers.clear()
        } else if (pendingAnswers.isEmpty() || command.index != pendingAnswers.last().index + 1 ||
            command.id != pendingAnswers.first().id || command.size != 0) {
            pendingAnswers.clear()
            throw IllegalArgumentException("Invalid BLE frame order")
        }
        pendingAnswers.add(command)

        val totalReceivedSize = pendingAnswers.sumOf { it.apdu.size }
        if (totalReceivedSize > pendingAnswers.first().size) {
            pendingAnswers.clear()
            throw IllegalArgumentException("Invalid BLE frame length")
        }

        val isAnswerComplete = if (command.index == 0) {
            command.size == command.apdu.size
        } else {
            pendingAnswers.first().size == totalReceivedSize
        }

        return if (isAnswerComplete) {
            val completeApdu = pendingAnswers.joinToString("") { it.apdu.toHexString() }
            pendingAnswers.clear()
            BleAnswer(id, completeApdu)
        } else {
            null
        }
    }
}
