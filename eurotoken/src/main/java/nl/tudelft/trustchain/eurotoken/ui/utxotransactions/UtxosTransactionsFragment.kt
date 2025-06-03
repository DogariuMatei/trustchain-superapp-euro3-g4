package nl.tudelft.trustchain.eurotoken.ui.utxotransactions

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
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.mattskala.itemadapter.Item
import com.mattskala.itemadapter.ItemAdapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentUtxoTransactionsBinding
import nl.tudelft.trustchain.common.eurotoken.UTXOService
import nl.tudelft.trustchain.eurotoken.ui.EurotokenNFCBaseFragment

/**
 * A simple [Fragment] subclass.
 * Use the [UtxosTransactionsFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class UtxosTransactionsFragment : EurotokenNFCBaseFragment(R.layout.fragment_utxo_transactions) {
    private val binding by viewBinding(FragmentUtxoTransactionsBinding::bind)

    private val adapter = ItemAdapter()

    private val items: LiveData<List<Item>> by lazy {
        liveData { emit(listOf<Item>()) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        adapter.registerRenderer(UtxoTransactionsItemRenderer { txId ->
            // Navigate to UtxosFragment, passing txId as argument
            val bundle = Bundle().apply {
                putString("transactionId", txId)
            }
            findNavController().navigate(R.id.action_utxosTransactionsFragment_to_utxosFragment, bundle)
        })
    }

    override fun onResume() {
        super.onResume()

        lifecycleScope.launch {
            val publicKey = utxoService.trustChainCommunity.myPeer.publicKey.keyToBin()
            val transactions = utxoService
                .getUtxoTransactionsByParticipation(publicKey)
                .map { utxoTx -> UtxoTransactionsItem(utxoTx) }

            Log.d("UtxosTransactionsFragment", "Loaded ${transactions.size} UTXO Transactions")
            adapter.updateItems(transactions)
            adapter.notifyDataSetChanged()

            binding.txtBalance.text = UTXOService.prettyAmount(utxoService.getMyBalance())
            binding.txtOwnPublicKey.text = publicKey.toHex()
            delay(1000L)
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

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
