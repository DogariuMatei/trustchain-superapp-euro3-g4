package nl.tudelft.trustchain.eurotoken.ui.transfer

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.navigation.fragment.findNavController
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentRequestMoneyBinding
import nl.tudelft.trustchain.eurotoken.ui.EurotokenNFCBaseFragment

class RequestMoneyFragment : EurotokenNFCBaseFragment(R.layout.fragment_request_money) {
    private val binding by viewBinding(FragmentRequestMoneyBinding::bind)

    private var paymentRequestData: String? = null

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        paymentRequestData = requireArguments().getString(ARG_DATA)!!

        // Hide any QR-related UI elements
        binding.qr.visibility = View.GONE

        // Display payment request data for debugging (can be removed later)
        binding.txtRequestData.text = "Payment request ready for NFC transmission"

        showNFCInstructions()

        // Automatically start NFC transmission
        startNFCTransmission()

        binding.btnContinue.setOnClickListener {
            findNavController().navigate(R.id.action_requestMoneyFragment_to_transactionsFragment)
        }
    }

    /**
     * Show instructions for NFC usage
     */
    private fun showNFCInstructions() {
        Toast.makeText(
            requireContext(),
            "Payment request ready. Ask the sender to hold their phone near yours to receive the request.",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Start NFC transmission of payment request
     */
    private fun startNFCTransmission() {
        paymentRequestData?.let { data ->
            writeToNFC(data) { success ->
                if (success) {
                    Toast.makeText(
                        requireContext(),
                        "Payment request sent successfully! Waiting for response...",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Failed to send payment request. Please try again.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * Handle incoming NFC data - This should receive the payment confirmation (Phase 2)
     */
    override fun onNFCDataReceived(jsonData: String) {
        try {
            // Parse the received data to determine what type it is
            val receivedData = org.json.JSONObject(jsonData)
            val dataType = receivedData.optString("type")

            when (dataType) {
                "payment_confirmation" -> {
                    // Received payment confirmation from sender
                    Toast.makeText(
                        requireContext(),
                        "Payment confirmation received! Processing transaction...",
                        Toast.LENGTH_LONG
                    ).show()

                    // Process the payment confirmation
                    processPaymentConfirmation(receivedData)
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
     * Process the payment confirmation received from sender
     */
    private fun processPaymentConfirmation(confirmationData: org.json.JSONObject) {
        // TODO: Implement actual transaction processing
        // This will be implemented in Phase 4 of the plan

        Toast.makeText(
            requireContext(),
            "Transaction completed successfully!",
            Toast.LENGTH_LONG
        ).show()

        // Navigate to transaction history
        findNavController().navigate(R.id.action_requestMoneyFragment_to_transactionsFragment)
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
