package nl.tudelft.trustchain.eurotoken.ui.transfer

import android.util.Log
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import nl.tudelft.trustchain.eurotoken.nfc.HCEPaymentService
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
        WAITING_PHASE1,
        WAITING_PHASE2
    }

    private var currentPhase = TransactionPhase.IDLE

    // Phase 2 state variables
    private var isPhase2SenderReady = false
    private var isPhase2ReceiverReady = false
    private var expectedSender: String? = null
    private var expectedAmount: Long = 0

    @JvmName("getEuroTokenCommunity1")
    private fun getEuroTokenCommunity(): nl.tudelft.trustchain.eurotoken.community.EuroTokenCommunity {
        return getIpv8().getOverlay()
            ?: throw IllegalStateException("EuroTokenCommunity is not configured")
    }

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
        handlePhase2ReadyStates()
    }

    private fun handlePhase2ReadyStates() {
        val phase2Ready = arguments?.getBoolean("phase2_ready") == true

        if (phase2Ready) {
            val expectedSender = arguments?.getString("expected_sender")
            val expectedAmount = arguments?.getLong("expected_amount")

            if (expectedSender != null && expectedAmount != null) {
                // Receiver returning from ReceiveMoneyFragment
                showPhase2ReceiverReady(expectedSender, expectedAmount)
            } else {
                // Sender returning from SendMoneyFragment
                showPhase2SenderReady()
            }

            arguments?.remove("phase2_ready")
            arguments?.remove("expected_sender")
            arguments?.remove("expected_amount")
        }
    }

    private fun showPhase2SenderReady() {
        isPhase2SenderReady = true
        Toast.makeText(
            requireContext(),
            "Phase 1 complete! Activate NFC when ready to send actual payment.",
            Toast.LENGTH_LONG
        ).show()
        updateButtonStates()
    }

    private fun showPhase2ReceiverReady(expectedSender: String, expectedAmount: Long) {
        isPhase2ReceiverReady = true
        this.expectedSender = expectedSender
        this.expectedAmount = expectedAmount
        Toast.makeText(
            requireContext(),
            "Ready to receive payment of ${TransactionRepository.prettyAmount(expectedAmount)}. Activate NFC when sender is ready.",
            Toast.LENGTH_LONG
        ).show()
        updateButtonStates()
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

        // Activate NFC Button - Phase-aware functionality
        binding.btnActivateNFC.setOnClickListener {
            when {
                isPhase2SenderReady -> initiatePhase2Payment()
                isPhase2ReceiverReady -> activatePhase2Receive()
                currentPhase == TransactionPhase.IDLE -> activateNFCReceive()
                currentPhase == TransactionPhase.WAITING_PHASE1 -> deactivateNFCReceive()
                currentPhase == TransactionPhase.WAITING_PHASE2 -> deactivateNFCReceive()
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
     * Initiate Phase 2 payment - Sender creates and sends actual transaction
     */
    private fun initiatePhase2Payment() {
        Log.d(TAG, "=== INITIATE PHASE 2 PAYMENT ===")

        val amount = getAmount(binding.edtAmount.text.toString())
        if (amount <= 0) {
            Toast.makeText(requireContext(), "Please specify a valid amount", Toast.LENGTH_SHORT).show()
            return
        }

        // Check sufficient balance
        val pref = requireContext().getSharedPreferences(
            EuroTokenMainActivity.EurotokenPreferences.EUROTOKEN_SHARED_PREF_NAME,
            Context.MODE_PRIVATE
        )
        val demoModeEnabled = pref.getBoolean(
            EuroTokenMainActivity.EurotokenPreferences.DEMO_MODE_ENABLED,
            false
        )

        val currentBalance = if (demoModeEnabled) {
            transactionRepository.getMyBalance()
        } else {
            transactionRepository.getMyVerifiedBalance()
        }

        if (currentBalance < amount) {
            Toast.makeText(requireContext(), "Insufficient balance", Toast.LENGTH_LONG).show()
            return
        }

        // Start reader mode to get receiver confirmation, then create transaction
        currentPhase = TransactionPhase.WAITING_PHASE2
        updateButtonStates()

        Toast.makeText(
            requireContext(),
            "Hold phones together to send payment",
            Toast.LENGTH_LONG
        ).show()

        startHCEReaderMode(
            message = "Connecting to receiver...",
            timeoutSeconds = 60,
            onDataReceived = { jsonData ->
                Log.d(TAG, "Received receiver confirmation: ${jsonData.take(100)}...")
                handleReceiverConfirmation(jsonData, amount)
            }
        )
    }

    /**
     * Handle receiver confirmation and create actual transaction
     */
    private fun handleReceiverConfirmation(jsonData: String, amount: Long) {
        try {
            val receiverData = JSONObject(jsonData)
            val dataType = receiverData.optString("type")

            if (dataType == "receiver_ready") {
                val receiverPublicKey = receiverData.optString("receiver_public_key")

                if (receiverPublicKey.isEmpty()) {
                    Toast.makeText(requireContext(), "Invalid receiver data", Toast.LENGTH_SHORT).show()
                    return
                }

                // Create actual transaction
                createAndSendTransaction(receiverPublicKey, amount)
            } else {
                Toast.makeText(requireContext(), "Invalid receiver response", Toast.LENGTH_SHORT).show()
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Error parsing receiver data: ${e.message}")
            Toast.makeText(requireContext(), "Invalid receiver data format", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Create actual blockchain transaction and send confirmation
     */
    private fun createAndSendTransaction(receiverPublicKey: String, amount: Long) {
        Log.d(TAG, "=== CREATE AND SEND TRANSACTION ===")

        try {
            // Create the actual blockchain transaction
            val transactionBlock = transactionRepository.sendTransferProposalSync(
                receiverPublicKey.hexToBytes(),
                amount
            )

            if (transactionBlock == null) {
                Toast.makeText(requireContext(), "Failed to create transaction", Toast.LENGTH_LONG).show()
                return
            }

            // Create payment confirmation
            val myPeer = transactionRepository.trustChainCommunity.myPeer
            val senderContact = ContactStore.getInstance(requireContext()).getContactFromPublicKey(myPeer.publicKey)

            val paymentConfirmation = JSONObject()
            paymentConfirmation.put("type", "payment_confirmation")
            paymentConfirmation.put("sender_public_key", myPeer.publicKey.keyToBin().toHex())
            paymentConfirmation.put("sender_name", senderContact?.name ?: "")
            paymentConfirmation.put("recipient_public_key", receiverPublicKey)
            paymentConfirmation.put("amount", amount)
            paymentConfirmation.put("timestamp", System.currentTimeMillis())
            paymentConfirmation.put("block_hash", transactionBlock.calculateHash().toHex())
            paymentConfirmation.put("sequence_number", transactionBlock.sequenceNumber)
            paymentConfirmation.put("block_timestamp", transactionBlock.timestamp.time)

            // Send confirmation via HCE card emulation
            startHCECardEmulation(
                jsonData = paymentConfirmation.toString(),
                message = "Sending payment confirmation...",
                timeoutSeconds = 30,
                expectResponse = false,
                onDataTransmitted = {
                    updateNFCDialogMessage("Transaction complete!")
                    Handler(Looper.getMainLooper()).postDelayed({
                        dismissNFCDialog()
                        resetSenderAfterTransaction()
                    }, 1500)
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error creating transaction: ${e.message}", e)
            Toast.makeText(requireContext(), "Transaction failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Complete sender transaction and cleanup
     */
    private fun resetSenderAfterTransaction() {
        Toast.makeText(
            requireContext(),
            "Payment sent successfully!",
            Toast.LENGTH_LONG
        ).show()

        // Reset state and navigate
        isPhase2SenderReady = false
        currentPhase = TransactionPhase.IDLE
        updateButtonStates()
        findNavController().navigate(R.id.transactionsFragment)
    }

    /**
     * Activate Phase 2 receiver mode - Send receiver confirmation then wait for payment
     */
    private fun activatePhase2Receive() {
        Log.d(TAG, "=== ACTIVATE PHASE 2 RECEIVE ===")
        currentPhase = TransactionPhase.WAITING_PHASE2
        updateButtonStates()

        // Create receiver confirmation
        val myPeer = transactionRepository.trustChainCommunity.myPeer
        val receiverConfirmation = JSONObject()
        receiverConfirmation.put("type", "receiver_ready")
        receiverConfirmation.put("receiver_public_key", myPeer.publicKey.keyToBin().toHex())
        receiverConfirmation.put("timestamp", System.currentTimeMillis())

        Toast.makeText(
            requireContext(),
            "Ready to receive payment. Hold phones together.",
            Toast.LENGTH_LONG
        ).show()

        // Use card emulation to send receiver confirmation and wait for payment response
        startHCECardEmulation(
            jsonData = receiverConfirmation.toString(),
            message = "Confirming with sender...",
            timeoutSeconds = 60,
            expectResponse = true,
            onResponseReceived = { paymentData ->
                Log.d(TAG, "Received payment confirmation: ${paymentData.take(100)}...")
                handleFinalPaymentConfirmation(paymentData)
            }
        )
    }

    /**
     * Handle final payment confirmation in Phase 2
     */
    private fun handleFinalPaymentConfirmation(jsonData: String) {
        try {
            val paymentConfirmation = JSONObject(jsonData)
            val dataType = paymentConfirmation.optString("type")

            if (dataType == "payment_confirmation") {
                val senderPublicKey = paymentConfirmation.optString("sender_public_key")
                val amount = paymentConfirmation.optLong("amount", -1L)

                // Validate this matches expected sender/amount
                if (senderPublicKey == expectedSender && amount == expectedAmount) {
                    processReceivedPayment(paymentConfirmation)
                } else {
                    Toast.makeText(requireContext(), "Payment details don't match expected values", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(requireContext(), "Invalid payment confirmation", Toast.LENGTH_SHORT).show()
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Error parsing payment confirmation: ${e.message}")
            onNFCReadError("Invalid payment data format")
        }
    }

    /**
     * Process received payment and complete transaction
     */
    private fun processReceivedPayment(paymentConfirmation: JSONObject) {
        val senderPublicKey = paymentConfirmation.optString("sender_public_key")
        val senderName = paymentConfirmation.optString("sender_name")
        val amount = paymentConfirmation.optLong("amount", -1L)
        val blockHash = paymentConfirmation.optString("block_hash")
        val sequenceNumber = paymentConfirmation.optLong("sequence_number", -1L)
        val blockTimestamp = paymentConfirmation.optLong("block_timestamp", -1L)

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

        // Reset state and navigate
        isPhase2ReceiverReady = false
        currentPhase = TransactionPhase.IDLE
        updateButtonStates()
        findNavController().navigate(R.id.transactionsFragment)
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
     * Update button text and states based on current phase and readiness
     */
    private fun updateButtonStates() {
        when {
            isPhase2SenderReady -> {
                binding.btnActivateNFC.text = "Send Payment via NFC"
                binding.btnActivateNFC.setBackgroundColor(resources.getColor(R.color.green, null))
            }
            isPhase2ReceiverReady -> {
                binding.btnActivateNFC.text = "Receive Payment via NFC"
                binding.btnActivateNFC.setBackgroundColor(resources.getColor(R.color.green, null))
            }
            currentPhase == TransactionPhase.IDLE -> {
                binding.btnActivateNFC.text = "Activate NFC"
                binding.btnActivateNFC.setBackgroundColor(resources.getColor(R.color.colorPrimary, null))
            }
            currentPhase == TransactionPhase.WAITING_PHASE1 -> {
                binding.btnActivateNFC.text = "Cancel NFC"
                binding.btnActivateNFC.setBackgroundColor(resources.getColor(R.color.red, null))
            }
            currentPhase == TransactionPhase.WAITING_PHASE2 -> {
                binding.btnActivateNFC.text = "Cancel NFC"
                binding.btnActivateNFC.setBackgroundColor(resources.getColor(R.color.red, null))
            }
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

            // TODO: Store the transaction block data locally for later synchronization
            // TODO: Validate the transaction cryptographically
            // TODO: Update local balance tracking

            Log.d(TAG, "Processed offline transaction successfully")
            Log.d(TAG, "Amount: $amount, From: $senderName, Block: ${blockHash.take(20)}...")

        } catch (e: Exception) {
            Log.e(TAG, "Error in processOfflineTransaction: ${e.message}", e)
            throw e
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
