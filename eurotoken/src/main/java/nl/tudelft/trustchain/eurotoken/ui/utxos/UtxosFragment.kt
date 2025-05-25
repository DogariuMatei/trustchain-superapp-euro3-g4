package nl.tudelft.trustchain.eurotoken.ui.utxos

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.liveData
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.mattskala.itemadapter.Item
import com.mattskala.itemadapter.ItemAdapter
import kotlinx.coroutines.delay
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentUtxoTransactionsBinding
import nl.tudelft.trustchain.eurotoken.ui.EurotokenBaseFragment
import nl.tudelft.trustchain.eurotoken.databinding.FragmentUtxosBinding
import nl.tudelft.trustchain.eurotoken.entity.UTXO

/**
 * A simple [Fragment] subclass.
 * Use the [UtxosFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
// TODO: Change to fragment_utxos after aggregating txIds
class UtxosFragment : EurotokenBaseFragment(R.layout.fragment_utxo_transactions) {
    /*private val binding by viewBinding(FragmentUtxosBinding::bind)*/
    private val binding by viewBinding(FragmentUtxoTransactionsBinding::bind)

    private val adapter = ItemAdapter()

    private val items: LiveData<List<Item>> by lazy {
        liveData { emit(listOf<Item>()) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        adapter.registerRenderer(UtxoItemRenderer())

        lifecycleScope.launchWhenResumed {
            val items =
                // TODO: Change this to use *getUtxosById* since this will be part of a second page for the TxIndexs
                utxoStore.getAllUtxos()
                    .map { Utxo: UTXO -> UtxoItem(Utxo) }
            adapter.updateItems(items)
            adapter.notifyDataSetChanged()
            delay(1000L)
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        /*binding.utxosRecyclerView.adapter = adapter
        binding.utxosRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.utxosRecyclerView.addItemDecoration(
            DividerItemDecoration(
                context,
                LinearLayout.VERTICAL
            )
        )*/
        binding.utxoTransactionsRecyclerView.adapter = adapter
        binding.utxoTransactionsRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.utxoTransactionsRecyclerView.addItemDecoration(
            DividerItemDecoration(
                context,
                LinearLayout.VERTICAL
            )
        )

        items.observe(viewLifecycleOwner) {
            adapter.updateItems(it)
        }
    }
}
