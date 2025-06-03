package nl.tudelft.trustchain.eurotoken.ui.utxos

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.liveData
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.mattskala.itemadapter.Item
import com.mattskala.itemadapter.ItemAdapter
import kotlinx.coroutines.delay
import nl.tudelft.trustchain.common.contacts.ContactStore
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentUtxosBinding
import nl.tudelft.trustchain.common.eurotoken.UTXO
import nl.tudelft.trustchain.eurotoken.ui.EurotokenNFCBaseFragment

/**
 * A simple [Fragment] subclass.
 * Use the [UtxosFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class UtxosFragment : EurotokenNFCBaseFragment(R.layout.fragment_utxos) {
    private val binding by viewBinding(FragmentUtxosBinding::bind)

    private val adapter = ItemAdapter()

    private val items: LiveData<List<Item>> by lazy {
        liveData { emit(listOf<Item>()) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        adapter.registerRenderer(UtxoItemRenderer())

        lifecycleScope.launchWhenResumed {
            val txId = arguments?.getString("transactionId")
            val items =
                utxoService.getUtxosById(txId!!)
                    .map { utxo: UTXO -> UtxoItem(utxo) }
            Log.e("UtxosFragment", "Loaded ${items.size} UTXOs")
            adapter.updateItems(items)
            adapter.notifyDataSetChanged()

            binding.txtTransactionId.text = txId
            delay(1000L)
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        binding.utxosRecyclerView.adapter = adapter
        binding.utxosRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.utxosRecyclerView.addItemDecoration(
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
