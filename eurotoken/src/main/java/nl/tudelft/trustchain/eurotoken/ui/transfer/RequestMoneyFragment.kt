package nl.tudelft.trustchain.eurotoken.ui.transfer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.navigation.fragment.findNavController
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentRequestMoneyBinding
import nl.tudelft.trustchain.eurotoken.ui.EurotokenNFCBaseFragment
import org.json.JSONObject

/**
 * Fragment for handling payment requests via NFC
 * This has been updated to handle the NFC-based payment flow
 */
class RequestMoneyFragment : EurotokenNFCBaseFragment(R.layout.fragment_request_money) {
    private val binding by viewBinding(FragmentRequestMoneyBinding::bind)

    // Payment request data
    private var jsonData: String = ""
    private var amount: Long = 0

    // Status tracking
    private var requestSent = false
    private var requestCompleted = false

    // Handler for updating the status message
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRunnable = object : Runnable {
        override fun run() {
            if (!requestCompleted && isAdded) {
                // Cycle through status messages to indicate the app is still working
                val currentText = binding.txtStatus.text.toString()

                binding.txtStatus.text = when {
                    currentText.endsWith("...") -> "Waiting for device."
                    currentText.endsWith("..") -> "Waiting for device..."
                    currentText.endsWith(".") -> "Waiting for device.."
                    else -> "Waiting for device."
                }

                // Schedule next update
                statusHandler.postDelayed(this, 500)
            }
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        jsonData = requireArguments().getString(ARG_DATA)!!

        try {
            // Parse the JSON to extract payment information
            val json = JSONObject(jsonData)
            amount = json.optLong("amount", 0)

            // Display the amount in the UI
            binding.txtAmount.text = TransactionRepository.prettyAmount(amount)

            // Display your public key
            val myPublicKey = getIpv8().myPeer.publicKey.keyToBin().toHex()
            binding.txtYourId.text = myPublicKey.take(16) + "..." + myPublicKey.takeLast(8)

        } catch (e: Exception) {
            Log.e("RequestMoneyFragment", "Error parsing JSON: ${e.message}")
        }

        // Start the NFC sending process
        initiateNFCPaymentRequest()

        // Set up the Cancel button to abort
        binding.btnCancel.setOnClickListener {
            abortPaymentRequest()
        }

        // Set up the Done button to continue
        binding.btnContinue.setOnClickListener {
            if (requestSent) {
                // If payment request is sent, move to transactions screen
                Toast.makeText(requireContext(), "Payment request sent!", Toast.LENGTH_SHORT).show()
                findNavController().navigate(R.id.action_requestMoneyFragment_to_transactionsFragment)
            } else {
                // If not sent, ask if they want to retry
                retryPaymentRequest()
            }
        }

        // Start the status animation
        statusHandler.post(statusRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Stop the status animation
        statusHandler.removeCallbacks(statusRunnable)
    }

    /**
     * Start the NFC payment request process
     */
    private fun initiateNFCPaymentRequest() {
        binding.txtStatus.text = "Waiting for device..."
        binding.progressStatus.isIndeterminate = true

        writeToNFC(jsonData) { success ->
            requestSent = success
            requestCompleted = true

            if (success) {
                // Update UI for success
                binding.txtStatus.text = "Payment request sent successfully!"
                binding.progressStatus.isIndeterminate = false
                binding.progressStatus.progress = 100
                binding.btnContinue.text = "Continue"
            } else {
                // Update UI for failure
                binding.txtStatus.text = "Failed to send payment request"
                binding.progressStatus.isIndeterminate = false
                binding.progressStatus.progress = 0
                binding.btnContinue.text = "Retry"
            }
        }
    }

    /**
     * Retry sending the payment request
     */
    private fun retryPaymentRequest() {
        requestSent = false
        requestCompleted = false

        // Reset UI
        binding.progressStatus.isIndeterminate = true

        // Restart status animation
        statusHandler.post(statusRunnable)

        // Try again
        initiateNFCPaymentRequest()
    }

    /**
     * Abort the payment request and go back
     */
    private fun abortPaymentRequest() {
        // Stop any pending NFC operations
        requestCompleted = true
        statusHandler.removeCallbacks(statusRunnable)

        Toast.makeText(requireContext(), "Payment request canceled", Toast.LENGTH_SHORT).show()
        findNavController().popBackStack()
    }

    /**
     * Handle NFC data received (not expected in this fragment)
     */
    override fun onNFCDataReceived(jsonData: String) {
        // This fragment is for sending requests, not receiving them
        // But if we get data, we can update our UI
        Toast.makeText(requireContext(), "Unexpected data received via NFC", Toast.LENGTH_SHORT).show()
    }

    /**
     * Handle payment declined notification
     */
    override fun onPaymentDeclined(message: String) {
        requestCompleted = true
        statusHandler.removeCallbacks(statusRunnable)

        binding.txtStatus.text = "Payment request was declined"
        binding.progressStatus.isIndeterminate = false
        binding.progressStatus.progress = 0

        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val ARG_DATA = "data"
    }
}
