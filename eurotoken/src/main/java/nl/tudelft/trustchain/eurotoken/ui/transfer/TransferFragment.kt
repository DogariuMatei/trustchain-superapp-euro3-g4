package nl.tudelft.trustchain.eurotoken.ui.transfer

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.contacts.ContactStore
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.EuroTokenMainActivity
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentTransferEuroBinding
import nl.tudelft.trustchain.eurotoken.ui.EurotokenNFCBaseFragment
import nl.tudelft.trustchain.eurotoken.ui.components.NFCActivationDialog
import org.json.JSONException
import org.json.JSONObject

class TransferFragment : EurotokenNFCBaseFragment(R.layout.fragment_transfer_euro) {
    private val binding by viewBinding(FragmentTransferEuroBinding::bind)
    private var isNFCReceiveModeActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launchWhenResumed {
            while (isActive) {
                updateBalanceDisplay()
                delay(1000L)
            }
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
        setupButtonListeners()
    }

    private fun setupUI() {
        val ownKey = transactionRepository.trustChainCommunity.myPeer.publicKey
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
    }

    private fun updateBalanceDisplay() {
        val ownKey = transactionRepository.trustChainCommunity.myPeer.publicKey
        val ownContact = ContactStore.getInstance(requireContext()).getContactFromPublicKey(ownKey)

        val pref = requireContext().getSharedPreferences(
            EuroTokenMainActivity.EurotokenPreferences.EUROTOKEN_SHARED_PREF_NAME,
            Context.MODE_PRIVATE
        )
        val demoModeEnabled = pref.getBoolean(
            EuroTokenMainActivity.EurotokenPreferences.DEMO_MODE_ENABLED,
            false
        )

        val balance = if (demoModeEnabled) {
            transactionRepository.getMyBalance()
        } else {
            transactionRepository.getMyVerifiedBalance()
        }

        binding.txtBalance.text = TransactionRepository.prettyAmount(balance)

        if (ownContact?.name != null) {
            binding.missingNameLayout.visibility = View.GONE
            binding.txtOwnName.text = "Your balance (${ownContact.name})"
        } else {
            binding.missingNameLayout.visibility = View.VISIBLE
            binding.txtOwnName.text = "Your balance"
        }
    }

    private fun setupButtonListeners() {
        // Add Name functionality
        binding.btnAdd.setOnClickListener {
            addName()
        }

        binding.edtMissingName.onSubmit {
            addName()
        }

        // Request Payment Button - Phase 1 NFC Transaction
        binding.btnRequest.setOnClickListener {
            val amount = getAmount(binding.edtAmount.text.toString())
            if (amount > 0) {
                initiatePaymentRequest(amount)
            } else {
                Toast.makeText(requireContext(), "Please specify a positive amount", Toast.LENGTH_SHORT).show()
            }
        }

        // Activate NFC Receive Button - Updated button functionality
        binding.btnSend.setOnClickListener {
            toggleNFCReceiveMode()
        }
    }

    private fun addName() {
        val newName = binding.edtMissingName.text.toString()
        if (newName.isNotEmpty()) {
            val ownKey = transactionRepository.trustChainCommunity.myPeer.publicKey
            ContactStore.getInstance(requireContext()).addContact(ownKey, newName)

            binding.missingNameLayout.visibility = View.GONE
            binding.txtOwnName.text = "Your balance ($newName)"

            val inputMethodManager =
                requireContext().getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(view?.windowToken, 0)
        }
    }

    /**
     * Initiate a payment request - Phase 1 of the NFC transaction
     */
    private fun initiatePaymentRequest(amount: Long) {
        val myPeer = transactionRepository.trustChainCommunity.myPeer
        val ownKey = myPeer.publicKey
        val contact = ContactStore.getInstance(requireContext()).getContactFromPublicKey(ownKey)

        val paymentRequest = JSONObject()
        paymentRequest.put("type", "payment_request")
        paymentRequest.put("public_key", myPeer.publicKey.keyToBin().toHex())
        paymentRequest.put("amount", amount)
        paymentRequest.put("requester_name", contact?.name ?: "")
        paymentRequest.put("timestamp", System.currentTimeMillis())

        // Navigate to NFC waiting screen for payment request
        navigateToNFCRequestScreen(paymentRequest.toString())
    }

    /**
     * Toggle NFC receive mode
     */
    private fun toggleNFCReceiveMode() {
        if (isNFCReceiveModeActive) {
            deactivateNFCReceiveMode()
        } else {
            activateNFCReceiveMode()
        }
    }

