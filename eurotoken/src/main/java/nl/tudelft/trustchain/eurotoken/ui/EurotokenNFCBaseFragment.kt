package nl.tudelft.trustchain.eurotoken.ui

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.LayoutRes
import nl.tudelft.trustchain.common.util.NFCUtils
import nl.tudelft.trustchain.eurotoken.ui.components.NFCActivationDialog

/**
 * Enhanced base fragment that handles NFC operations for EuroToken transfers
 * with improved dialog management and state handling
 */
abstract class EurotokenNFCBaseFragment(@LayoutRes contentLayoutId: Int = 0) : EurotokenBaseFragment(contentLayoutId) {

    protected val nfcUtils by lazy { NFCUtils(requireContext()) }

    // NFC Dialog Management
    private var nfcDialog: NFCActivationDialog? = null
    private var nfcTimeoutHandler: Handler = Handler(Looper.getMainLooper())
    private var nfcTimeoutRunnable: Runnable? = null

    // NFC State Management
    enum class NFCState {
        IDLE,
        WAITING_TO_SEND,
        WAITING_TO_RECEIVE,
        SENDING,
        RECEIVING,
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
     * Handle incoming NFC data with enhanced error handling
     */
    fun handleIncomingNFCIntent(intent: Intent) {
        updateNFCState(NFCState.RECEIVING)

        val jsonData = nfcUtils.processIncomingNFCIntent(intent)
        if (jsonData != null) {
            onNFCDataReceived(jsonData)
        } else {
            updateNFCState(NFCState.ERROR)
            onNFCReadError("No valid data found on NFC tag")
        }
    }

    /**
     * Show NFC activation dialog with proper state management
     */
    protected fun showNFCDialog(
        dialogType: NFCActivationDialog.NFCDialogType,
        amount: String = "",
        recipientName: String = "",
        timeoutSeconds: Int = 30
    ) {
        dismissNFCDialog() // Dismiss any existing dialog

        nfcDialog = NFCActivationDialog.newInstance(dialogType, amount, recipientName)
        nfcDialog?.setListener(object : NFCActivationDialog.NFCDialogListener {
            override fun onCancel() {
                updateNFCState(NFCState.IDLE)
                onNFCOperationCancelled()
            }

            override fun onRetry() {
                onNFCRetryRequested()
            }
        })

        nfcDialog?.show(parentFragmentManager, "nfc_dialog")

        // Set timeout for the operation
        if (timeoutSeconds > 0) {
            setNFCTimeout(timeoutSeconds * 1000L)
        }
    }

    /**
     * Update the current NFC dialog state
     */
    protected fun updateNFCDialogState(newDialogType: NFCActivationDialog.NFCDialogType) {
        nfcDialog?.updateDialogType(newDialogType)
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
     * Update the current NFC state and optionally update dialog
     */
    protected fun updateNFCState(newState: NFCState) {
        currentNFCState = newState

        val dialogType = when (newState) {
            NFCState.WAITING_TO_SEND -> NFCActivationDialog.NFCDialogType.WAITING_TO_SEND
            NFCState.WAITING_TO_RECEIVE -> NFCActivationDialog.NFCDialogType.WAITING_TO_RECEIVE
            NFCState.SENDING -> NFCActivationDialog.NFCDialogType.SENDING
            NFCState.RECEIVING -> NFCActivationDialog.NFCDialogType.RECEIVING
            NFCState.SUCCESS -> NFCActivationDialog.NFCDialogType.SUCCESS
            NFCState.ERROR -> NFCActivationDialog.NFCDialogType.ERROR
            NFCState.IDLE -> {
                dismissNFCDialog()
                return
            }
        }

        updateNFCDialogState(dialogType)
    }

    /**
     * Write data to NFC tag with enhanced dialog management
     */
    protected fun writeToNFC(
        jsonData: String,
        amount: String = "",
        recipientName: String = "",
        onResult: (Boolean) -> Unit
    ) {
        if (!nfcUtils.isNFCAvailable()) {
            onResult(false)
            showNFCNotAvailableMessage()
            return
        }

        // Show waiting dialog
        showNFCDialog(
            NFCActivationDialog.NFCDialogType.WAITING_TO_SEND,
            amount,
            recipientName
        )
        updateNFCState(NFCState.WAITING_TO_SEND)

        // Setup NFC write through activity
        requireActivity().let { activity ->
            if (activity is NFCWriteCapable) {
                activity.setupNFCWrite(jsonData) { success ->
                    if (success) {
                        updateNFCState(NFCState.SUCCESS)
                        onResult(true)

                        // Auto-dismiss success dialog after delay
                        Handler(Looper.getMainLooper()).postDelayed({
                            dismissNFCDialog()
                        }, 2000)
                    } else {
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
    protected fun activateNFCReceive(
        expectedDataType: String = "",
        timeoutSeconds: Int = 30
    ) {
        if (!nfcUtils.isNFCAvailable()) {
            showNFCNotAvailableMessage()
            return
        }

        showNFCDialog(
            NFCActivationDialog.NFCDialogType.WAITING_TO_RECEIVE,
            timeoutSeconds = timeoutSeconds
        )
        updateNFCState(NFCState.WAITING_TO_RECEIVE)
    }

    /**
     * Set a timeout for NFC operations
     */
    private fun setNFCTimeout(timeoutMs: Long) {
        cancelNFCTimeout()
        nfcTimeoutRunnable = Runnable {
            if (currentNFCState == NFCState.WAITING_TO_SEND || currentNFCState == NFCState.WAITING_TO_RECEIVE) {
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
     * Called when user requests to retry NFC operation
     */
    protected open fun onNFCRetryRequested() {
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
