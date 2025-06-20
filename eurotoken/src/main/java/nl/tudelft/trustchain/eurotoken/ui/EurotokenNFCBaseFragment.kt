package nl.tudelft.trustchain.eurotoken.ui

import android.util.Log
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.LayoutRes
import androidx.annotation.RequiresApi
import nl.tudelft.trustchain.eurotoken.nfc.HCEPaymentService
import nl.tudelft.trustchain.eurotoken.ui.components.NFCActivationDialog

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
abstract class EurotokenNFCBaseFragment(@LayoutRes contentLayoutId: Int = 0) : EurotokenBaseFragment(contentLayoutId) {

    companion object {
        private const val TAG = "EurotokenNFCBaseFragment"
    }

    // NFC Dialog Management
    private var nfcDialog: NFCActivationDialog? = null
    private var nfcTimeoutHandler: Handler = Handler(Looper.getMainLooper())
    private var nfcTimeoutRunnable: Runnable? = null

    // HCE Operation States
    enum class HCEOperationType {
        NONE,
        CARD_EMULATION,      // Acting as a card (sender in phase 2)
        READER_MODE,         // Acting as a reader (receiver in phase 1)
        SEND_AND_RECEIVE     // Send data then receive response
    }

    private var currentOperation: HCEOperationType = HCEOperationType.NONE

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "Fragment resumed: ${this::class.simpleName}")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "Fragment paused: ${this::class.simpleName}")

        // Cleanup any active NFC operations
        cleanupNFCOperations()
    }

    /**
     * Start HCE card emulation mode (device acts as a card)
     * Used when this device needs to send data and potentially receive a response
     */
    protected fun startHCECardEmulation(
        jsonData: String,
        message: String = "Hold phones together...",
        timeoutSeconds: Int = 30,
        expectResponse: Boolean = false,
        onSuccess: () -> Unit = {},
        onDataTransmitted: (() -> Unit)? = null,
        onResponseReceived: ((String) -> Unit)? = null
    ) {
        Log.d(TAG, "=== START HCE CARD EMULATION ===")
        Log.d(TAG, "Expect response: $expectResponse")

        currentOperation = HCEOperationType.CARD_EMULATION
        showNFCDialog(message, timeoutSeconds)

        HCEPaymentService.setPendingTransactionData(jsonData)
        if (onDataTransmitted != null) {
            HCEPaymentService.setOnDataTransmittedCallback {
                Log.d(TAG, "Data successfully transmitted")
                Handler(Looper.getMainLooper()).post {
                    onDataTransmitted()
                }
            }
        }


        getHCEHandler()?.setupHCECardEmulation(
            jsonData = jsonData,
            onDataReceived = { responseData ->
                Log.d(TAG, "Card emulation received response data")
                if (expectResponse && onResponseReceived != null) {
                    updateNFCDialogMessage("Response received!")
                    Handler(Looper.getMainLooper()).postDelayed({
                        dismissNFCDialog()
                        onResponseReceived(responseData)
                    }, 1000)
                }
            },
            onTimeout = {
                Log.w(TAG, "Card emulation timeout")
                dismissNFCDialog()
                onNFCTimeout()
            }
        ) ?: run {
            Log.e(TAG, "HCE handler not available")
            dismissNFCDialog()
            Toast.makeText(requireContext(), "NFC not available", Toast.LENGTH_SHORT).show()
        }

        // Don't call onSuccess here - wait for actual transmission
    }

    /**
     * Start HCE reader mode (device acts as a reader)
     * Used when this device needs to receive data from another device
     */
    protected fun startHCEReaderMode(
        message: String = "Ready to receive. Hold phones together...",
        timeoutSeconds: Int = 60,
        onDataReceived: (String) -> Unit
    ) {
        Log.d(TAG, "=== START HCE READER MODE ===")

        currentOperation = HCEOperationType.READER_MODE
        showNFCDialog(message, timeoutSeconds)

        getHCEHandler()?.setupHCEReaderMode(
            onDataReceived = { jsonData ->
                Log.d(TAG, "Reader mode received data: ${jsonData.take(100)}...")
                updateNFCDialogMessage("Data received!")
                Handler(Looper.getMainLooper()).postDelayed({
                    dismissNFCDialog()
                    onDataReceived(jsonData)
                }, 1000)
            },
            onError = { error ->
                Log.e(TAG, "Reader mode error: $error")
                dismissNFCDialog()
                onNFCReadError(error)
            }
        ) ?: run {
            Log.e(TAG, "HCE handler not available")
            dismissNFCDialog()
            Toast.makeText(requireContext(), "NFC not available", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Show NFC operation dialog
     */
    protected fun showNFCDialog(message: String, timeoutSeconds: Int = 60) {
        dismissNFCDialog() // Dismiss any existing dialog

        nfcDialog = NFCActivationDialog.newInstance(message)
        nfcDialog?.setListener(object : NFCActivationDialog.NFCDialogListener {
            override fun onCancel() {
                Log.d(TAG, "NFC dialog cancelled by user")
                cleanupNFCOperations()
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
     * Set a timeout for NFC operations
     */
    private fun setNFCTimeout(timeoutMs: Long) {
        cancelNFCTimeout()
        nfcTimeoutRunnable = Runnable {
            Log.w(TAG, "NFC operation timeout")
            cleanupNFCOperations()
            onNFCTimeout()
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
     * Clean up all NFC operations
     */
    private fun cleanupNFCOperations() {
        Log.d(TAG, "Cleaning up NFC operations")

        dismissNFCDialog()

        when (currentOperation) {
            HCEOperationType.CARD_EMULATION -> {
                // Don't immediately stop HCE - let it complete the transaction
                Log.d(TAG, "HCE card emulation active - will clean up after transmission")
            }
            HCEOperationType.READER_MODE, HCEOperationType.SEND_AND_RECEIVE -> {
                getHCEHandler()?.disableReaderMode()
            }
            HCEOperationType.NONE -> {
                // Nothing to clean up
            }
        }

        currentOperation = HCEOperationType.NONE
    }

    /**
     * Get HCE handler from activity
     */
    protected open fun getHCEHandler(): HCETransactionHandler? {
        return requireActivity() as? HCETransactionHandler
    }

    /**
     * Called when NFC read fails
     */
    protected open fun onNFCReadError(error: String) {
        Toast.makeText(requireContext(), "NFC Error: $error", Toast.LENGTH_SHORT).show()
    }

    /**
     * Called when NFC operation is cancelled by user
     */
    protected open fun onNFCOperationCancelled() {
        Log.d(TAG, "NFC operation cancelled")
    }

    /**
     * Called when NFC operation times out
     */
    protected open fun onNFCTimeout() {
        Toast.makeText(requireContext(), "NFC operation timed out. Please try again.", Toast.LENGTH_LONG).show()
    }

    /**
     * Interface for activities that can handle HCE transactions
     */
    interface HCETransactionHandler {
        fun setupHCECardEmulation(
            jsonData: String,
            onDataReceived: (String) -> Unit,
            onTimeout: () -> Unit
        )

        fun setupHCEReaderMode(
            onDataReceived: (String) -> Unit,
            onError: (String) -> Unit
        )

        fun sendDataAndReceiveResponse(
            jsonData: String,
            onResponseReceived: (String) -> Unit,
            onError: (String) -> Unit
        )

        fun disableReaderMode()
        fun stopHCECardEmulation()
    }
}