    /**
     * Activate NFC receive mode for incoming payment requests
     */
    private fun activateNFCReceiveMode() {
        isNFCReceiveModeActive = true
        updateButtonStates()

        activateNFCReceive("payment_request", 60) // 60 second timeout

        Toast.makeText(
            requireContext(),
            "Ready to receive payment request. Ask the requester to tap phones when ready.",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Deactivate NFC receive mode
     */
    private fun deactivateNFCReceiveMode() {
        isNFCReceiveModeActive = false
        updateButtonStates()
        dismissNFCDialog()

        Toast.makeText(
            requireContext(),
            "NFC receive mode deactivated",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Update button states based on NFC mode
     */
    private fun updateButtonStates() {
        if (isNFCReceiveModeActive) {
            binding.btnSend.text = "Cancel NFC"
            binding.btnSend.setBackgroundColor(resources.getColor(R.color.nfc_error, null))
        } else {
            binding.btnSend.text = "Activate NFC"
            binding.btnSend.setBackgroundColor(resources.getColor(R.color.colorPrimary, null))
        }
    }

    /**
     * Navigate to NFC request waiting screen
     */
    private fun navigateToNFCRequestScreen(paymentRequestData: String) {
        val args = Bundle()
        args.putString(RequestMoneyFragment.ARG_DATA, paymentRequestData)
        findNavController().navigate(
            R.id.action_transferFragment_to_requestMoneyFragment,
            args
        )
    }

    /**
     * Handle received NFC data - This handles both Phase 1 and Phase 2 data
     */
    override fun onNFCDataReceived(jsonData: String) {
        try {
            val receivedData = JSONObject(jsonData)
            val dataType = receivedData.optString("type")

            when (dataType) {
                "payment_request" -> {
                    // Received Phase 1 - Payment Request from Requester
                    handlePaymentRequest(receivedData)
                }
                "payment_confirmation" -> {
                    // Received Phase 2 - Payment Confirmation from Sender
                    handlePaymentConfirmation(receivedData)
                }
                else -> {
                    updateNFCState(NFCState.ERROR)
                    Toast.makeText(requireContext(), "Invalid transaction data received", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: JSONException) {
            updateNFCState(NFCState.ERROR)
            onNFCReadError("Invalid transaction data format")
        }
    }

    /**
     * Handle Phase 1 - Payment Request received from Requester
     */
    private fun handlePaymentRequest(paymentRequest: JSONObject) {
        val amount = paymentRequest.optLong("amount", -1L)
        val publicKey = paymentRequest.optString("public_key")
        val requesterName = paymentRequest.optString("requester_name")

        if (amount <= 0 || publicKey.isEmpty()) {
            updateNFCState(NFCState.ERROR)
            Toast.makeText(requireContext(), "Invalid payment request data", Toast.LENGTH_LONG).show()
            return
        }

        updateNFCState(NFCState.SUCCESS)
        dismissNFCDialog()
        deactivateNFCReceiveMode()

        // Navigate to SendMoneyFragment to review the transaction
        val args = Bundle()
        args.putString(SendMoneyFragment.ARG_PUBLIC_KEY, publicKey)
        args.putLong(SendMoneyFragment.ARG_AMOUNT, amount)
        args.putString(SendMoneyFragment.ARG_NAME, requesterName)

        findNavController().navigate(
            R.id.action_transferFragment_to_sendMoneyFragment,
            args
        )
    }

    /**
     * Handle Phase 2 - Payment Confirmation received from Sender
     */
    private fun handlePaymentConfirmation(paymentConfirmation: JSONObject) {
        // TODO: Process the actual transaction data
        // This will be implemented in Phase 4 of the plan

        updateNFCState(NFCState.SUCCESS)

        Toast.makeText(requireContext(), "Payment received successfully!", Toast.LENGTH_LONG).show()

        // Auto-dismiss success dialog and navigate
        dismissNFCDialog()
        findNavController().navigate(R.id.transactionsFragment)
    }

    override fun onNFCReadError(error: String) {
        super.onNFCReadError(error)
        updateNFCState(NFCState.ERROR)
        Toast.makeText(requireContext(), "Failed to read transaction data: $error", Toast.LENGTH_LONG).show()
    }

    override fun onNFCOperationCancelled() {
        super.onNFCOperationCancelled()
        deactivateNFCReceiveMode()
    }

    override fun onNFCRetryRequested() {
        super.onNFCRetryRequested()
        if (isNFCReceiveModeActive) {
            activateNFCReceiveMode()
        }
    }

    override fun onNFCTimeout() {
        super.onNFCTimeout()
        deactivateNFCReceiveMode()
    }

    companion object {
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
