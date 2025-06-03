package nl.tudelft.trustchain.eurotoken.ui.utxos

import android.view.View
import com.mattskala.itemadapter.ItemLayoutRenderer
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.contacts.ContactStore
import nl.tudelft.trustchain.common.eurotoken.UTXOService
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.ItemUtxoBinding
import nl.tudelft.trustchain.eurotoken.entity.UTXO

/**
 * [UtxoItemRenderer] used by the [UtxosFragment] to render the [UTXO] items as a list.
 */
class UtxoItemRenderer () : ItemLayoutRenderer<UtxoItem, View>(
    UtxoItem::class.java
) {
    override fun bindView(
        item: UtxoItem,
        view: View
    ) = with(view) {
        val binding = ItemUtxoBinding.bind(view)
        binding.txtTxIndex.text = "#" + item.utxo.txIndex.toString()
        binding.txtAmount.text = UTXOService.prettyAmount(item.utxo.amount.toLong())
        binding.txtOwner.text = ("Owner: " + item.utxo.owner.toHex()).take(20) + "..."
        binding.txtSpentStatus.text = if (item.utxo.spentInTxId != null) {
            "Spent"
        } else {
            "Unspent"
        }
    }

    override fun getLayoutResourceId(): Int {
        return R.layout.item_utxo
    }
}
