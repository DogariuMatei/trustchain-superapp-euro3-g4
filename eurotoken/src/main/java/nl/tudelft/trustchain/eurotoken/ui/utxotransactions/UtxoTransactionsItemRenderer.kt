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
class UtxoTransactionsItemRenderer (
    private val onClick: (String) -> Unit
): ItemLayoutRenderer<UtxoTransactionsItem, View>(
    UtxoTransactionsItem::class.java
) {
    override fun bindView(
        item: UtxoTransactionsItem,
        view: View
    ) = with(view) {
        val binding = ItemUtxoTransactionBinding.bind(view)
        binding.txtGroupTxId.text = item.utxoTx.txId
        binding.txtSender.text = ("Sender: " + item.utxoTx.sender.toHex()).take(20) + "..."
        binding.txtReceiver.text = ("Recipient: " + item.utxoTx.recipient.toHex()).take(20) + "..."
        // Set click listener for the whole item
        setOnClickListener {
            onClick(item.utxoTx.txId)
        }
    }

    override fun getLayoutResourceId(): Int {
        return R.layout.item_utxo_transaction
    }
}
