// Updated SendMoneyFragment.kt

package nl.tudelft.trustchain.eurotoken.ui.transfer

import android.util.Log
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.navigation.fragment.findNavController
import nl.tudelft.ipv8.keyvault.defaultCryptoProvider
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.contacts.ContactStore
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentSendMoneyBinding
import nl.tudelft.trustchain.eurotoken.nfc.HCEPaymentService
import nl.tudelft.trustchain.eurotoken.ui.EurotokenNFCBaseFragment
import org.json.JSONObject
import com.google.gson.Gson

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@SuppressLint("SetTextI18n")
class SendMoneyFragment : EurotokenNFCBaseFragment(R.layout.fragment_send_money) {

    companion object {
        private const val TAG = "SendMoneyFragment"
        const val ARG_AMOUNT = "amount"
    }

    private val binding by viewBinding(FragmentSendMoneyBinding::bind)

    private var senderPayloadData: String? = null
    private var isPhase1Complete = false
    private var amount: Long = 0
    private var receiverPublicKey: String? = null

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "=== SEND MONEY FRAGMENT VIEW CREATED ===")

        amount = requireArguments().getLong(ARG_AMOUNT)
        Log.d(TAG, "Amount to send: $amount")

        // Create sender payload with transaction info
        createSenderPayload()

        // Display status
        binding.txtSendData.text = "Payment details ready for transmission"

        startPhase1HCETransmission()

        binding.btnContinue.setOnClickListener {
            if (isPhase1Complete) {
                startPhase2Payment()
            } else {
                retryPhase1Transmission()
            }
        }
    }

    /**
     * Create sender payload with transaction info and trust data
     */
    private fun createSenderPayload() {
        Log.d(TAG, "=== CREATE SENDER PAYLOAD ===")

        val myPeer = utxoService.trustChainCommunity.myPeer
        val ownKey = myPeer.publicKey
        val contact = ContactStore.getInstance(requireContext()).getContactFromPublicKey(ownKey)

        // Get recent transaction counterparties for trust building
        val recentTransactions = transactionRepository.getTransactions(10)
        val recentCounterparties = recentTransactions.map { it.receiver.keyToBin().toHex() }.distinct().take(5)

        val senderInfo = JSONObject()
        senderInfo.put("type", "sender_info")
        senderInfo.put("sender_public_key", myPeer.publicKey.keyToBin().toHex())
        senderInfo.put("sender_name", contact?.name ?: "")
        senderInfo.put("amount", amount)
        senderInfo.put("timestamp", System.currentTimeMillis())

        // Add trust data - recent counterparties for trust score building
        senderInfo.put("recent_counterparties", recentCounterparties.joinToString(","))

        // Add current balance for transparency
        val currentBalance = utxoService.getMyBalance()
        senderInfo.put("sender_balance", currentBalance)

        senderPayloadData = senderInfo.toString()
        Log.d(TAG, "Sender payload created: ${senderPayloadData?.take(200)}...")
    }

    /**
     * Start Phase 1 HCE transmission of sender info
     */
    private fun startPhase1HCETransmission() {
        Log.d(TAG, "=== START PHASE 1 HCE TRANSMISSION ===")

        // Ensure HCE service is completely clean before starting
        HCEPaymentService.clearPendingTransactionData()
        HCEPaymentService.clearOnDataReceivedCallback()
        HCEPaymentService.clearOnDataTransmittedCallback()

        // Start HCE service explicitly
        requireContext().startService(Intent(requireContext(), HCEPaymentService::class.java))

        senderPayloadData?.let { data ->
            Toast.makeText(
                requireContext(),
                "Ask the receiver to activate NFC, then hold phones together",
                Toast.LENGTH_LONG
            ).show()

            // Use HCE card emulation mode to send sender info
            startHCECardEmulation(
                jsonData = data,
                message = "Waiting for receiver's phone...",
                timeoutSeconds = 30,
                expectResponse = false, // Phase 1 is one-way communication
                onSuccess = {
                    Log.d(TAG, "HCE card emulation ready - waiting for reader")
                },
                onDataTransmitted = {
                    Log.d(TAG, "Phase 1: Sender info successfully transmitted!")
                    isPhase1Complete = true

                    updateNFCDialogMessage("Payment details sent!")
                    Handler(Looper.getMainLooper()).postDelayed({
                        dismissNFCDialog()
                        completePhase1AndPreparePhase2()
                    }, 1500)
                }
            )

            // Update UI to show we're ready
            binding.txtSendData.text = "Ready to transmit. Hold phones together..."
        } ?: run {
            Log.e(TAG, "No sender payload data available")
            Toast.makeText(
                requireContext(),
                "Error: No payment data",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Complete Phase 1 and prepare for Phase 2
     */
    private fun completePhase1AndPreparePhase2() {
        Log.d(TAG, "=== COMPLETE PHASE 1 AND PREPARE FOR PHASE 2 ===")

        // IMPORTANT: Stop HCE card emulation and clear all data from Phase 1
        getHCEHandler()?.stopHCECardEmulation()

        updateUIAfterPhase1()
    }

    /**
     * Update UI after Phase 1 completion
     */
    private fun updateUIAfterPhase1() {
        binding.txtSendData.text = "Phase 1 Complete! Payment details sent successfully."
        binding.btnContinue.text = "Send Payment (Phase 2)"
        binding.btnContinue.isEnabled = true
        binding.btnContinue.setOnClickListener {
            if (isPhase1Complete) {
                startPhase2Payment()
            } else {
                retryPhase1Transmission()
            }
        }
    }

    /**
     * Start Phase 2 - Get receiver confirmation and send transaction
     */
    private fun startPhase2Payment() {
        Log.d(TAG, "=== START PHASE 2 PAYMENT ===")

        Toast.makeText(
            requireContext(),
            "Hold phones together to receive confirmation",
            Toast.LENGTH_LONG
        ).show()

        // In Phase 2, we first get receiver confirmation (which includes their public key)
        startHCEReaderMode(
            message = "Connecting to receiver...",
            timeoutSeconds = 60,
            onDataReceived = { receiverData ->
                Log.d(TAG, "Phase 2: Received receiver confirmation: ${receiverData.take(100)}...")
                handleReceiverConfirmation(receiverData)
            }
        )
    }

    /**
     * Handle receiver confirmation and create transaction
     */
    private fun handleReceiverConfirmation(jsonData: String) {
        try {
            val receiverData = JSONObject(jsonData)
            val dataType = receiverData.optString("type")

            if (dataType == "receiver_ready") {
                receiverPublicKey = receiverData.optString("receiver_public_key")

                if (receiverPublicKey.isNullOrEmpty()) {
                    Toast.makeText(requireContext(), "Invalid receiver data", Toast.LENGTH_SHORT).show()
                    return
                }

                Log.d(TAG, "Got receiver public key: ${receiverPublicKey?.take(20)}...")
                updateNFCDialogMessage("Receiver confirmed, creating transaction...")

                // Give receiver time to switch to reader mode before sending payment
                Handler(Looper.getMainLooper()).postDelayed({
                    createAndSendTransaction()
                }, 2000) // Increased delay to ensure receiver is ready
            } else {
                Toast.makeText(requireContext(), "Invalid receiver response", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing receiver data: ${e.message}")
            Toast.makeText(requireContext(), "Invalid receiver data format", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Create and send transaction after receiving receiver confirmation
     */
    private fun createAndSendTransaction() {
        Log.d(TAG, "=== CREATE AND SEND TRANSACTION ===")

        val receiverKey = receiverPublicKey
        if (receiverKey.isNullOrEmpty()) {
            Log.e(TAG, "No receiver public key available")
            Toast.makeText(requireContext(), "Error: No receiver information", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // CRITICAL: Ensure HCE service is completely clean before setting new data
            Log.d(TAG, "Cleaning HCE service before sending payment confirmation")
            HCEPaymentService.clearPendingTransactionData()
            HCEPaymentService.clearOnDataReceivedCallback()
            HCEPaymentService.clearOnDataTransmittedCallback()

            // Add a small delay to ensure cleanup is complete
            Handler(Looper.getMainLooper()).postDelayed({
                sendPaymentConfirmation(receiverKey)
            }, 300)

        } catch (e: Exception) {
            Log.e(TAG, "Error in createAndSendTransaction: ${e.message}", e)
            Toast.makeText(requireContext(), "Transaction failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Send payment confirmation after cleanup
     */
    private fun sendPaymentConfirmation(receiverKey: String) {
        try {
            // Create actual blockchain transaction
            val utxoTransaction = utxoService.buildUtxoTransactionSync(
                receiverKey.hexToBytes(),
                amount
            )
            if (utxoTransaction == null) {
                Log.e(TAG, "Failed to create utxo transaction")
                Toast.makeText(
                    requireContext(),
                    "Failed to create utxo transaction",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            // Create payment confirmation
            val myPeer = utxoService.trustChainCommunity.myPeer
            val senderContact = ContactStore.getInstance(requireContext()).getContactFromPublicKey(myPeer.publicKey)

            val gson = Gson()
            val utxoTransactionJson = gson.toJson(utxoTransaction)
            val paymentConfirmation = JSONObject()
            paymentConfirmation.put("type", "payment_confirmation")
            paymentConfirmation.put("sender_public_key", myPeer.publicKey.keyToBin().toHex())
            paymentConfirmation.put("sender_name", senderContact?.name ?: "")
            paymentConfirmation.put("recipient_public_key", receiverKey)
            paymentConfirmation.put("amount", amount)
            paymentConfirmation.put("timestamp", System.currentTimeMillis())
            /*paymentConfirmation.put("block_hash", transactionBlock.calculateHash().toHex())
            paymentConfirmation.put("sequence_number", transactionBlock.sequenceNumber)
            paymentConfirmation.put("block_timestamp", transactionBlock.timestamp.time)*/

            // Include actual Utxo transaction data
            paymentConfirmation.put("utxo_transaction", JSONObject(utxoTransactionJson))

            val paymentData = paymentConfirmation.toString()
            Log.d(TAG, "Created transaction block, sending payment confirmation")
            Log.d(TAG, "Payment confirmation data length: ${paymentData.length}")
            Log.d(TAG, "Payment confirmation preview: ${paymentData.take(150)}...")

            // Send payment confirmation via HCE card emulation
            startHCECardEmulation(
                jsonData = paymentData,
                message = "Sending payment confirmation...",
                timeoutSeconds = 30,
                expectResponse = false,
                onDataTransmitted = {
                    Log.d(TAG, "Phase 2: Payment confirmation sent successfully")
                    updateNFCDialogMessage("Payment sent successfully!")

                    Handler(Looper.getMainLooper()).postDelayed({
                        dismissNFCDialog()
                        completeTransaction()
                    }, 1500)
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error in sendPaymentConfirmation: ${e.message}", e)
            Toast.makeText(requireContext(), "Transaction failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Complete transaction and navigate to transaction history
     */
    private fun completeTransaction() {
        Log.d(TAG, "=== COMPLETE TRANSACTION ===")

        // Final cleanup
        getHCEHandler()?.stopHCECardEmulation()

        Toast.makeText(
            requireContext(),
            "Payment sent successfully!",
            Toast.LENGTH_LONG
        ).show()

        Log.d(TAG, "Navigating to transaction history")

        // Navigate to transaction history
        try {
            findNavController().navigate(R.id.action_sendMoneyFragment_to_transactionsFragment)
            Log.d(TAG, "Successfully navigated to transactions fragment")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to navigate: ${e.message}")
        }
    }

    /**
     * Allow user to retry Phase 1 transmission
     */
    private fun retryPhase1Transmission() {
        Log.d(TAG, "Retrying Phase 1 transmission")

        // Reset state
        isPhase1Complete = false
        receiverPublicKey = null

        // Complete cleanup
        getHCEHandler()?.stopHCECardEmulation()

        // Reset UI
        binding.btnContinue.text = "Retry Send"
        binding.btnContinue.isEnabled = true

        // Restart Phase 1 after cleanup
        Handler(Looper.getMainLooper()).postDelayed({
            startPhase1HCETransmission()
        }, 500)
    }

    /**
     * Handle NFC specific errors
     */
    override fun onNFCReadError(error: String) {
        Log.e(TAG, "NFC Error: $error")
        Toast.makeText(requireContext(), "NFC Error: $error", Toast.LENGTH_SHORT).show()

        // Reset and allow retry
        binding.btnContinue.isEnabled = true
    }

    override fun onNFCTimeout() {
        super.onNFCTimeout()
        Log.w(TAG, "NFC operation timed out")
        Toast.makeText(
            requireContext(),
            "Send timed out. Make sure the receiver has NFC activated and try again.",
            Toast.LENGTH_LONG
        ).show()

        // Reset and allow retry
        binding.btnContinue.isEnabled = true
    }

    override fun onNFCOperationCancelled() {
        super.onNFCOperationCancelled()
        Log.d(TAG, "NFC operation cancelled")

        // Reset and allow retry
        binding.btnContinue.isEnabled = true
    }

    override fun onDestroy() {
        super.onDestroy()
        // Ensure complete cleanup when fragment is destroyed
        getHCEHandler()?.stopHCECardEmulation()
    }

    /**
     * Get HCE handler from activity
     */
    override fun getHCEHandler(): EurotokenNFCBaseFragment.HCETransactionHandler? {
        return requireActivity() as? EurotokenNFCBaseFragment.HCETransactionHandler
    }
}
