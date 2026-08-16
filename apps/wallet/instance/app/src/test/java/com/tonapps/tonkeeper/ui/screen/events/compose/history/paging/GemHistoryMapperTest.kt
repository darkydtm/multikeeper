package com.tonapps.tonkeeper.ui.screen.events.compose.history.paging

import com.tonapps.wallet.data.gem.AssetId
import com.tonapps.wallet.data.gem.AssetMetadata
import com.tonapps.wallet.data.gem.Chain
import com.tonapps.wallet.data.gem.TransactionRecord
import com.tonapps.wallet.data.gem.TransactionState
import com.tonapps.icu.CurrencyFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GemHistoryMapperTest {
	@Test
	fun `maps incoming Gem transaction without changing amount text`() {
		val item = GemHistoryMapper(
			GemHistoryMapper.Labels(
				received = "Received",
				sent = "Sent",
				failed = "Failed",
				unknown = "Unknown",
			),
		).toUiItem(
			TransactionRecord(
				walletId = com.tonapps.wallet.data.gem.WalletId("wallet"),
				chain = Chain.Solana,
				assetId = AssetId(Chain.Solana, "native"),
				amount = "0.000000001",
				timestamp = 1_700_000_000_000,
				state = TransactionState.InTransit,
				direction = "incoming",
				metadata = AssetMetadata.Known("SOL", "Solana", 9),
			),
		)

		val action = item.actions.single()
		assertEquals(CurrencyFormatter.PREFIX_PLUS + "0.000000001", action.incomingAmount)
		assertEquals(null, action.outgoingAmount)
		assertEquals("SOL", action.title)
		assertTrue(action.state == ui.components.events.UiEvent.Item.Action.State.Pending)
	}

	@Test
	fun `does not show unknown Gem transaction state as successful`() {
		val item = GemHistoryMapper(
			GemHistoryMapper.Labels(
				received = "Received",
				sent = "Sent",
				failed = "Failed",
				unknown = "Unknown",
			),
		).toUiItem(
			TransactionRecord(
				walletId = com.tonapps.wallet.data.gem.WalletId("wallet"),
				chain = Chain.Bitcoin,
				assetId = AssetId(Chain.Bitcoin, "native"),
				amount = "1",
				state = TransactionState.Unknown,
				metadata = AssetMetadata.Known("BTC", "Bitcoin", 8),
			),
		)

		assertEquals(ui.components.events.UiEvent.Item.Action.State.Pending, item.actions.single().state)
	}
}
