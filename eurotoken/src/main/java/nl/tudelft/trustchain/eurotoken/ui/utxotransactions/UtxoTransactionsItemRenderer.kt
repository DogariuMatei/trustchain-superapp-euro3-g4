package nl.tudelft.trustchain.eurotoken.ui.utxotransactions

import android.view.View
import com.mattskala.itemadapter.ItemLayoutRenderer
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.ItemUtxoTransactionBinding
import nl.tudelft.trustchain.eurotoken.entity.UTXO

/**
 * [UtxoTransactionsItemRenderer] used by the [UtxosTransactionsFragment] to render the [UTXO] items as a list.
 */
class UtxoTransactionsItemRenderer : ItemLayoutRenderer<UtxoTransactionsItem, View>(
    UtxoTransactionsItem::class.java
) {
    override fun bindView(
        item: UtxoTransactionsItem,
        view: View
    ) = with(view) {
        /*val binding = ItemUtxoBinding.bind(view)
        binding.txtTxIndex.text = item.utxo.txIndex.toString()
        binding.txtChildAmount.text = "€" + item.utxo.amount.toString()*/
        val binding = ItemUtxoTransactionBinding.bind(view)
        binding.txtGroupTxId.text = item.utxoTx.txId
        binding.txtSender.text = "Sender: " + item.utxoTx.sender.toHex()
        binding.txtReceiver.text = "Recipient: " + item.utxoTx.recipient.toHex()
    }

    override fun getLayoutResourceId(): Int {
        return R.layout.item_utxo_transaction
    }
}
