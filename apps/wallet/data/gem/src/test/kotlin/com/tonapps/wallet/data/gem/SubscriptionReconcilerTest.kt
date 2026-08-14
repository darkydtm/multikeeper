package com.tonapps.wallet.data.gem

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionReconcilerTest {
	@Test
	fun `reconciles duplicate addresses across chains and stale subscriptions`() {
		val walletId = WalletId("wallet")
		val desired = listOf(
			WalletSubscription(walletId, Chain.Ethereum, "same-address"),
			WalletSubscription(walletId, Chain.SmartChain, "same-address"),
		)
		val current = listOf(
			WalletSubscription(walletId, Chain.Ethereum, "same-address"),
			WalletSubscription(walletId, Chain.SmartChain, "stale-address"),
			WalletSubscription(walletId, Chain.Bitcoin, "same-address"),
		)

		val result = SubscriptionReconciler.reconcile(desired, current)

		assertEquals(
			listOf(WalletSubscription(walletId, Chain.SmartChain, "same-address")),
			result.add,
		)
		assertEquals(
			listOf(
				WalletSubscription(walletId, Chain.Bitcoin, "same-address"),
				WalletSubscription(walletId, Chain.SmartChain, "stale-address"),
			),
			result.delete,
		)
	}
}
