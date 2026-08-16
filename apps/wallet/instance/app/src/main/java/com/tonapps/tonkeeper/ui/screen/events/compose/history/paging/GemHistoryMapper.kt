package com.tonapps.tonkeeper.ui.screen.events.compose.history.paging

import com.tonapps.wallet.data.gem.AssetMetadata
import com.tonapps.wallet.data.gem.TransactionRecord
import com.tonapps.wallet.data.gem.TransactionState
import com.tonapps.icu.CurrencyFormatter
import kotlinx.collections.immutable.toImmutableList
import ui.UiPosition
import ui.components.events.UiEvent
import java.text.DateFormat
import java.util.Date

internal class GemHistoryMapper(
	private val labels: Labels,
) {
	fun toUiItem(record: TransactionRecord): UiEvent.Item {
		val incoming = record.direction?.lowercase()?.let(incomingDirections::contains) == true
		val title = (record.metadata as? AssetMetadata.Known)?.symbol ?: labels.unknown
		val subtitle = (if (incoming) record.from else record.to) ?: if (incoming) labels.received else labels.sent
		val failed = record.state == TransactionState.Failed || record.state == TransactionState.Reverted
		val action = UiEvent.Item.Action(
			state = when {
				record.state == TransactionState.Pending || record.state == TransactionState.InTransit -> UiEvent.Item.Action.State.Pending
				failed -> UiEvent.Item.Action.State.Failed
				record.state == TransactionState.Unknown -> UiEvent.Item.Action.State.Pending
				else -> UiEvent.Item.Action.State.Success
			},
			title = title,
			subtitle = subtitle,
			incomingAmount = record.amount.takeIf { incoming }?.let { CurrencyFormatter.PREFIX_PLUS + it },
			outgoingAmount = record.amount.takeUnless { incoming }?.let { CurrencyFormatter.PREFIX_MINUS + it },
			date = record.timestamp?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) }.orEmpty(),
			imageUrl = (record.metadata as? AssetMetadata.Known)?.imageUrl,
			iconUrl = null,
			product = null,
			text = null,
			warningText = labels.failed.takeIf { failed },
			rightDescription = null,
			spam = false,
			position = UiPosition.Single,
			badge = null,
		)
		return UiEvent.Item(
			id = record.hash ?: record.gemId ?: record.cacheKey,
			timestamp = record.timestamp ?: 0L,
			actions = listOf(action).toImmutableList(),
			filterIds = listOf(if (incoming) 3 else 2).toImmutableList(),
			spam = false,
			progress = record.state == TransactionState.Pending || record.state == TransactionState.InTransit,
		)
	}

	data class Labels(
		val received: String,
		val sent: String,
		val failed: String,
		val unknown: String,
	)

	private companion object {
		val incomingDirections = setOf("incoming", "received")
	}
}
