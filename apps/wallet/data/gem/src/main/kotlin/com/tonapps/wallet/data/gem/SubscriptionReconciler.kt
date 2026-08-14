package com.tonapps.wallet.data.gem

data class WalletSubscription(
	val walletId: WalletId,
	val chain: Chain,
	val address: String,
)

data class SubscriptionReconciliation(
	val add: List<WalletSubscription>,
	val delete: List<WalletSubscription>,
)

private data class SubscriptionKey(
	val walletId: WalletId,
	val chain: Chain,
	val address: String,
)

@Suppress("ClassOrdering")
object SubscriptionReconciler {
	fun reconcile(
		desired: List<WalletSubscription>,
		current: List<WalletSubscription>,
	): SubscriptionReconciliation {
		val desiredByKey = desired.associateBy(::keyOf)
		val currentByKey = current.associateBy(::keyOf)

		return SubscriptionReconciliation(
			add = (desiredByKey.keys - currentByKey.keys).map { desiredByKey.getValue(it) }.sortedWith(subscriptionComparator),
			delete = (currentByKey.keys - desiredByKey.keys).map { currentByKey.getValue(it) }.sortedWith(subscriptionComparator),
		)
	}

	private fun keyOf(subscription: WalletSubscription) = SubscriptionKey(
		walletId = subscription.walletId,
		chain = subscription.chain,
		address = subscription.address,
	)

	private val subscriptionComparator = compareBy<WalletSubscription>(
		{ it.walletId.value },
		{ it.chain.key },
		{ it.address },
	)
}
