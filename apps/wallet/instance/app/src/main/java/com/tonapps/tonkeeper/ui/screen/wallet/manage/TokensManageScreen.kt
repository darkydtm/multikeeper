package com.tonapps.tonkeeper.ui.screen.wallet.manage

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.tonapps.tonkeeper.koin.walletViewModel
import com.tonapps.tonkeeper.ui.base.BaseListWalletScreen
import com.tonapps.tonkeeper.ui.base.ScreenContext
import com.tonapps.tonkeeper.ui.screen.wallet.manage.list.Adapter
import com.tonapps.tonkeeper.ui.screen.wallet.manage.list.Item
import com.tonapps.tonkeeper.ui.screen.wallet.manage.list.holder.Holder
import com.tonapps.tonkeeper.ui.screen.wallet.manage.list.holder.TokenHolder
import com.tonapps.blockchain.model.legacy.WalletEntity
import com.tonapps.wallet.localization.Localization
import com.tonapps.wallet.data.gem.Chain as GemChain
import com.tonapps.wallet.data.gem.GemTokenImport
import com.tonapps.wallet.data.gem.WalletId as GemWalletId
import com.tonapps.tonkeeperx.R
import uikit.HapticHelper
import uikit.base.BaseFragment
import uikit.extensions.collectFlow
import uikit.extensions.getDimensionPixelSize

class TokensManageScreen(wallet: WalletEntity): BaseListWalletScreen<ScreenContext.Wallet>(ScreenContext.Wallet(wallet)), BaseFragment.BottomSheet {

    override val fragmentName: String = "TokensManageScreen"

    override val viewModel: TokensManageViewModel by walletViewModel()

    private val adapter: Adapter by lazy {
        Adapter(viewModel::onPinChange, viewModel::onHiddenChange, ::onDrag)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collectFlow(viewModel.uiItemsFlow, adapter::submitList)
    }

    private fun onDrag(holder: TokenHolder) {
        getTouchHelper()?.startDrag(holder)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setTitle(getString(Localization.home_screen))
        if (viewModel.isGemWallet) {
            headerView.setRightButton(Localization.gem_import_token, ::showGemTokenImport)
        }
        applyListMargin(top = requireContext().getDimensionPixelSize(uikit.R.dimen.barHeight))
        setAdapter(adapter)
        val horizontalOffset = requireContext().getDimensionPixelSize(uikit.R.dimen.cornerMedium)
        setListPadding(horizontalOffset, 0, horizontalOffset, 0)
        setTouchHelperCallback(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {

            override fun isLongPressDragEnabled() = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val item = (viewHolder as? Holder<*>)?.item ?: return false
                if (item is Item.Token && item.pinned) {
                    adapter.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    HapticHelper.impactLight(requireContext())
                    return true
                }
                return false
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                val item = (viewHolder as? Holder<*>)?.item ?: return
                if (item is Item.Token && item.pinned) {
                    viewModel.changeOrder(item.address, viewHolder.bindingAdapterPosition)
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) { }

        })
    }

    private fun showGemTokenImport() {
        val content = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_gem_token_import, null)
        val chainView = content.findViewById<Spinner>(R.id.chain)
        val chains = GemChain.entries.filter { it != GemChain.Bitcoin && it != GemChain.Ton }
        chainView.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            chains.map { it.key },
        )
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(Localization.gem_import_token)
            .setView(content)
            .setNegativeButton(Localization.cancel, null)
            .setPositiveButton(Localization.gem_import_action, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val input = GemTokenImport(
                    walletId = GemWalletId(screenContext.wallet.id),
                    chain = chains[chainView.selectedItemPosition],
                    assetId = content.findViewById<EditText>(R.id.asset_id).text.toString(),
                    name = content.findViewById<EditText>(R.id.name).text.toString(),
                    symbol = content.findViewById<EditText>(R.id.symbol).text.toString(),
                    decimals = content.findViewById<EditText>(R.id.decimals).text.toString(),
                )
                viewModel.importGemToken(
                    input = input,
                    onSuccess = dialog::dismiss,
                    onError = { error ->
                        navigation?.toast(getString(Localization.gem_import_invalid, error.message.orEmpty()))
                    },
                )
            }
        }
        dialog.show()
    }

    companion object {
        fun newInstance(wallet: WalletEntity) = TokensManageScreen(wallet)
    }
}
