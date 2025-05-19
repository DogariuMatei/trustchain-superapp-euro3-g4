package nl.tudelft.trustchain.eurotoken.ui.transfer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.keyvault.defaultCryptoProvider
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.contacts.ContactStore
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.EuroTokenMainActivity
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.community.EuroTokenCommunity
import nl.tudelft.trustchain.eurotoken.databinding.FragmentTransferEuroBinding
import nl.tudelft.trustchain.eurotoken.ui.EurotokenNFCBaseFragment
import org.json.JSONException
import org.json.JSONObject

class TransferFragment : EurotokenNFCBaseFragment(R.layout.fragment_transfer_euro) {
    private val binding by viewBinding(FragmentTransferEuroBinding::bind)

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
                    binding.missingNameLayout.visibility = View.GONE
                    binding.txtOwnName.text = "Your balance (" + ownContact.name + ")"
                } else {
                    binding.missingNameLayout.visibility = View.VISIBLE
                    binding.txtOwnName.text = "Your balance"
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

        val ownKey = transactionRepository.trustChainCommunity.myPeer.publicKey
        val ownContact = ContactStore.getInstance(view.context).getContactFromPublicKey(ownKey)

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
        binding.txtOwnPublicKey.text = ownKey.keyToHash().toHex()

        if (ownContact?.name != null) {
            binding.missingNameLayout.visibility = View.GONE
            binding.txtOwnName.text = "Your balance (" + ownContact.name + ")"
        }

        fun addName() {
            val newName = binding.edtMissingName.text.toString()
            if (newName.isNotEmpty()) {
                ContactStore.getInstance(requireContext())
                    .addContact(ownKey, newName)
                if (ownContact?.name != null) {
                    binding.missingNameLayout.visibility = View.GONE
                    binding.txtOwnName.text = "Your balance (" + ownContact.name + ")"
                }
                val inputMethodManager =
                    requireContext().getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
                inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
            }
        }

        binding.btnAdd.setOnClickListener {
            addName()
        }

        binding.edtMissingName.onSubmit {
            addName()
        }

        binding.edtAmount.addDecimalLimiter()

        /**
         * Modified from original: Replaced QR generation with NFC writing
         */
        binding.btnRequest.setOnClickListener {
            val amount = getAmount(binding.edtAmount.text.toString())
            if (amount > 0) {
                createPaymentRequest(amount)
            } else {
                Toast.makeText(requireContext(), "Please specify a positive amount", Toast.LENGTH_SHORT).show()
            }
        }

        /**
         * Modified from original: Added NFC instructions
         */
        binding.btnSend.setOnClickListener {
            startNFCPaymentReceive()
        }
    }

    /**
     * Create a payment request and write it to NFC
     */
    private fun createPaymentRequest(amount: Long) {
        val myPeer = transactionRepository.trustChainCommunity.myPeer
        val ownKey = myPeer.publicKey
        val contact = ContactStore.getInstance(requireContext()).getContactFromPublicKey(ownKey)

        val connectionData = JSONObject()
        connectionData.put("public_key", myPeer.publicKey.keyToBin().toHex())
        connectionData.put("amount", amount)
        connectionData.put("name", contact?.name ?: "")
        connectionData.put("type", "transfer")

        // Write payment request to NFC
        writeToNFC(connectionData.toString()) { success ->
            if (success) {
                Toast.makeText(requireContext(), "Payment request ready! Ask the sender to tap phones.", Toast.LENGTH_LONG).show()
                navigateToNFCWaitingScreen(connectionData.toString())
            } else {
                Toast.makeText(requireContext(), "Failed to prepare payment request", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Start NFC for receiving payment
     */
    private fun startNFCPaymentReceive() {
        Toast.makeText(
            requireContext(),
            "Ready to receive payment. Ask the receiver to tap phones when ready.",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Navigate to NFC waiting screen (similar to RequestMoneyFragment)
     */
    private fun navigateToNFCWaitingScreen(paymentData: String) {
        val args = Bundle()
        args.putString(RequestMoneyFragment.ARG_DATA, paymentData)
        findNavController().navigate(
            R.id.action_transferFragment_to_requestMoneyFragment,
            args
        )
    }

    /**
     * Handle received NFC data
     */
    override fun onNFCDataReceived(jsonData: String) {
        try {
            val connectionData = ConnectionData(jsonData)

            val args = Bundle()
            args.putString(SendMoneyFragment.ARG_PUBLIC_KEY, connectionData.publicKey)
            args.putLong(SendMoneyFragment.ARG_AMOUNT, connectionData.amount)
            args.putString(SendMoneyFragment.ARG_NAME, connectionData.name)

            // Try to send the addresses of the last X transactions to the peer we received data from
            try {
                val peer = findPeer(
                    defaultCryptoProvider.keyFromPublicBin(connectionData.publicKey.hexToBytes()).toString()
                )
                if (peer == null) {
                    logger.warn { "Could not find peer from NFC data by public key " + connectionData.publicKey }
                    Toast.makeText(
                        requireContext(),
                        "Could not find peer from NFC data",
                        Toast.LENGTH_LONG
                    ).show()
                }
                val euroTokenCommunity = getIpv8().getOverlay<EuroTokenCommunity>()
                if (euroTokenCommunity == null) {
                    Toast.makeText(
                        requireContext(),
                        "Could not find community",
                        Toast.LENGTH_LONG
                    ).show()
                }
                if (peer != null && euroTokenCommunity != null) {
                    logger.info { "Note: Peer communication requires network connectivity" }
                }
            } catch (e: Exception) {
                logger.error { e }
                Toast.makeText(
                    requireContext(),
                    "Failed to process peer information",
                    Toast.LENGTH_LONG
                ).show()
            }

            if (connectionData.type == "transfer") {
                findNavController().navigate(
                    R.id.action_transferFragment_to_sendMoneyFragment,
                    args
                )
            } else {
                Toast.makeText(requireContext(), "Invalid payment request", Toast.LENGTH_LONG).show()
            }
        } catch (e: JSONException) {
            onNFCReadError("Invalid payment request format")
        }
    }

    override fun onNFCReadError(error: String) {
        super.onNFCReadError(error)
        Toast.makeText(requireContext(), "Failed to read payment request: $error", Toast.LENGTH_LONG).show()
    }

    /**
     * Find a [Peer] in the network by its public key.
     */
    private fun findPeer(pubKey: String): Peer? {
        val itr = transactionRepository.trustChainCommunity.getPeers().listIterator()
        while (itr.hasNext()) {
            val cur: Peer = itr.next()
            Log.d("EUROTOKEN", cur.key.pub().toString())
            if (cur.key.pub().toString() == pubKey) {
                return cur
            }
        }
        return null
    }

    companion object {
        private const val KEY_PUBLIC_KEY = "public_key"

        fun EditText.onSubmit(func: () -> Unit) {
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    func()
                }
                true
            }
        }

        class ConnectionData(json: String) : JSONObject(json) {
            var publicKey = this.optString("public_key")
            var amount = this.optLong("amount", -1L)
            var name = this.optString("name")
            var type = this.optString("type")
        }

        fun getAmount(amount: String): Long {
            val regex = """[^\d]""".toRegex()
            if (amount.isEmpty()) {
                return 0L
            }
            return regex.replace(amount, "").toLong()
        }

        fun Context.hideKeyboard(view: View) {
            val inputMethodManager =
                getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
        }

        fun EditText.decimalLimiter(string: String): String {
            var amount = getAmount(string)

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
                    ) {}
                }
            )
        }
    }
}
