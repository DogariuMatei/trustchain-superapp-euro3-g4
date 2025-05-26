package nl.tudelft.trustchain.eurotoken.ui.transfer

import android.util.Log
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.navigation.fragment.findNavController
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentRequestMoneyBinding
import nl.tudelft.trustchain.eurotoken.ui.EurotokenNFCBaseFragment

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@SuppressLint("SetTextI18n")
class RequestMoneyFragment : EurotokenNFCBaseFragment(R.layout.fragment_request_money) {
    private val binding by viewBinding(FragmentRequestMoneyBinding::bind)

    private var paymentRequestData: String? = null
    private var isPhase1Complete = false

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        paymentRequestData = requireArguments().getString(ARG_DATA)!!
        log.e ("Request Fragment got args: ${paymentRequestData}")
        // Hide any QR-related UI elements
        binding.qr.visibility = View.GONE

        // Display payment request data for debugging (can be removed later)
        binding.txtRequestData.text = "Payment request ready for NFC transmission"

        // Start Phase 1 NFC transmission immediately
        startPhase1NFCTransmission()

        binding.btnContinue.setOnClickListener {
            if (isPhase1Complete) {
                // Navigate back to TransferFragment with Phase 2 activation signal
                val args = Bundle()
                args.putBoolean("activate_phase2", true)
                findNavController().navigate(R.id.transferFragment, args)
            } else {
                Toast.makeText(requireContext(), "Please complete Phase 1 first", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Start Phase 1 NFC transmission of payment request
     */

    private fun startPhase1NFCTransmission() {
        paymentRequestData?.let { data ->
            Toast.makeText(
                requireContext(),
                "Ask the sender to activate NFC, then hold phones together",
                Toast.LENGTH_LONG
            ).show()

            writeToNFC(data) { success ->
                if (success) {
                    isPhase1Complete = true
                    binding.txtRequestData.text = "Payment request sent! Waiting for response..."
                    binding.btnContinue.text = "Activate Phase 2"

                    Toast.makeText(
                        requireContext(),
                        "Payment request sent successfully! You can now activate Phase 2 to receive the payment.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Failed to send payment request. Please try again.",
                        Toast.LENGTH_SHORT
                    ).show()
                    // Allow retry
                    retryPhase1Transmission()
                }
            }
        }
    }

    /**
     * Allow user to retry Phase 1 transmission
     */

    private fun retryPhase1Transmission() {
        binding.btnContinue.text = "Retry Request"
        binding.btnContinue.setOnClickListener {
            startPhase1NFCTransmission()
        }
    }

    /**
     * Handle incoming NFC data - Should not receive data in Phase 1, but could receive Phase 2 data
     */
    override fun onNFCDataReceived(jsonData: String) {
        try {
            val receivedData = org.json.JSONObject(jsonData)
            val dataType = receivedData.optString("type")

            when (dataType) {
                "payment_confirmation" -> {
                    // Received Phase 2 payment confirmation - process it
                    handlePhase2PaymentConfirmation(receivedData)
                }
                else -> {
                    Toast.makeText(
                        requireContext(),
                        "Received unexpected data type: $dataType",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        } catch (e: Exception) {
            onNFCReadError("Invalid data received: ${e.message}")
        }
    }

    /**
     * Handle Phase 2 payment confirmation received directly in RequestMoneyFragment
     * This happens if the user stays on this screen during both phases
     */

    private fun handlePhase2PaymentConfirmation(confirmationData: org.json.JSONObject) {
        try {
            // Extract transaction data
            val senderName = confirmationData.optString("sender_name")
            val amount = confirmationData.optLong("amount", -1L)

            if (amount > 0) {
                val displayName = if (senderName.isNotEmpty()) senderName else "Unknown"
                Toast.makeText(
                    requireContext(),
                    "Payment of ${nl.tudelft.trustchain.common.eurotoken.TransactionRepository.prettyAmount(amount)} received from $displayName!",
                    Toast.LENGTH_LONG
                ).show()

                binding.txtRequestData.text = "Payment received successfully!"

                // Navigate to transaction history
                findNavController().navigate(R.id.action_requestMoneyFragment_to_transactionsFragment)
            } else {
                Toast.makeText(requireContext(), "Invalid payment confirmation", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            log.e ("Error processing payment confirmation: ${e.message}")
            Toast.makeText(requireContext(), "Failed to process payment: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Handle NFC specific errors
     */
    override fun onNFCReadError(error: String) {
        Toast.makeText(requireContext(), "NFC Error: $error", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ARG_DATA = "data"
    }
}
