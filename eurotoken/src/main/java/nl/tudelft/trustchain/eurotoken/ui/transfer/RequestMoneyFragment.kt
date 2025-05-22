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

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        val json = requireArguments().getString(ARG_DATA)!!

        // Display payment request data - TODO: delet this
        binding.txtRequestData.text = json

        binding.qr.visibility = View.GONE

        showNFCInstructions()

        writeToNFC(json) { success ->
            if (success) {
                Toast.makeText(requireContext(), "Payment request sent! Transaction can proceed.", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(requireContext(), "Failed to send payment request", Toast.LENGTH_SHORT).show()
            }
        }

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
            "Payment request ready. Ask the sender to hold their phone near yours.",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Handle NFC data received
     */
    override fun onNFCDataReceived(jsonData: String) {
        Toast.makeText(requireContext(), "Received unexpected data via NFC", Toast.LENGTH_SHORT).show()
    }
    /**
     * Handle NFC speciofic error
     */
    override fun onNFCReadError(error: String) {
        Toast.makeText(requireContext(), "NFC error: $error", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ARG_DATA = "data"
    }
}
