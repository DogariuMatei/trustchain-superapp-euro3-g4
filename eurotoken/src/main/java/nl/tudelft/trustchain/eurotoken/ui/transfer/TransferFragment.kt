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

        // Check if we should activate Phase 2 immediately
        if (arguments?.getBoolean("activate_phase2") == true) {
            Log.d(TAG, "Activating Phase 2 from arguments")
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

        // Request Payment Button - Phase 1 HCE Transaction
        binding.btnRequest.setOnClickListener {
            val amount = getAmount(binding.edtAmount.text.toString())
            if (amount > 0) {
                Log.d(TAG, "Payment Request Init for amount: $amount")
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
     * Activate HCE reader mode to receive Phase 1 payment requests
     */
    private fun activatePhase1Receive() {
        Log.d(TAG, "=== ACTIVATE PHASE 1 RECEIVE ===")
        currentPhase = TransactionPhase.WAITING_PHASE1
        updateButtonStates()

        Toast.makeText(
            requireContext(),
            "Ready to receive payment request. Ask the requester to tap phones.",
            Toast.LENGTH_LONG
        ).show()

        startHCEReaderMode(
            message = "Waiting for payment request...",
            timeoutSeconds = 60,
            onDataReceived = { jsonData ->
                Log.d(TAG, "Received data in Phase 1: ${jsonData.take(100)}...")
                handleReceivedData(jsonData)
            }
        )
    }

    /**
     * Activate HCE reader mode to receive Phase 2 payment confirmation
     */
    fun activatePhase2Receive() {
        Log.d(TAG, "=== ACTIVATE PHASE 2 RECEIVE ===")
        currentPhase = TransactionPhase.WAITING_PHASE2
        updateButtonStates()

        Toast.makeText(
            requireContext(),
            "Ready to receive payment. Ask the sender to tap phones.",
            Toast.LENGTH_LONG
        ).show()

        startHCEReaderMode(
            message = "Waiting for payment confirmation...",
            timeoutSeconds = 60,
            onDataReceived = { jsonData ->
                Log.d(TAG, "Received data in Phase 2: ${jsonData.take(100)}...")
                handleReceivedData(jsonData)
            }
        )
    }

    /**
     * Deactivate NFC receive mode
     */
    private fun deactivateNFCReceive() {
        Log.d(TAG, "=== DEACTIVATE NFC RECEIVE ===")
        currentPhase = TransactionPhase.IDLE
        updateButtonStates()

        // Cleanup is handled by base fragment
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
     * Initiate a payment request - Phase 1 of the HCE transaction
     */
    private fun initiatePaymentRequest(amount: Long) {
        Log.d(TAG, "=== INITIATE PAYMENT REQUEST ===")

        val myPeer = transactionRepository.trustChainCommunity.myPeer
        val ownKey = myPeer.publicKey
        val contact = ContactStore.getInstance(requireContext()).getContactFromPublicKey(ownKey)

        val paymentRequest = JSONObject()
        paymentRequest.put("type", "payment_request")
        paymentRequest.put("public_key", myPeer.publicKey.keyToBin().toHex())
        paymentRequest.put("amount", amount)
        paymentRequest.put("requester_name", contact?.name ?: "")
        paymentRequest.put("timestamp", System.currentTimeMillis())

        Log.d(TAG, "Payment request created: ${paymentRequest.toString(2)}")

        // Navigate to HCE waiting screen for payment request
        navigateToNFCRequestScreen(paymentRequest.toString())
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
     * Handle received HCE data - Routes to appropriate phase handler
     */
    private fun handleReceivedData(jsonData: String) {
        Log.d(TAG, "=== HANDLE RECEIVED DATA ===")

        try {
            val receivedData = JSONObject(jsonData)
            val dataType = receivedData.optString("type")

            Log.d(TAG, "Received data type: $dataType")

            when (dataType) {
                "payment_request" -> {
                    if (currentPhase == TransactionPhase.WAITING_PHASE1) {
                        handlePhase1PaymentRequest(receivedData)
                    } else {
                        Log.w(TAG, "Received payment request in wrong phase: $currentPhase")
                        Toast.makeText(requireContext(), "Not expecting payment request", Toast.LENGTH_SHORT).show()
                    }
                }
                "payment_confirmation" -> {
                    if (currentPhase == TransactionPhase.WAITING_PHASE2) {
                        handlePhase2PaymentConfirmation(receivedData)
                    } else {
                        Log.w(TAG, "Received payment confirmation in wrong phase: $currentPhase")
                        Toast.makeText(requireContext(), "Not expecting payment confirmation", Toast.LENGTH_SHORT).show()
                    }
                }
                else -> {
                    Log.e(TAG, "Unknown data type: $dataType")
                    Toast.makeText(requireContext(), "Invalid transaction data received", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: JSONException) {
            Log.e(TAG, "JSON parsing error: ${e.message}")
            onNFCReadError("Invalid transaction data format")
        }
    }

    /**
     * Handle Phase 1 - Payment Request received from Requester
     */
    private fun handlePhase1PaymentRequest(paymentRequest: JSONObject) {
        Log.d(TAG, "=== HANDLE PHASE 1 PAYMENT REQUEST ===")

        val amount = paymentRequest.optLong("amount", -1L)
        val publicKey = paymentRequest.optString("public_key")
        val requesterName = paymentRequest.optString("requester_name")

        Log.d(TAG, "Payment request - Amount: $amount, From: ${publicKey.take(20)}..., Name: $requesterName")

        if (amount <= 0 || publicKey.isEmpty()) {
            Log.e(TAG, "Invalid payment request data")
            Toast.makeText(requireContext(), "Invalid payment request data", Toast.LENGTH_LONG).show()
            return
        }

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
     */
    private fun handlePhase2PaymentConfirmation(paymentConfirmation: JSONObject) {
        Log.d(TAG, "=== HANDLE PHASE 2 PAYMENT CONFIRMATION ===")

        try {
            // Extract transaction data
            val senderPublicKey = paymentConfirmation.optString("sender_public_key")
            val senderName = paymentConfirmation.optString("sender_name")
            val amount = paymentConfirmation.optLong("amount", -1L)
            val blockHash = paymentConfirmation.optString("block_hash")
            val sequenceNumber = paymentConfirmation.optLong("sequence_number", -1L)
            val blockTimestamp = paymentConfirmation.optLong("block_timestamp", -1L)

            Log.d(TAG, "Payment confirmation - Amount: $amount, From: ${senderPublicKey.take(20)}..., Name: $senderName")
            Log.d(TAG, "Block hash: ${blockHash.take(20)}..., Sequence: $sequenceNumber")

            // Validate required data
            if (senderPublicKey.isEmpty() || amount <= 0 || blockHash.isEmpty() || sequenceNumber < 0) {
                Log.e(TAG, "Invalid transaction data")
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

            val displayName = if (senderName.isNotEmpty()) senderName else "Unknown"
            Toast.makeText(
                requireContext(),
                "Payment of ${TransactionRepository.prettyAmount(amount)} received from $displayName!",
                Toast.LENGTH_LONG
            ).show()

            // Navigate to transaction history
            deactivateNFCReceive()
            findNavController().navigate(R.id.transactionsFragment)

        } catch (e: Exception) {
            Log.e(TAG, "Error processing payment confirmation: ${e.message}", e)
            Toast.makeText(requireContext(), "Failed to process transaction: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Process offline transaction data received via HCE
     */
    private fun processOfflineTransaction(
        senderPublicKey: String,
        senderName: String,
        amount: Long,
        blockHash: String,
        sequenceNumber: Long,
        blockTimestamp: Long
    ) {
        Log.d(TAG, "=== PROCESS OFFLINE TRANSACTION ===")

        try {
            // Convert sender public key
            val senderKeyBytes = senderPublicKey.hexToBytes()

            // Update trust score for sender
            Log.d(TAG, "Incrementing trust score for sender")
            trustStore.incrementTrust(senderKeyBytes)

            // Add contact if we have a name and don't already have this contact
            if (senderName.isNotEmpty()) {
                val senderKey = defaultCryptoProvider.keyFromPublicBin(senderKeyBytes)
                val existingContact = ContactStore.getInstance(requireContext()).getContactFromPublicKey(senderKey)
                if (existingContact == null) {
                    ContactStore.getInstance(requireContext()).addContact(senderKey, senderName)
                    Log.d(TAG, "Added new contact: $senderName")
                }
            }

            // In a full implementation, you would also:
            // 1. Store the transaction block data locally for later synchronization
            // 2. Validate the transaction cryptographically
            // 3. Update local balance tracking

            Log.d(TAG, "Processed offline transaction successfully")
            Log.d(TAG, "Amount: $amount, From: $senderName, Block: ${blockHash.take(20)}...")

        } catch (e: Exception) {
            Log.e(TAG, "Error in processOfflineTransaction: ${e.message}", e)
            throw e
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
