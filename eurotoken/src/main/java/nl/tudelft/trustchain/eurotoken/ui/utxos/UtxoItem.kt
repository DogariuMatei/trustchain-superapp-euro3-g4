package nl.tudelft.trustchain.eurotoken.ui.utxos

import com.mattskala.itemadapter.Item
import nl.tudelft.trustchain.common.eurotoken.UTXO

/**
 * [UtxoItem] used by the [UtxoItemRenderer] to render the list of [UTXO]s.
 */
class UtxoItem(val utxo: UTXO) : Item() {
    override fun areItemsTheSame(other: Item): Boolean {
        return other is UtxoItem && utxo == other.utxo
    }
}
