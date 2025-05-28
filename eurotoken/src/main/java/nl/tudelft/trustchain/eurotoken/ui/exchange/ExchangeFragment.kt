package nl.tudelft.trustchain.eurotoken.ui.exchange

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.contacts.ContactStore
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import nl.tudelft.trustchain.eurotoken.EuroTokenMainActivity
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentExchangeBinding
import nl.tudelft.trustchain.eurotoken.ui.EurotokenNFCBaseFragment
import nl.tudelft.trustchain.eurotoken.ui.transfer.TransferFragment
import org.json.JSONException
import org.json.JSONObject

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class ExchangeFragment : EurotokenNFCBaseFragment() {
    @Suppress("ktlint:standard:property-naming") // False positive
    private var _binding: FragmentExchangeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExchangeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launchWhenResumed {
            while (isActive) {
                val ownKey = transactionRepository.trustChainCommunity.myPeer.publicKey
                val ownContact =
                    ContactStore.getInstance(requireContext()).getContactFromPublicKey(ownKey)

                val pref =
                    requireContext().getSharedPreferences(
                        EuroTokenMainActivity.EurotokenPreferences.EUROTOKEN_SHARED_PREF_NAME,
                        Context.MODE_PRIVATE
                    )
                val demoModeEnabled =
                    pref.getBoolean(
                        EuroTokenMainActivity.EurotokenPreferences.DEMO_MODE_ENABLED,
                        false
                    )

                if (demoModeEnabled) {
                    binding.txtBalance.text =
                        TransactionRepository.prettyAmount(transactionRepository.getMyBalance())
                } else {
                    binding.txtBalance.text =
                        TransactionRepository.prettyAmount(transactionRepository.getMyVerifiedBalance())
                }
                if (ownContact?.name != null) {
                    binding.txtOwnName.text = "Your balance (" + ownContact.name + ")"
                }
                delay(1000L)
            }
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)
        binding.txtOwnPublicKey.text = getTrustChainCommunity().myPeer.publicKey.keyToHash().toHex()

        // NFC Gateway Connection instead of QR scanning
        binding.btnCamera.setOnClickListener {
            startNFCGatewayReceive()
        }

        binding.edtAmount.addDecimalLimiter()

        binding.btnInstaSell.setOnClickListener {
            val amount = TransferFragment.getAmount(binding.edtAmount.text.toString())
            if (amount > 0) {
                val iban = binding.edtIBAN.text.toString()
                transactionRepository.sendDestroyProposalWithIBAN(iban, amount)
                findNavController().navigate(R.id.action_exchangeFragment_to_transactionsFragment)
            } else {
                Toast.makeText(
                    requireContext(),
                    "Please specify a positive amount",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Start NFC to receive gateway data
     */
    private fun startNFCGatewayReceive() {
        Toast.makeText(
            requireContext(),
            "Ready to connect to gateway. Hold phones together when ready.",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Handle received NFC gateway data
     */
    fun onNFCDataReceived(jsonData: String) {
        try {
            val connectionData = ConnectionData(jsonData)
            Toast.makeText(requireContext(), "Connected to ${connectionData.name}", Toast.LENGTH_LONG).show()

            when (connectionData.type) {
                "destruction" -> {
                    val args = Bundle()
                    args.putString(DestroyMoneyFragment.ARG_PUBLIC_KEY, connectionData.publicKey)
                    args.putString(DestroyMoneyFragment.ARG_NAME, connectionData.name)
                    args.putLong(DestroyMoneyFragment.ARG_AMOUNT, connectionData.amount)
                    args.putInt(DestroyMoneyFragment.ARG_PORT, connectionData.port)
                    args.putString(DestroyMoneyFragment.ARG_IP, connectionData.ip)
                    args.putString(DestroyMoneyFragment.ARG_PAYMENT_ID, connectionData.paymentId)
                    findNavController().navigate(
                        R.id.action_exchangeFragment_to_destroyMoneyFragment,
                        args
                    )
                }

                "creation" -> {
                    val args = Bundle()
                    args.putString(CreateMoneyFragment.ARG_PUBLIC_KEY, connectionData.publicKey)
                    args.putString(CreateMoneyFragment.ARG_NAME, connectionData.name)
                    args.putInt(CreateMoneyFragment.ARG_PORT, connectionData.port)
                    args.putString(CreateMoneyFragment.ARG_IP, connectionData.ip)
                    args.putString(CreateMoneyFragment.ARG_PAYMENT_ID, connectionData.paymentId)
                    findNavController().navigate(
                        R.id.action_exchangeFragment_to_createMoneyFragment,
                        args
                    )
                }

                else -> {
                    Toast.makeText(requireContext(), "Invalid gateway data", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: JSONException) {
            onNFCReadError("Invalid gateway data format: $e")
        }
    }

    override fun onNFCReadError(error: String) {
        super.onNFCReadError(error)
        Toast.makeText(requireContext(), "Failed to read gateway data: $error", Toast.LENGTH_LONG).show()
    }

    /**
     * Connection class for parsing NFC data
     */
    class ConnectionData(json: String) : JSONObject(json) {
        val paymentId = this.optString("payment_id")
        val publicKey = this.optString("public_key")
        val ip = this.optString("ip")
        val name = this.optString("name")
        val port = this.optInt("port")
        val amount = this.optLong("amount", -1L)
        val type = this.optString("type")
    }

    companion object {
        fun EditText.decimalLimiter(string: String): String {
            val amount = TransferFragment.getAmount(string)

            if (amount == 0L) {
                return ""
            }

            return (amount / 100).toString() + "." + (amount % 100).toString().padStart(2, '0')
        }

        fun EditText.addDecimalLimiter() {
            this.addTextChangedListener(
                object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        val str = this@addDecimalLimiter.text!!.toString()
                        if (str.isEmpty()) return
                        val str2 = decimalLimiter(str)

                        if (str2 != str) {
                            this@addDecimalLimiter.setText(str2)
                            val pos = this@addDecimalLimiter.text!!.length
                            this@addDecimalLimiter.setSelection(pos)
                        }
                    }

                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int
                    ) {
                    }

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int
                    ) {
                    }
                }
            )
        }
    }
}
