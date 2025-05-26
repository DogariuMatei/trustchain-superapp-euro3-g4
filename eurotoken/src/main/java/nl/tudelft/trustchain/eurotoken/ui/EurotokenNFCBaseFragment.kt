package nl.tudelft.trustchain.eurotoken.ui

import android.util.Log
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.LayoutRes
import androidx.annotation.RequiresApi
import nl.tudelft.trustchain.common.util.NFCUtils
import nl.tudelft.trustchain.eurotoken.ui.components.NFCActivationDialog

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
abstract class EurotokenNFCBaseFragment(@LayoutRes contentLayoutId: Int = 0) : EurotokenBaseFragment(contentLayoutId) {

    protected val nfcUtils by lazy { NFCUtils(requireContext()) }

    // NFC Dialog Management
    private var nfcDialog: NFCActivationDialog? = null
    private var nfcTimeoutHandler: Handler = Handler(Looper.getMainLooper())
    private var nfcTimeoutRunnable: Runnable? = null

    // Simple NFC State Management
    enum class NFCState {
        IDLE,
        WAITING,
        SUCCESS,
        ERROR
    }

    private var currentNFCState: NFCState = NFCState.IDLE

    override fun onResume() {
        super.onResume()
        // Enable NFC reading when fragment is visible
        if (nfcUtils.isNFCAvailable()) {
            nfcUtils.enableNFCReading(requireActivity())
        } else {
            showNFCNotAvailableMessage()
        }
    }

    override fun onPause() {
        super.onPause()
        // Disable NFC reading when fragment not visible
        if (nfcUtils.isNFCAvailable()) {
            nfcUtils.disableNFCReading(requireActivity())
        }

        // Cleanup dialog and timeouts
        dismissNFCDialog()
        cancelNFCTimeout()
    }

    /**
     * Handle incoming NFC data
     */
    fun handleIncomingNFCIntent(intent: Intent) {
        updateNFCState(NFCState.WAITING)

        val jsonData = nfcUtils.processIncomingNFCIntent(intent)
        if (jsonData != null) {
            onNFCDataReceived(jsonData)
        } else {
            updateNFCState(NFCState.ERROR)
            onNFCReadError("No valid data found on NFC tag")
        }
    }

    /**
     * Show simple NFC dialog
     */
    protected fun showNFCDialog(message: String, timeoutSeconds: Int = 30) {
        dismissNFCDialog() // Dismiss any existing dialog

        nfcDialog = NFCActivationDialog.newInstance(message)
        nfcDialog?.setListener(object : NFCActivationDialog.NFCDialogListener {
            override fun onCancel() {
                updateNFCState(NFCState.IDLE)
                onNFCOperationCancelled()
            }
        })

        nfcDialog?.show(parentFragmentManager, "nfc_dialog")

        // Set timeout for the operation
        if (timeoutSeconds > 0) {
            setNFCTimeout(timeoutSeconds * 1000L)
        }
    }

    /**
     * Update dialog message
     */
    protected fun updateNFCDialogMessage(newMessage: String) {
        nfcDialog?.updateMessage(newMessage)
    }

    /**
     * Dismiss the current NFC dialog
     */
    protected fun dismissNFCDialog() {
        nfcDialog?.dismiss()
        nfcDialog = null
        cancelNFCTimeout()
    }

    /**
     * Update the current NFC state
     */
    protected fun updateNFCState(newState: NFCState) {
        currentNFCState = newState

        when (newState) {
            NFCState.WAITING -> updateNFCDialogMessage("Hold phones together...")
            NFCState.SUCCESS -> updateNFCDialogMessage("Success!")
            NFCState.ERROR -> updateNFCDialogMessage("Error occurred. Please try again.")
            NFCState.IDLE -> dismissNFCDialog()
        }
    }

    /**
     * Write data to NFC tag
     */
    protected fun writeToNFC(jsonData: String, onResult: (Boolean) -> Unit) {
        if (!nfcUtils.isNFCAvailable()) {
            onResult(false)
            showNFCNotAvailableMessage()
            return
        }

        // Show waiting dialog
        showNFCDialog("Hold phones together to send data...")
        updateNFCState(NFCState.WAITING)

        // Setup NFC write through activity
        requireActivity().let { activity ->
            if (activity is NFCWriteCapable) {
                activity.setupNFCWrite(jsonData) { success ->
                    if (success) {
                        updateNFCState(NFCState.SUCCESS)
                        onResult(true)
                        Log.e("EurotokenNFCBaseFragment", "Sending NFC payload SUCCESS")
                        // Auto-dismiss success dialog after delay
                        Handler(Looper.getMainLooper()).postDelayed({
                            dismissNFCDialog()
                        }, 2000)
                    } else {
                        Log.e("EurotokenNFCBaseFragment", "Sending NFC payload FAILED in NFCBaseFragment")
                        updateNFCState(NFCState.ERROR)
                        onResult(false)
                    }
                }
            } else {
                updateNFCState(NFCState.ERROR)
                onResult(false)
            }
        }
    }

    /**
     * Activate NFC for receiving data
     */
    protected fun activateNFCReceive(expectedDataType: String = "", timeoutSeconds: Int = 30) {
        if (!nfcUtils.isNFCAvailable()) {
            showNFCNotAvailableMessage()
            return
        }

        showNFCDialog("Ready to receive data. Hold phones together...", timeoutSeconds)
        updateNFCState(NFCState.WAITING)
    }

    /**
     * Set a timeout for NFC operations
     */
    private fun setNFCTimeout(timeoutMs: Long) {
        cancelNFCTimeout()
        nfcTimeoutRunnable = Runnable {
            if (currentNFCState == NFCState.WAITING) {
                updateNFCState(NFCState.ERROR)
                onNFCTimeout()
            }
        }
        nfcTimeoutHandler.postDelayed(nfcTimeoutRunnable!!, timeoutMs)
    }

    /**
     * Cancel any pending NFC timeout
     */
    private fun cancelNFCTimeout() {
        nfcTimeoutRunnable?.let { runnable ->
            nfcTimeoutHandler.removeCallbacks(runnable)
        }
        nfcTimeoutRunnable = null
    }

    /**
     * Show message when NFC is not available
     */
    private fun showNFCNotAvailableMessage() {
        Toast.makeText(
            requireContext(),
            "NFC is not available or disabled. Please enable NFC in settings.",
            Toast.LENGTH_LONG
        ).show()
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
     * Called when NFC operation is cancelled by user
     */
    protected open fun onNFCOperationCancelled() {
        // Override in subclasses if needed
    }

    /**
     * Called when NFC operation times out
     */
    protected open fun onNFCTimeout() {
        Toast.makeText(requireContext(), "NFC operation timed out. Please try again.", Toast.LENGTH_LONG).show()
    }

    /**
     * Interface for activities that can handle NFC writing
     */
    interface NFCWriteCapable {
        fun setupNFCWrite(jsonData: String, onResult: (Boolean) -> Unit)
    }
}
