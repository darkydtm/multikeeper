package com.tonapps.ledger.ble.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattService
import android.content.Context
import androidx.annotation.VisibleForTesting
import com.tonapps.async.Async
import com.tonapps.ledger.ble.extension.fromHexStringToBytes
import com.tonapps.ledger.ble.extension.toHexString
import com.tonapps.ledger.ble.extension.toUUID
import com.tonapps.ledger.ble.model.BleDeviceService
import com.tonapps.ledger.ble.model.BleError
import com.tonapps.ledger.ble.service.BleService.Companion.MTU_HANDSHAKE_COMMAND
import com.tonapps.ledger.ble.service.model.BleAnswer
import com.tonapps.ledger.ble.service.model.BlePairingEvent
import com.tonapps.ledger.ble.service.model.GattCallbackEvent
import com.tonapps.ledger.devices.Devices
import com.tonapps.log.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking

@SuppressLint("MissingPermission")
class BleServiceStateMachine(
    private val gattCallbackFlow: BleGattCallbackFlow,
    private val deviceAddress: String,
    private val device: BluetoothDevice,
) {
    private var isCleared: Boolean = false
    internal var currentState: BleServiceState = BleServiceState.Created
    internal lateinit var deviceService: BleDeviceService
    var mtuSize = -1
    internal var negotiatedMtu = -1
    private val bleReceiver = BleReceiver()

    private val scope = Async.ioScope() + Job()
    internal lateinit var timeoutJob: Job
    internal lateinit var pairingCallbackFlow: BlePairingCallbackFlow

    private val _stateMachineFlow = MutableSharedFlow<BleServiceState>(
        replay = 1,
        extraBufferCapacity = 0,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    val stateFlow: Flow<BleServiceState>
        get() = _stateMachineFlow.filter { !isCleared }

    private lateinit var gattInteractor: GattInteractor

    internal val bleSender: BleSender by lazy {
        BleSender(gattInteractor, deviceAddress) { sendId ->
            pushState(BleServiceState.WaitingResponse(sendId))
        }
    }

    var isPaired = false
    var pairing = false

    init {
        gattCallbackFlow.gattFlow
            .onEach { L.d("Event Received $it") }
            .onEach { handleGattCallbackEvent(it) }
            .flowOn(Dispatchers.IO)
            .launchIn(scope)
    }

    fun build(context: Context) {
        val bluetoothGATT = device.connectGatt(context, false, gattCallbackFlow)
        timeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT)
            _stateMachineFlow.tryEmit(BleServiceState.Error(BleError.CONNECTION_TIMEOUT))
        }

        pairingCallbackFlow = BlePairingCallbackFlow(context, deviceAddress)
        pairingCallbackFlow.bind()
        pairingCallbackFlow.gattFlow
            .onEach { handleGattCallbackEvent(it) }
            .flowOn(Dispatchers.IO)
            .launchIn(scope)

        this.gattInteractor = GattInteractor(bluetoothGATT!!)
        this.isCleared = false
    }

    fun clear() {
        this.isCleared = true
        _stateMachineFlow.resetReplayCache()
        pairingCallbackFlow.unbind()
        this.gattInteractor.gatt.close()
        this.gattInteractor.gatt.disconnect()
    }

    fun sendApdu(apdu: ByteArray): String {
        val id = bleSender.queuApdu(apdu)
        if (currentState is BleServiceState.Ready
            || currentState is BleServiceState.WaitingResponse
        ) {
            bleSender.dequeuApdu()
        } else { //Trigger Gatt initialization reset current state in order to ensure right initialization
            timeoutJob.cancel()
            pushState(BleServiceState.WaitingServices)
            gattInteractor.discoverService()
        }

        return id
    }

    @VisibleForTesting
    private fun handleGattCallbackEvent(event: GattCallbackEvent) {
        when (event) {
            is GattCallbackEvent.ConnectionState.Connected -> {
                when (currentState) {
                    BleServiceState.Created -> {
                        timeoutJob.cancel()
                        pushState(BleServiceState.WaitingServices)
                        gattInteractor.discoverService()
                    }
                    else -> {
                        pushState(BleServiceState.Error(BleError.INTERNAL_STATE))
                    }
                }
            }
            is GattCallbackEvent.ServicesDiscovered -> {
                when (currentState) {
                    BleServiceState.WaitingServices -> {
                        val deviceService = parseServices(event.services)
                        if (deviceService != null) {
                            L.d("Devices Services parsed for given UUID ${deviceService?.uuid}")
                            L.d("Current State $currentState")

                            this@BleServiceStateMachine.deviceService = deviceService
                            pushState(BleServiceState.NegotiatingMtu)
                            gattInteractor.negotiateMtu()
                        } else {
                            _stateMachineFlow.tryEmit(BleServiceState.Error(BleError.SERVICE_NOT_FOUND))
                        }
                    }
                    else -> {
                        pushState(BleServiceState.Error(BleError.INTERNAL_STATE))
                    }
                }
            }
            is GattCallbackEvent.MtuNegociated -> {
                when (currentState) {
                    BleServiceState.NegotiatingMtu -> {
                        negotiatedMtu = event.mtuSize
                        pushState(BleServiceState.WaitingNotificationEnable)
                        gattInteractor.enableNotification(deviceService)
                    }
                    else -> {
                        pushState(BleServiceState.Error(BleError.INTERNAL_STATE))
                    }
                }
            }
            is GattCallbackEvent.WriteDescriptorAck -> {
                when (currentState) {
                    BleServiceState.WaitingNotificationEnable -> {
                        if (!event.isSuccess || event.descriptorUuid != deviceService.notifyCharacteristic.descriptors.first {
                                it.uuid == android.bluetooth.BluetoothGattDescriptor.UUID_CLIENT_CHARACTERISTIC_CONFIG
                            }.uuid) {
                            pushState(BleServiceState.Error(BleError.INTERNAL_STATE))
                            return
                        }
                        pushState(BleServiceState.CheckingMtu)
                        gattInteractor.askMtu(deviceService)
                    }
                    else -> {
                        pushState(BleServiceState.Error(BleError.INTERNAL_STATE))
                    }
                }
            }
            is GattCallbackEvent.WriteCharacteristicAck -> {
                when (currentState) {
                    BleServiceState.CheckingMtu -> {
                        if (!event.isSuccess || event.characteristicUuid != deviceService.writeCharacteristic.uuid) {
                            pushState(BleServiceState.Error(BleError.INTERNAL_STATE))
                            return
                        }
                        L.d("Mtu request Sent")
                    }
                    is BleServiceState.Ready -> {
                        if (!event.isSuccess || event.characteristicUuid != deviceService.writeCharacteristic.uuid && event.characteristicUuid != deviceService.writeNoAnswerCharacteristic?.uuid) {
                            pushState(BleServiceState.Error(BleError.INTERNAL_STATE))
                            return
                        }
                        //NOTHING TO do but not an error
                        //CharacteristicChanged can be called before write characteristic ack
                        bleSender.nextCommand()
                    }
                    is BleServiceState.WaitingResponse -> {
                        if (!event.isSuccess || event.characteristicUuid != deviceService.writeCharacteristic.uuid && event.characteristicUuid != deviceService.writeNoAnswerCharacteristic?.uuid) {
                            pushState(BleServiceState.Error(BleError.INTERNAL_STATE))
                            return
                        }
                        bleSender.nextCommand()
                    }
                    else -> {
                        pushState(
                            BleServiceState.Error(BleError.INTERNAL_STATE)
                        )
                    }
                }
            }
            is GattCallbackEvent.CharacteristicChanged -> {
                if (event.characteristicUuid != deviceService.notifyCharacteristic.uuid) {
                    pushState(BleServiceState.Error(BleError.INTERNAL_STATE))
                    return
                }
                when (currentState) {
                    BleServiceState.CheckingMtu -> {
                        if (event.value.size < MTU_HANDSHAKE_COMMAND.length / 2 ||
                            !event.value.copyOfRange(0, MTU_HANDSHAKE_COMMAND.length / 2)
                                .contentEquals(MTU_HANDSHAKE_COMMAND.fromHexStringToBytes())) {
                            pushState(BleServiceState.Error(BleError.INTERNAL_STATE))
                            return
                        }
                        val mtuValue = event.value.toHexString().substring(MTU_HANDSHAKE_COMMAND.length)
                        if (mtuValue.isEmpty() || mtuValue.length > 4) {
                            pushState(BleServiceState.Error(BleError.INTERNAL_STATE))
                            return
                        }
                        mtuSize = mtuValue.toInt(16)
                        L.d("Mtu Value received : $mtuSize")
                        L.d("Negotiated Mtu Value received : $negotiatedMtu")
                        if (mtuSize != negotiatedMtu) {
                            pushState(BleServiceState.Error(BleError.INTERNAL_STATE))
                            return
                        }

                        pushState(BleServiceState.Ready(deviceService, negotiatedMtu, null))
                    }
                    is BleServiceState.WaitingResponse -> {
                        val answer = try {
                            bleReceiver.handleAnswer(
                                bleSender.pendingCommand!!.id,
                                event.value.toHexString()
                            )
                        } catch (_: IllegalArgumentException) {
                            pushState(BleServiceState.Error(BleError.INTERNAL_STATE))
                            return
                        }
                        if (answer != null) {
                            bleSender.clearCommand()
                            pushState(BleServiceState.Ready(deviceService, mtuSize, answer))
                        } else {
                            L.d("Still waiting for a part of the answer")
                        }
                    }
                    else -> {
                        pushState(BleServiceState.Error(BleError.INTERNAL_STATE))
                    }
                }
            }
            is GattCallbackEvent.ConnectionState.Disconnected -> {
                if (pairing) {
                    pushState(BleServiceState.Error(BleError.PAIRING_FAILED))
                } else {
                    pushState(BleServiceState.Error(BleError.UNKNOWN))
                }
            }
            is BlePairingEvent.None -> {
                pairing = false
                isPaired = false
            }
            is BlePairingEvent.Pairing -> {
                pairing = true
                isPaired = false
            }
            is BlePairingEvent.Paired -> {
                pairing = false
                isPaired = true
            }
        }
    }

    @VisibleForTesting
    private fun pushState(state: BleServiceState) {
        currentState = state
        //ensure state is pushed
        runBlocking {
            L.d("push state => $state")
            _stateMachineFlow.emit(state)
        }

        if (currentState is BleServiceState.Ready) {
            if (!bleSender.isInitialized) {
                bleSender.initialized(mtuSize, deviceService)
            }
            bleSender.dequeuApdu()
        }
    }

    private fun parseServices(services: List<BluetoothGattService>): BleDeviceService? {
        val bluetoothSpecs = Devices.getBluetoothDevices().mapNotNull { it.bluetoothSpec }
        var deviceService: BleDeviceService? = null
        services.forEach { service ->
            val spec = bluetoothSpecs.find { it.serviceUuid.toUUID() == service.uuid }
            if (spec != null) {
                L.d("Service UUID ${service.uuid}")

                val bleServiceBuilder: BleDeviceService.Builder =
                    BleDeviceService.Builder(service.uuid)
                service.characteristics.forEach { characteristic ->
                    when (characteristic.uuid) {
                        spec.writeUuid.toUUID() -> {
                            bleServiceBuilder.setWriteCharacteristic(characteristic)
                        }
                        spec.writeCmdUuid.toUUID() -> {
                            bleServiceBuilder.setWriteNoAnswerCharacteristic(characteristic)
                        }
                        spec.notifyUuid.toUUID() -> {
                            bleServiceBuilder.setNotifyCharacteristic(characteristic)
                        }
                    }
                }
                deviceService = try {
                    bleServiceBuilder.build()
                } catch (_: IllegalStateException) {
                    null
                }
            }
        }

        return deviceService
    }

    sealed class BleServiceState {
        object Created : BleServiceState()
        object WaitingServices : BleServiceState()
        object NegotiatingMtu : BleServiceState()
        object WaitingNotificationEnable : BleServiceState()
        object CheckingMtu : BleServiceState()
        data class Ready(
            val deviceService: BleDeviceService,
            val mtu: Int,
            val answer: BleAnswer? = null
        ) : BleServiceState()

        data class WaitingResponse(val sendId: String) : BleServiceState()
        data class Error(val error: BleError) : BleServiceState()
    }

    companion object {
        private const val CONNECT_TIMEOUT = 5_000L
        internal const val ERROR_MTU_NEGOTIATED_AND_CHECKED_DIVERGENT =
            "Error MTU checked is not the same than the negotiated MTU"
        internal const val ERROR_WRONG_STATE_FOR_CONNECTED_EVENT =
            "Connected event should only be received when current state is Created"
        internal const val ERROR_WRONG_STATE_FOR_SERVICES_DISCOVERED =
            "ServicesDiscovered event should only be received when current state is WaitingServices"
        internal const val ERROR_WRONG_STATE_FOR_MTU_NEGOTIATED =
            "MtuNegociated event should only be received when current state is NegotiatingMtu"
        internal const val ERROR_WRONG_STATE_FOR_WRITE_DESCRIPTOR_ACK =
            "WriteDescriptorAck event should only be received when current state is WaitingNotificationEnable"
        internal const val ERROR_WRONG_STATE_FOR_WRITE_CHARACTERISTIC_ACK =
            "WriteCharacteristicAck event should only be received when current state is WaitingMtu or WaitingResponse"
        internal const val ERROR_WRONG_STATE_FOR_CHARACTERISTIC_CHANGED =
            "CharacteristicChanged event should only be received when current state is WaitingMtu or WaitingResponse"
    }
}
