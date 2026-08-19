package com.tonapps.ledger.ble.service.model

sealed class BlePairingEvent(
    override val generation: Long,
): GattCallbackEvent(), GattCallbackEvent.GenerationAware {
    data class None(generation: Long): BlePairingEvent(generation)
    data class Pairing(generation: Long): BlePairingEvent(generation)
    data class Paired(generation: Long): BlePairingEvent(generation)
}
