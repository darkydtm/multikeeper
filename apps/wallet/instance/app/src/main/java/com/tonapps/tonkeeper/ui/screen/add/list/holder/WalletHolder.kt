package com.tonapps.tonkeeper.ui.screen.add.list.holder

import uikit.extensions.setHapticClickListener

import android.view.ViewGroup
import com.tonapps.tonkeeper.ui.screen.add.list.Item
import uikit.widget.ActionCellView

class WalletHolder(
    parent: ViewGroup,
    private val onClick: (Item.Wallet) -> Unit,
): Holder<Item.Wallet>(ActionCellView(parent.context)) {

    private val itemActionView = itemView as ActionCellView

    override fun onBind(item: Item.Wallet) {
        itemActionView.setHapticClickListener { onClick(item) }
        itemActionView.iconRes = item.iconResId
		itemActionView.title = item.titleText ?: getString(item.titleResId)
		itemActionView.subtitle = item.subtitleText ?: getString(item.subtitleResId)
    }

}
