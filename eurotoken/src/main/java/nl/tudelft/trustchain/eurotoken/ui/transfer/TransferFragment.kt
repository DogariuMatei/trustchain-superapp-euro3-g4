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
import nl.tudelft.ipv8.keyvault.defaultCryptoProvider
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.contacts.ContactStore
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
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
    private val binding by viewBinding(FragmentTransferEuroBinding::bind)

    // Track which phase we're in
    private enum class TransactionPhase {
        IDLE,           // No active transaction
        WAITING_PHASE1, // Waiting to receive payment request (Phase 1)
        WAITING_PHASE2  // Waiting to receive payment confirmation (Phase 2)
    }

    private var currentPhase = TransactionPhase.IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launchWhenResumed {
            while (isActive) {
                updateBalanceDisplay()
                delay(1000L)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        setupButtonListeners()

        // Check if we should activate Phase 2 immediately
        if (arguments?.getBoolean("activate_phase2") == true) {
            activatePhase2Receive()
            arguments?.remove("activate_phase2") // Clear the flag
        }
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
        updateButtonStates()
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
                Log.e("TransferFragment", "Payment Request Init success")
                initiatePaymentRequest(amount)
            } else {
                Toast.makeText(requireContext(), "Please specify a positive amount", Toast.LENGTH_SHORT).show()
            }
        }

        // NFC Receive Button - Context-aware for different phases
        binding.btnSend.setOnClickListener {
            when (currentPhase) {
                TransactionPhase.IDLE -> activatePhase1Receive()
                TransactionPhase.WAITING_PHASE1 -> deactivateNFCReceive()
                TransactionPhase.WAITING_PHASE2 -> deactivateNFCReceive()
            }
        }
    }

    /**
     * Update button text and states based on current phase
     */
    private fun updateButtonStates() {
        when (currentPhase) {
            TransactionPhase.IDLE -> {
                binding.btnSend.text = "Activate NFC"
                binding.btnSend.setBackgroundColor(resources.getColor(R.color.colorPrimary, null))
            }
            TransactionPhase.WAITING_PHASE1 -> {
                binding.btnSend.text = "Cancel NFC"
                binding.btnSend.setBackgroundColor(resources.getColor(R.color.red, null))
            }
            TransactionPhase.WAITING_PHASE2 -> {
                binding.btnSend.text = "Cancel NFC"
                binding.btnSend.setBackgroundColor(resources.getColor(R.color.red, null))
            }
        }
    }

    /**
     * Activate NFC to receive Phase 1 payment requests
     */
    private fun activatePhase1Receive() {
        currentPhase = TransactionPhase.WAITING_PHASE1
        updateButtonStates()

        activateNFCReceive("payment_request", 60)

        Toast.makeText(
            requireContext(),
            "Ready to receive payment request. Ask the requester to tap phones.",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Deactivate NFC receive mode
     */
    private fun deactivateNFCReceive() {
        currentPhase = TransactionPhase.IDLE
        updateButtonStates()
        dismissNFCDialog()

        Toast.makeText(
            requireContext(),
            "NFC receive mode deactivated",
            Toast.LENGTH_SHORT
        ).show()
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

        Log.e("TransferFragment", "Transfer to request fragment success")
        Log.e("TransferFragment", "Payload: ${paymentRequest.toString()}")
        // Navigate to NFC waiting screen for payment request
        navigateToNFCRequestScreen(paymentRequest.toString())
    }

    /**
     * Navigate to NFC request waiting screen, with transaction data in args
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
     * Handle received NFC data - Routes to appropriate phase handler
     */
    override fun onNFCDataReceived(jsonData: String) {
        try {
            val receivedData = JSONObject(jsonData)
            val dataType = receivedData.optString("type")

            when (dataType) {
                "payment_request" -> {
                    if (currentPhase == TransactionPhase.WAITING_PHASE1) {
                        handlePhase1PaymentRequest(receivedData)
                    } else {
                        Toast.makeText(requireContext(), "Not expecting payment request", Toast.LENGTH_SHORT).show()
                    }
                }
                "payment_confirmation" -> {
                    if (currentPhase == TransactionPhase.WAITING_PHASE2) {
                        handlePhase2PaymentConfirmation(receivedData)
                    } else {
                        Toast.makeText(requireContext(), "Not expecting payment confirmation", Toast.LENGTH_SHORT).show()
                    }
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
    private fun handlePhase1PaymentRequest(paymentRequest: JSONObject) {
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
        deactivateNFCReceive()

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
     * Process the actual transaction data for offline processing
     */
    private fun handlePhase2PaymentConfirmation(paymentConfirmation: JSONObject) {
        try {
            // Extract transaction data
            val senderPublicKey = paymentConfirmation.optString("sender_public_key")
            val senderName = paymentConfirmation.optString("sender_name")
            val amount = paymentConfirmation.optLong("amount", -1L)
            val blockHash = paymentConfirmation.optString("block_hash")
            val sequenceNumber = paymentConfirmation.optLong("sequence_number", -1L)
            val blockTimestamp = paymentConfirmation.optLong("block_timestamp", -1L)

            // Validate required data
            if (senderPublicKey.isEmpty() || amount <= 0 || blockHash.isEmpty() || sequenceNumber < 0) {
                updateNFCState(NFCState.ERROR)
                Toast.makeText(requireContext(), "Invalid transaction data received", Toast.LENGTH_LONG).show()
                return
            }

            // Process the offline transaction
            processOfflineTransaction(
                senderPublicKey = senderPublicKey,
                senderName = senderName,
                amount = amount,
                blockHash = blockHash,
                sequenceNumber = sequenceNumber,
                blockTimestamp = blockTimestamp
            )

            updateNFCState(NFCState.SUCCESS)

            val displayName = if (senderName.isNotEmpty()) senderName else "Unknown"
            Toast.makeText(
                requireContext(),
                "Payment of ${TransactionRepository.prettyAmount(amount)} received from $displayName!",
                Toast.LENGTH_LONG
            ).show()

            // Auto-dismiss success dialog and navigate
            dismissNFCDialog()
            deactivateNFCReceive()
            findNavController().navigate(R.id.transactionsFragment)

        } catch (e: Exception) {
            Log.e("TransferFragment", "Error processing payment confirmation: ${e.message}")
            updateNFCState(NFCState.ERROR)
            Toast.makeText(requireContext(), "Failed to process transaction: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Process offline transaction data received via NFC
     * Here is where the magic will happen
     */
    private fun processOfflineTransaction(
        senderPublicKey: String,
        senderName: String,
        amount: Long,
        blockHash: String,
        sequenceNumber: Long,
        blockTimestamp: Long
    ) {
        try {
            // Convert sender public key
            val senderKeyBytes = senderPublicKey.hexToBytes()

            // Update trust score for sender
            trustStore.incrementTrust(senderKeyBytes)

            // Add contact if we have a name and don't already have this contact
            if (senderName.isNotEmpty()) {
                val senderKey = defaultCryptoProvider.keyFromPublicBin(senderKeyBytes)
                val existingContact = ContactStore.getInstance(requireContext()).getContactFromPublicKey(senderKey)
                if (existingContact == null) {
                    ContactStore.getInstance(requireContext()).addContact(senderKey, senderName)
                }
            }

            // Note: In a full implementation, you would also:
            // 1. Store the transaction block data locally for later synchronization
            // 2. Validate the transaction cryptographically
            // 3. Update local balance tracking
            // For this university project, the basic processing above should be sufficient

            Log.e("TransferFragment", "Processed offline transaction: $amount from (${senderName})")

        } catch (e: Exception) {
            Log.e("TransferFragment", "Error in processOfflineTransaction: ${e.message}")
            throw e
        }
    }

    override fun onNFCReadError(error: String) {
        super.onNFCReadError(error)
        updateNFCState(NFCState.ERROR)
        Toast.makeText(requireContext(), "Failed to read transaction data: $error", Toast.LENGTH_LONG).show()
    }

    override fun onNFCOperationCancelled() {
        super.onNFCOperationCancelled()
        deactivateNFCReceive()
    }

    override fun onNFCTimeout() {
        super.onNFCTimeout()
        deactivateNFCReceive()
    }

    /**
     * Activate Phase 2 NFC receive mode (called from RequestMoneyFragment)
     */

    fun activatePhase2Receive() {
        currentPhase = TransactionPhase.WAITING_PHASE2
        updateButtonStates()
        activateNFCReceive("payment_confirmation", 60)

        Toast.makeText(
            requireContext(),
            "Ready to receive payment. Ask the sender to tap phones.",
            Toast.LENGTH_LONG
        ).show()
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
