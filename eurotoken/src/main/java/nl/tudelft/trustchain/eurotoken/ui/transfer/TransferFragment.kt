package nl.tudelft.trustchain.eurotoken.ui.transfer

import android.util.Log
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
import nl.tudelft.trustchain.common.eurotoken.UTXOService
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.EuroTokenMainActivity
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentTransferEuroBinding
import nl.tudelft.trustchain.eurotoken.ui.EurotokenNFCBaseFragment
import org.json.JSONException
import org.json.JSONObject

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@SuppressLint("SetTextI18n")
class TransferFragment : EurotokenNFCBaseFragment(R.layout.fragment_transfer_euro) {

    companion object {
        private const val TAG = "TransferFragment"

        fun EditText.onSubmit(func: () -> Unit) {
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    func()
                }
                true
            }
        }

        fun getAmount(amount: String): Long {
            val regex = """[^\d]""".toRegex()
            if (amount.isEmpty()) {
                return 0L
            }
            return regex.replace(amount, "").toLong()
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

    private val binding by viewBinding(FragmentTransferEuroBinding::bind)

    // Track which phase we're in
    private enum class TransactionPhase {
        IDLE,
        WAITING_PHASE1
    }

    private var currentPhase = TransactionPhase.IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "=== TRANSFER FRAGMENT CREATED ===")

        lifecycleScope.launchWhenResumed {
            while (isActive) {
                updateBalanceDisplay()
                delay(1000L)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "=== TRANSFER FRAGMENT VIEW CREATED ===")

        setupUI()
        setupButtonListeners()
    }

    private fun setupUI() {
        val ownKey = utxoService.trustChainCommunity.myPeer.publicKey
        val ownContact = ContactStore.getInstance(requireContext()).getContactFromPublicKey(ownKey)

        binding.txtOwnPublicKey.text = ownKey.keyToHash().toHex()
        updateBalanceDisplay()

        if (ownContact?.name != null) {
            binding.missingNameLayout.visibility = View.GONE
            binding.txtOwnName.text = "Your balance (${ownContact.name})"
        } else {
            binding.missingNameLayout.visibility = View.VISIBLE
            binding.txtOwnName.text = "Your balance"
        }

        binding.edtAmount.addDecimalLimiter()
        updateButtonStates()
    }

    private fun updateBalanceDisplay() {
        val ownKey = utxoService.trustChainCommunity.myPeer.publicKey
        val ownContact = ContactStore.getInstance(requireContext()).getContactFromPublicKey(ownKey)

        val pref = requireContext().getSharedPreferences(
            EuroTokenMainActivity.EurotokenPreferences.EUROTOKEN_SHARED_PREF_NAME,
            Context.MODE_PRIVATE
        )
        val demoModeEnabled = pref.getBoolean(
            EuroTokenMainActivity.EurotokenPreferences.DEMO_MODE_ENABLED,
            false
        )

        val balance = utxoService.getMyBalance()

        binding.txtBalance.text = UTXOService.prettyAmount(balance)

        if (ownContact?.name != null) {
            binding.missingNameLayout.visibility = View.GONE
            binding.txtOwnName.text = "Your balance (${ownContact.name})"
        } else {
            binding.missingNameLayout.visibility = View.VISIBLE
            binding.txtOwnName.text = "Your balance"
        }
    }

    private fun setupButtonListeners() {
        binding.btnAdd.setOnClickListener {
            addName()
        }

        binding.edtMissingName.onSubmit {
            addName()
        }

        // Send Payment Button - Navigate to SendMoneyFragment with amount
        binding.btnSend.setOnClickListener {
            val amount = getAmount(binding.edtAmount.text.toString())
            if (amount > 0) {
                Log.d(TAG, "Send Payment clicked for amount: $amount")
                navigateToSendMoneyScreen(amount)
            } else {
                Toast.makeText(requireContext(), "Please specify a positive amount", Toast.LENGTH_SHORT).show()
            }
        }

        // Activate NFC Button - Only for Phase 1 receiving
        binding.btnActivateNFC.setOnClickListener {
            when (currentPhase) {
                TransactionPhase.IDLE -> activateNFCReceive()
                TransactionPhase.WAITING_PHASE1 -> deactivateNFCReceive()
            }
        }
    }

    /**
     * Navigate to SendMoneyFragment with amount
     */
    private fun navigateToSendMoneyScreen(amount: Long) {
        val args = Bundle()
        args.putLong(SendMoneyFragment.ARG_AMOUNT, amount)
        findNavController().navigate(
            R.id.action_transferFragment_to_sendMoneyFragment,
            args
        )
    }

    /**
     * Activate NFC reader mode to receive Phase 1 sender data
     */
    private fun activateNFCReceive() {
        Log.d(TAG, "=== ACTIVATE NFC RECEIVE ===")
        currentPhase = TransactionPhase.WAITING_PHASE1
        updateButtonStates()

        Toast.makeText(
            requireContext(),
            "Ready to receive payment details. Ask the sender to tap phones.",
            Toast.LENGTH_LONG
        ).show()

        startHCEReaderMode(
            message = "Waiting for payment details...",
            timeoutSeconds = 60,
            onDataReceived = { jsonData ->
                Log.d(TAG, "Received sender data: ${jsonData.take(100)}...")
                handleReceivedSenderData(jsonData)
            }
        )
    }

    /**
     * Handle received sender data - Navigate to ReceiveMoneyFragment
     */
    private fun handleReceivedSenderData(jsonData: String) {
        Log.d(TAG, "=== HANDLE RECEIVED SENDER DATA ===")

        try {
            val senderData = JSONObject(jsonData)
            val dataType = senderData.optString("type")

            Log.d(TAG, "Received data type: $dataType")

            if (dataType == "sender_info") {
                deactivateNFCReceive()

                // Navigate to ReceiveMoneyFragment for review
                val args = Bundle()
                args.putString(ReceiveMoneyFragment.ARG_DATA, jsonData)
                findNavController().navigate(
                    R.id.action_transferFragment_to_receiveMoneyFragment,
                    args
                )
            } else {
                Log.e(TAG, "Unknown data type: $dataType")
                Toast.makeText(requireContext(), "Invalid payment data received", Toast.LENGTH_LONG).show()
            }
        } catch (e: JSONException) {
            Log.e(TAG, "JSON parsing error: ${e.message}")
            onNFCReadError("Invalid payment data format")
        }
    }

    /**
     * Deactivate NFC receive mode
     */
    private fun deactivateNFCReceive() {
        Log.d(TAG, "=== DEACTIVATE NFC RECEIVE ===")
        currentPhase = TransactionPhase.IDLE
        updateButtonStates()

        Toast.makeText(
            requireContext(),
            "NFC receive mode deactivated",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Update button text and states based on current phase
     */
    private fun updateButtonStates() {
        when (currentPhase) {
            TransactionPhase.IDLE -> {
                binding.btnActivateNFC.text = "Activate NFC"
                binding.btnActivateNFC.setBackgroundColor(resources.getColor(R.color.colorPrimary, null))
            }
            TransactionPhase.WAITING_PHASE1 -> {
                binding.btnActivateNFC.text = "Cancel NFC"
                binding.btnActivateNFC.setBackgroundColor(resources.getColor(R.color.red, null))
            }
        }
    }

    private fun addName() {
        val newName = binding.edtMissingName.text.toString()
        if (newName.isNotEmpty()) {
            val ownKey = utxoService.trustChainCommunity.myPeer.publicKey
            ContactStore.getInstance(requireContext()).addContact(ownKey, newName)

            binding.missingNameLayout.visibility = View.GONE
            binding.txtOwnName.text = "Your balance ($newName)"

            val inputMethodManager =
                requireContext().getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(view?.windowToken, 0)
        }
    }

    override fun onNFCReadError(error: String) {
        super.onNFCReadError(error)
        Log.e(TAG, "NFC Read Error: $error")
        Toast.makeText(requireContext(), "Failed to read transaction data: $error", Toast.LENGTH_LONG).show()
    }

    override fun onNFCOperationCancelled() {
        super.onNFCOperationCancelled()
        Log.d(TAG, "NFC operation cancelled by user")
        deactivateNFCReceive()
    }

    override fun onNFCTimeout() {
        super.onNFCTimeout()
        Log.w(TAG, "NFC operation timed out")
        deactivateNFCReceive()
    }
}
