package nl.tudelft.trustchain.eurotoken.ui.utxotransactions

import com.mattskala.itemadapter.Item
import nl.tudelft.trustchain.common.eurotoken.UTXOTransaction

/**
 * [UtxoTransactionsItem] used by the [UtxoTransactionsItemRenderer] to render the list of [UTXOTransaction]s.
 */
class UtxoTransactionsItem(val utxoTx: UTXOTransaction) : Item() {
    override fun areItemsTheSame(other: Item): Boolean {
        return other is UtxoTransactionsItem && utxoTx == other.utxoTx
    }
}
