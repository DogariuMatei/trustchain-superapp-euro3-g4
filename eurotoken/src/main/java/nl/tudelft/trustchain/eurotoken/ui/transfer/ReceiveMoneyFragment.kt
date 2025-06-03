package nl.tudelft.trustchain.eurotoken.ui.transfer

import android.util.Log
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import nl.tudelft.ipv8.keyvault.defaultCryptoProvider
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.contacts.ContactStore
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.EuroTokenMainActivity
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentReceiveMoneyBinding
import nl.tudelft.trustchain.eurotoken.nfc.HCEPaymentService
import nl.tudelft.trustchain.eurotoken.ui.EurotokenNFCBaseFragment
import org.json.JSONException
import org.json.JSONObject

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class ReceiveMoneyFragment : EurotokenNFCBaseFragment(R.layout.fragment_receive_money) {

    companion object {
        private const val TAG = "ReceiveMoneyFragment"
        const val ARG_DATA = "data"
        const val TRUSTSCORE_AVERAGE_BOUNDARY = 70
        const val TRUSTSCORE_LOW_BOUNDARY = 30
    }

    private var addContact = false
    private val binding by viewBinding(FragmentReceiveMoneyBinding::bind)

    private val ownPublicKey by lazy {
        defaultCryptoProvider.keyFromPublicBin(
            transactionRepository.trustChainCommunity.myPeer.publicKey.keyToBin().toHex()
                .hexToBytes()
        )
    }

    // Store sender information for Phase 2
    private lateinit var senderInfo: SenderInfo

    private data class SenderInfo(
        val senderPublicKey: String,
        val senderName: String,
        val amount: Long,
        val senderBalance: Long,
        val recentCounterparties: List<String>
    )

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "=== RECEIVE MONEY FRAGMENT VIEW CREATED ===")

        val senderDataJson = requireArguments().getString(ARG_DATA)!!
        Log.d(TAG, "Received sender data: ${senderDataJson.take(100)}...")

        try {
            parseSenderData(senderDataJson)
            setupUI()
            displayTrustScore()

        } catch (e: JSONException) {
            Log.e(TAG, "Error parsing sender data: ${e.message}")
            Toast.makeText(requireContext(), "Invalid sender data", Toast.LENGTH_LONG).show()
            findNavController().popBackStack()
        }
    }

    /**
     * Parse sender data from JSON
     */
    private fun parseSenderData(jsonData: String) {
        val senderData = JSONObject(jsonData)

        val senderPublicKey = senderData.optString("sender_public_key")
        val senderName = senderData.optString("sender_name")
        val amount = senderData.optLong("amount", -1L)
        val senderBalance = senderData.optLong("sender_balance", -1L)
        val counterpartiesStr = senderData.optString("recent_counterparties", "")
        val recentCounterparties = if (counterpartiesStr.isNotEmpty()) {
            counterpartiesStr.split(",")
        } else {
            emptyList()
        }

        if (senderPublicKey.isEmpty() || amount <= 0) {
            throw JSONException("Invalid sender data: missing required fields")
        }

        senderInfo = SenderInfo(senderPublicKey, senderName, amount, senderBalance, recentCounterparties)
        Log.d(TAG, "Parsed sender info - Amount: ${senderInfo.amount}, From: ${senderInfo.senderName}")

        // Update trust scores based on received counterparties
        updateTrustScores(recentCounterparties)
    }

    /**
     * Update trust scores based on sender's recent counterparties
     */
    private fun updateTrustScores(counterparties: List<String>) {
        Log.d(TAG, "Updating trust scores for ${counterparties.size} counterparties")
        counterparties.forEach { publicKeyHex ->
            try {
                val publicKeyBytes = publicKeyHex.hexToBytes()
                trustStore.incrementTrust(publicKeyBytes)
                Log.d(TAG, "Incremented trust for: ${publicKeyHex.take(10)}...")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update trust for: ${publicKeyHex.take(10)}...")
            }
        }
    }

    /**
     * Setup UI with sender information
     */
    private fun setupUI() {
        val key = defaultCryptoProvider.keyFromPublicBin(senderInfo.senderPublicKey.hexToBytes())
        val contact = ContactStore.getInstance(requireContext()).getContactFromPublicKey(key)

        binding.txtContactName.text = contact?.name ?: senderInfo.senderName.ifEmpty { "Unknown" }
        binding.txtContactPublicKey.text = senderInfo.senderPublicKey
        binding.txtAmount.text = TransactionRepository.prettyAmount(senderInfo.amount)

        // Setup contact saving
        binding.newContactName.visibility = View.GONE

        if (senderInfo.senderName.isNotEmpty()) {
            binding.newContactName.setText(senderInfo.senderName)
        }

        if (contact == null) {
            binding.addContactSwitch.toggle()
            addContact = true
            binding.newContactName.visibility = View.VISIBLE
            binding.newContactName.setText(senderInfo.senderName)
        } else {
            binding.addContactSwitch.visibility = View.GONE
            binding.newContactName.visibility = View.GONE
        }

        binding.addContactSwitch.setOnClickListener {
            addContact = !addContact
            if (addContact) {
                binding.newContactName.visibility = View.VISIBLE
            } else {
                binding.newContactName.visibility = View.GONE
            }
        }

        // Display current balance
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
        binding.txtOwnPublicKey.text = ownPublicKey.toString()

        // Setup trust button - now starts Phase 2 directly
        binding.btnTrustSender.setOnClickListener {
            Log.d(TAG, "Trust Sender button clicked - starting Phase 2")
            proceedWithTransaction()
        }
    }

    /**
     * Display trust score information for the sender
     */
    private fun displayTrustScore() {
        val trustScore = trustStore.getScore(senderInfo.senderPublicKey.toByteArray())
        Log.d(TAG, "Trust score for sender: $trustScore")

        if (trustScore != null) {
            if (trustScore >= TRUSTSCORE_AVERAGE_BOUNDARY) {
                binding.trustScoreWarning.text =
                    getString(R.string.send_money_trustscore_warning_high, trustScore)
                binding.trustScoreWarning.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.android_green)
                )
            } else if (trustScore > TRUSTSCORE_LOW_BOUNDARY) {
                binding.trustScoreWarning.text =
                    getString(R.string.send_money_trustscore_warning_average, trustScore)
                binding.trustScoreWarning.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.metallic_gold)
                )
            } else {
                binding.trustScoreWarning.text =
                    getString(R.string.send_money_trustscore_warning_low, trustScore)
                binding.trustScoreWarning.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.red)
                )
            }
        } else {
            binding.trustScoreWarning.text =
                getString(R.string.send_money_trustscore_warning_no_score)
            binding.trustScoreWarning.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.metallic_gold)
            )
        }
        binding.trustScoreWarning.visibility = View.VISIBLE
    }

    /**
     * Proceed with transaction - save contact and start Phase 2 directly
     */
    private fun proceedWithTransaction() {
        Log.d(TAG, "=== PROCEED WITH TRANSACTION ===")

        // Add contact if requested
        val newName = binding.newContactName.text.toString()
        if (addContact && newName.isNotEmpty()) {
            val key = defaultCryptoProvider.keyFromPublicBin(senderInfo.senderPublicKey.hexToBytes())
            ContactStore.getInstance(requireContext()).addContact(key, newName)
            Log.d(TAG, "Contact added: $newName")
        }

        // Start Phase 2 receiver mode immediately
        startPhase2Receive()
    }

    /**
     * Start Phase 2 - Send receiver confirmation then wait for payment
     */
    private fun startPhase2Receive() {
        Log.d(TAG, "=== START PHASE 2 RECEIVE ===")

        // Clear any lingering HCE data from Phase 1
        HCEPaymentService.clearPendingTransactionData()
        HCEPaymentService.clearOnDataReceivedCallback()

        val myPeer = transactionRepository.trustChainCommunity.myPeer
        val receiverConfirmation = JSONObject()
        receiverConfirmation.put("type", "receiver_ready")
        receiverConfirmation.put("receiver_public_key", myPeer.publicKey.keyToBin().toHex())
        receiverConfirmation.put("timestamp", System.currentTimeMillis())

        Log.d(TAG, "Sending receiver confirmation: ${receiverConfirmation.toString().take(100)}...")

        Toast.makeText(
            requireContext(),
            "Ready to receive payment. Hold phones together when sender is ready.",
            Toast.LENGTH_LONG
        ).show()

        // First send receiver confirmation via card emulation
        startHCECardEmulation(
            jsonData = receiverConfirmation.toString(),
            message = "Confirming with sender...",
            timeoutSeconds = 30,
            expectResponse = false,
            onDataTransmitted = {
                Log.d(TAG, "Receiver confirmation sent, switching to reader mode")
                updateNFCDialogMessage("Waiting for payment...")

                // Short delay then switch to reader mode for payment
                Handler(Looper.getMainLooper()).postDelayed({
                    switchToPaymentReceiveMode()
                }, 500)
            }
        )
    }

    /**
     * Switch to reader mode to receive payment confirmation
     */
    private fun switchToPaymentReceiveMode() {
        Log.d(TAG, "=== SWITCH TO PAYMENT RECEIVE MODE ===")

        // Clear any old HCE data before starting reader mode
        HCEPaymentService.clearPendingTransactionData()
        HCEPaymentService.clearOnDataReceivedCallback()

        startHCEReaderMode(
            message = "Receiving payment...",
            timeoutSeconds = 60,
            onDataReceived = { paymentData ->
                Log.d(TAG, "Received payment confirmation: ${paymentData.take(100)}...")
                Log.d(TAG, "Full payment data type check: ${JSONObject(paymentData).optString("type")}")
                handlePaymentConfirmation(paymentData)
            }
        )
    }

    /**
     * Handle payment confirmation from sender
     */
    private fun handlePaymentConfirmation(jsonData: String) {
        Log.d(TAG, "=== HANDLE PAYMENT CONFIRMATION ===")

        try {
            val paymentConfirmation = JSONObject(jsonData)
            val dataType = paymentConfirmation.optString("type")

            if (dataType == "payment_confirmation") {
                val senderPublicKey = paymentConfirmation.optString("sender_public_key")
                val amount = paymentConfirmation.optLong("amount", -1L)

                // Validate this matches expected sender/amount
                if (senderPublicKey == senderInfo.senderPublicKey && amount == senderInfo.amount) {
                    processReceivedPayment(paymentConfirmation)
                } else {
                    Log.e(TAG, "Payment details don't match - Expected: ${senderInfo.senderPublicKey.take(10)}/$senderInfo.amount, Got: ${senderPublicKey.take(10)}/$amount")
                    Toast.makeText(requireContext(), "Payment details don't match expected values", Toast.LENGTH_LONG).show()
                }
            } else {
                Log.e(TAG, "Invalid payment confirmation type: $dataType")
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
        Log.d(TAG, "=== PROCESS RECEIVED PAYMENT ===")

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

        // Update UI and navigate
        updateNFCDialogMessage("Payment received!")

        Handler(Looper.getMainLooper()).postDelayed({
            dismissNFCDialog()
            completeTransaction(senderName, amount)
        }, 1500)
    }

    /**
     * Process offline transaction data received via NFC
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

    /**
     * Complete transaction and navigate to transaction history
     */
    private fun completeTransaction(senderName: String, amount: Long) {
        Log.d(TAG, "=== COMPLETE TRANSACTION ===")

        val displayName = if (senderName.isNotEmpty()) senderName else "Unknown"
        Toast.makeText(
            requireContext(),
            "Payment of ${TransactionRepository.prettyAmount(amount)} received from $displayName!",
            Toast.LENGTH_LONG
        ).show()

        Log.d(TAG, "Navigating to transaction history")

        // Navigate to transaction history
        try {
            findNavController().navigate(R.id.action_receiveMoneyFragment_to_transactionsFragment)
            Log.d(TAG, "Successfully navigated to transactions fragment")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to navigate: ${e.message}")
        }
    }

    override fun onNFCReadError(error: String) {
        super.onNFCReadError(error)
        Log.e(TAG, "NFC Read Error: $error")
        Toast.makeText(requireContext(), "Failed to receive payment: $error", Toast.LENGTH_LONG).show()
    }

    override fun onNFCTimeout() {
        super.onNFCTimeout()
        Log.w(TAG, "NFC operation timed out")
        Toast.makeText(
            requireContext(),
            "Payment receive timed out. Please try again.",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onNFCOperationCancelled() {
        super.onNFCOperationCancelled()
        Log.d(TAG, "NFC operation cancelled by user")
    }
}
