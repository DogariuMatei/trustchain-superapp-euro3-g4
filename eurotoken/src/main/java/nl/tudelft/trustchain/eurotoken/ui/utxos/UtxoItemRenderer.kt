package nl.tudelft.trustchain.eurotoken.ui.utxos

import android.view.View
import com.mattskala.itemadapter.ItemLayoutRenderer
import nl.tudelft.trustchain.common.eurotoken.UTXOService
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.ItemUtxoBinding
import nl.tudelft.trustchain.eurotoken.entity.UTXO

/**
 * [UtxoItemRenderer] used by the [UtxosFragment] to render the [UTXO] items as a list.
 */
class UtxoItemRenderer : ItemLayoutRenderer<UtxoItem, View>(
    UtxoItem::class.java
) {
    override fun bindView(
        item: UtxoItem,
        view: View
    ) = with(view) {
        val binding = ItemUtxoBinding.bind(view)
        binding.txtTxId.text = item.utxo.txId
        binding.txtTxIndex.text = item.utxo.txIndex.toString()
        binding.txtAmount.text = UTXOService.prettyAmount(item.utxo.amount.toLong())
    }

    override fun getLayoutResourceId(): Int {
        return R.layout.item_utxo
    }
}
