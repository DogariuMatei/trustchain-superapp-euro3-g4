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
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.contacts.ContactStore
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentSendMoneyBinding
import nl.tudelft.trustchain.eurotoken.nfc.HCEPaymentService
import nl.tudelft.trustchain.eurotoken.ui.EurotokenNFCBaseFragment
import org.json.JSONObject

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
                Log.d(TAG, "Navigating back to transfer fragment for Phase 2")
                val args = Bundle()
                args.putBoolean("phase2_ready", true)
                findNavController().navigate(R.id.transferFragment, args)
            } else {
                Toast.makeText(requireContext(), "Please complete Phase 1 first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Create sender payload with transaction info and trust data
     */
    private fun createSenderPayload() {
        Log.d(TAG, "=== CREATE SENDER PAYLOAD ===")

        val myPeer = transactionRepository.trustChainCommunity.myPeer
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
        val currentBalance = transactionRepository.getMyBalance()
        senderInfo.put("sender_balance", currentBalance)

        senderPayloadData = senderInfo.toString()
        Log.d(TAG, "Sender payload created: ${senderPayloadData?.take(200)}...")
    }

    /**
     * Start Phase 1 HCE transmission of sender info
     */
    private fun startPhase1HCETransmission() {
        Log.d(TAG, "=== START PHASE 1 HCE TRANSMISSION ===")

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
                expectResponse = false,
                onSuccess = {
                    Log.d(TAG, "HCE card emulation ready - waiting for reader")
                },
                onDataTransmitted = {
                    // This is called when data is actually read by the receiver
                    Log.d(TAG, "Sender info successfully transmitted!")
                    isPhase1Complete = true

                    // Update UI to show success
                    updateNFCDialogMessage("Payment details sent!")

                    // Dismiss dialog after a short delay
                    Handler(Looper.getMainLooper()).postDelayed({
                        dismissNFCDialog()

                        // Update UI to show Phase 1 is complete
                        binding.txtSendData.text = "Payment details sent. Ready for Phase 2."
                        binding.btnContinue.text = "Continue to Send Payment"
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
     * Allow user to retry Phase 1 transmission
     */
    private fun retryPhase1Transmission() {
        Log.d(TAG, "Retrying Phase 1 transmission")
        binding.btnContinue.text = "Retry Send"
        binding.btnContinue.setOnClickListener {
            startPhase1HCETransmission()
        }
    }

    /**
     * Handle NFC specific errors
     */
    override fun onNFCReadError(error: String) {
        Log.e(TAG, "NFC Error: $error")
        Toast.makeText(requireContext(), "NFC Error: $error", Toast.LENGTH_SHORT).show()

        // Allow retry on error
        retryPhase1Transmission()
    }

    override fun onNFCTimeout() {
        super.onNFCTimeout()
        Log.w(TAG, "Phase 1 transmission timed out")
        Toast.makeText(
            requireContext(),
            "Send timed out. Make sure the receiver has NFC activated and try again.",
            Toast.LENGTH_LONG
        ).show()

        // Allow retry on timeout
        retryPhase1Transmission()
    }

    override fun onNFCOperationCancelled() {
        super.onNFCOperationCancelled()
        Log.d(TAG, "Phase 1 transmission cancelled")

        // Allow retry on cancel
        retryPhase1Transmission()
    }
}
