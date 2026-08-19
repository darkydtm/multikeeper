package com.tonapps.ledger.ble.service.model

sealed class BlePairingEvent(
    override val generation: Long,
): GattCallbackEvent(), GattCallbackEvent.GenerationAware {
    data class None(override val generation: Long): BlePairingEvent(generation)
    data class Pairing(override val generation: Long): BlePairingEvent(generation)
    data class Paired(override val generation: Long): BlePairingEvent(generation)
}
