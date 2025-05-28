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

    companion object {
        private const val TAG = "RequestMoneyFragment"
        const val ARG_DATA = "data"
    }

    private val binding by viewBinding(FragmentRequestMoneyBinding::bind)

    private var paymentRequestData: String? = null
    private var isPhase1Complete = false

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "=== REQUEST MONEY FRAGMENT VIEW CREATED ===")

        paymentRequestData = requireArguments().getString(ARG_DATA)!!
        Log.d(TAG, "Payment request data: ${paymentRequestData?.take(100)}...")

        // Hide QR code UI (legacy)
        binding.qr.visibility = View.GONE

        // Display status
        binding.txtRequestData.text = "Payment request ready for transmission"

        // Start Phase 1 HCE transmission immediately
        startPhase1HCETransmission()

        binding.btnContinue.setOnClickListener {
            if (isPhase1Complete) {
                Log.d(TAG, "Navigating back to activate Phase 2")
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
     * Start Phase 1 HCE transmission of payment request
     */
    private fun startPhase1HCETransmission() {
        Log.d(TAG, "=== START PHASE 1 HCE TRANSMISSION ===")

        paymentRequestData?.let { data ->
            Toast.makeText(
                requireContext(),
                "Ask the sender to activate NFC, then hold phones together",
                Toast.LENGTH_LONG
            ).show()

            // Use HCE card emulation mode to send payment request
            startHCECardEmulation(
                jsonData = data,
                message = "Sending payment request...",
                timeoutSeconds = 30,
                expectResponse = false,
                onSuccess = {
                    Log.d(TAG, "Payment request sent successfully")
                    isPhase1Complete = true
                    binding.txtRequestData.text = "Payment request sent! Waiting for response..."
                    binding.btnContinue.text = "Activate Phase 2"

                    Toast.makeText(
                        requireContext(),
                        "Payment request sent successfully! You can now activate Phase 2 to receive the payment.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        } ?: run {
            Log.e(TAG, "No payment request data available")
            Toast.makeText(
                requireContext(),
                "Error: No payment request data",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Allow user to retry Phase 1 transmission
     */
    private fun retryPhase1Transmission() {
        Log.d(TAG, "Retrying Phase 1 transmission")
        binding.btnContinue.text = "Retry Request"
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
            "Request timed out. Make sure the sender has NFC activated and try again.",
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
