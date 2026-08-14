package com.tonapps.wallet.data.gem

import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.gemstone.GemFeeRate

class GemSendCoordinatorTest {
	@Test
	fun `builds a lossless Gem draft from a chain account and asset`() {
		val draft = GemSendCoordinator.draft(
			walletId = WalletId("wallet"),
			chain = Chain.Ethereum,
			assetId = AssetId(Chain.Ethereum, "native"),
			sender = "0xsender",
			recipient = "0xrecipient",
			amount = "1000000000000000001",
			metadata = AssetMetadata.Known("ETH", "Ethereum", 18),
		)

		assertEquals("1000000000000000001", draft.amount)
		assertEquals(Chain.Ethereum, draft.assetId.chain)
	}

	@Test
	fun `selects the requested fee index`() {
		val transaction = PreloadedTransaction(
			draft = GemSendCoordinator.draft(
				walletId = WalletId("wallet"),
				chain = Chain.Ethereum,
				assetId = AssetId(Chain.Ethereum, "native"),
				sender = "0xsender",
				recipient = "0xrecipient",
				amount = "1",
				metadata = AssetMetadata.Known("ETH", "Ethereum", 18),
			),
			metadata = uniffi.gemstone.GemTransactionLoadMetadata.None,
			feeRates = listOf(
				GemFeeRate("slow", uniffi.gemstone.GemGasPriceType.Regular("1")),
				GemFeeRate("fast", uniffi.gemstone.GemGasPriceType.Regular("2")),
			),
            data = listOf(
                uniffi.gemstone.GemTransactionData(
                    fee = uniffi.gemstone.GemTransactionLoadFee(
                        fee = "1",
                        gasPriceType = uniffi.gemstone.GemGasPriceType.Regular("1"),
                        gasLimit = "1",
                        options = uniffi.gemstone.GemFeeOptions(emptyMap()),
                    ),
                    metadata = uniffi.gemstone.GemTransactionLoadMetadata.None,
                ),
                uniffi.gemstone.GemTransactionData(
                    fee = uniffi.gemstone.GemTransactionLoadFee(
                        fee = "2",
                        gasPriceType = uniffi.gemstone.GemGasPriceType.Regular("2"),
                        gasLimit = "2",
                        options = uniffi.gemstone.GemFeeOptions(emptyMap()),
                    ),
                    metadata = uniffi.gemstone.GemTransactionLoadMetadata.None,
                ),
            ),
		)

		assertEquals(1, transaction.selectFee(1).getOrThrow().selectedFeeIndex)
	}
}
