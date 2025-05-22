package nl.tudelft.trustchain.eurotoken.ui

import android.content.Intent
import android.widget.Toast
import androidx.annotation.LayoutRes
import nl.tudelft.trustchain.common.util.NFCUtils

/**
 * Base fragment that handles NFC operations for EuroToken transfers
 */
abstract class EurotokenNFCBaseFragment(@LayoutRes contentLayoutId: Int = 0) : EurotokenBaseFragment(contentLayoutId) {

    protected val nfcUtils by lazy { NFCUtils(requireContext()) }

    override fun onResume() {
        super.onResume()

        // Enable NFC reading when fragment is visible
        if (nfcUtils.isNFCAvailable()) {
            nfcUtils.enableNFCReading(requireActivity())
        } else {
            Toast.makeText(requireContext(), "NFC is not available or disabled", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()

        // Disable NFC reading when fragment not visible
        if (nfcUtils.isNFCAvailable()) {
            nfcUtils.disableNFCReading(requireActivity())
        }
    }

    /**
     * Handle new NFC intent
     */
    fun handleNFCIntent(intent: Intent) {
        val jsonData = nfcUtils.processNFCIntent(intent)
        if (jsonData != null) {
            onNFCDataReceived(jsonData)
        } else {
            onNFCReadError("No valid data found on NFC tag")
        }
    }

    /**
     * Write data to NFC tag
     */
    protected fun writeToNFC(jsonData: String, onResult: (Boolean) -> Unit) {
        // TODO:
        // This will be triggered when user taps an NFC tag
        // We'll store the data to write and callback for when tag is detected
        setupNFCWrite(jsonData, onResult)
    }

    /**
     * Setup for NFC writing - stores data until tag is detected
     */
    private fun setupNFCWrite(jsonData: String, onResult: (Boolean) -> Unit) {
        // TODO:
        // For writing, we need to wait for a tag to be detected
        // This would typically involve setting up a pending write operation
        // For now, we'll show instructions to user
        showNFCWriteInstructions(jsonData, onResult)
    }

    /**
     * Show instructions for NFC writing
     */
    private fun showNFCWriteInstructions(jsonData: String, onResult: (Boolean) -> Unit) {
        Toast.makeText(
            requireContext(),
            "Hold your phone near the receiver's phone to transfer payment request",
            Toast.LENGTH_LONG
        ).show()

        requireActivity().let { activity ->
            if (activity is NFCWriteCapable) {
                activity.setupNFCWrite(jsonData, onResult)
            }
        }
    }

    /**
     * Called when valid NFC data is received
     */
    protected abstract fun onNFCDataReceived(jsonData: String)

    /**
     * Called when NFC read fails
     */
    protected open fun onNFCReadError(error: String) {
        Toast.makeText(requireContext(), "NFC Read Error: $error", Toast.LENGTH_SHORT).show()
    }

    /**
     * Interface for activities that can handle NFC writing
     */
    interface NFCWriteCapable {
        fun setupNFCWrite(jsonData: String, onResult: (Boolean) -> Unit)
    }
}
