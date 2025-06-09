package nl.tudelft.trustchain.eurotoken

import android.content.ComponentName
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import nl.tudelft.trustchain.common.BaseActivity
import nl.tudelft.trustchain.eurotoken.ui.EurotokenNFCBaseFragment
import nl.tudelft.trustchain.common.util.HCENFCUtils
import nl.tudelft.trustchain.eurotoken.nfc.HCEPaymentService
import kotlin.math.absoluteValue


class SimpleBloomFilter(private val size: Int = 128) {
    private val bitSet = BooleanArray(size)

    private fun hash1(input: String): Int = input.hashCode().absoluteValue % size
    private fun hash2(input: String): Int = (input.reversed().hashCode().absoluteValue) % size

    fun mightContain(item: String): Boolean {
        val h1 = hash1(item)
        val h2 = hash2(item)
        return bitSet[h1] && bitSet[h2]
    }

    fun add(item: String) {
        bitSet[hash1(item)] = true
        bitSet[hash2(item)] = true
    }
}



@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class EuroTokenMainActivity : BaseActivity(), EurotokenNFCBaseFragment.HCETransactionHandler {
    override val navigationGraph = R.navigation.nav_graph_eurotoken
    override val bottomNavigationMenu = R.menu.eurotoken_navigation_menu

    private val hceNfcUtils by lazy { HCENFCUtils(this) }
    private var isReaderModeActive = false
    private var cardEmulation: CardEmulation? = null

    private val bloomFilter = SimpleBloomFilter()

    companion object {
        private const val TAG = "EuroTokenMainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e(TAG, "=== ACTIVITY LIFECYCLE ===")
        Log.e(TAG, "onCreate called")

        initializeHCE()
    }

    private fun initializeHCE() {
        Log.d(TAG, "=== INITIALIZING HCE ===")

        val nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter != null) {
            cardEmulation = CardEmulation.getInstance(nfcAdapter)

            // Check if our HCE service is the default
            val serviceComponent = ComponentName(this, HCEPaymentService::class.java)
            val isDefault = cardEmulation?.isDefaultServiceForCategory(
                serviceComponent,
                CardEmulation.CATEGORY_PAYMENT
            ) ?: false

            Log.d(TAG, "HCE service is default: $isDefault")

            if (!isDefault) {
                Log.w(TAG, "Our HCE service is not the default payment service")
                // Optionally prompt user to set as default
                promptSetAsDefaultPaymentApp()
            }
        } else {
            Log.e(TAG, "NFC adapter is null - NFC not supported")
        }
    }

    private fun promptSetAsDefaultPaymentApp() {
        Toast.makeText(
            this,
            "Please set EuroToken as your default payment app in NFC settings",
            Toast.LENGTH_LONG
        ).show()

        // Open NFC payment settings
        try {
            startActivity(Intent(CardEmulation.ACTION_CHANGE_DEFAULT))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open NFC payment settings: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        Log.e(TAG, "onResume called")

        // Clear any old HCE data
        HCEPaymentService.clearPendingTransactionData()
        HCEPaymentService.clearOnDataReceivedCallback()
    }

    override fun onPause() {
        super.onPause()
        Log.e(TAG, "onPause called")

        if (isReaderModeActive) {
            disableReaderMode()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.e(TAG, "onDestroy called")

        // Clean up HCE data
        HCEPaymentService.clearPendingTransactionData()
        HCEPaymentService.clearOnDataReceivedCallback()
    }

    /**
     * Setup HCE card emulation mode for SENDING data
     */
    override fun setupHCECardEmulation(
        jsonData: String,
        onDataReceived: (String) -> Unit,
        onTimeout: () -> Unit
    ) {
        Log.e(TAG, "=== SETUP HCE CARD EMULATION ===")
        Log.e(TAG, "Setting up card emulation with data length: ${jsonData.length}")
        Log.e(TAG, "JSON preview: ${jsonData.take(100)}")

        if (!hceNfcUtils.isNFCAvailable()) {
            Log.w(TAG, "NFC is not available")
            Toast.makeText(this, "NFC is not available or disabled", Toast.LENGTH_LONG).show()
            onTimeout()
            return
        }

        // Set the pending data in HCE service
        HCEPaymentService.setPendingTransactionData(jsonData)

        // Set callback for when we receive data back
        HCEPaymentService.setOnDataReceivedCallback { receivedData ->
            Log.d(TAG, "HCE service received data callback triggered")
            runOnUiThread {
                onDataReceived(receivedData)
            }
        }

        Log.d(TAG, "HCE card emulation configured successfully")
        Log.d(TAG, "Waiting for reader to connect...")

        // Set timeout
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (HCEPaymentService.hasPendingData()) {
                Log.w(TAG, "HCE card emulation timeout")
                HCEPaymentService.clearPendingTransactionData()
                HCEPaymentService.clearOnDataReceivedCallback()
                onTimeout()
            }
        }, 60000) // 60 second timeout
    }

    /**
     * Setup reader mode for receiving data
     */
    override fun setupHCEReaderMode(
        onDataReceived: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        Log.e(TAG, "=== SETUP HCE READER MODE ===")

        checkAndDisplayNFCUnavailability(onError)

        isReaderModeActive = true

        hceNfcUtils.enableReaderMode(
            this,
            onTagDiscovered = { tag ->
                Log.d(TAG, "Tag discovered in reader mode, attempting to read data")

                // Try to receive data from the HCE service
                hceNfcUtils.receiveDataFromHCE(
                    tag,
                    onSuccess = { jsonData ->
                        Log.d(TAG, "Successfully received data from HCE")
                        runOnUiThread {
                            isReaderModeActive = false
                            hceNfcUtils.disableReaderMode(this)

                            if (bloomFilter.mightContain(jsonData)) {
                                Log.w(TAG, "Duplicate transaction detected: $jsonData")
                                Toast.makeText(this, "Duplicate transaction detected. You can't do that.", Toast.LENGTH_LONG).show()
                            } else {
                                bloomFilter.add(jsonData)
                                onDataReceived(jsonData)
                            }
                        }
                    },
                    onError = { error ->
                        Log.e(TAG, "Failed to receive data from HCE: $error")
                        runOnUiThread {
                            onError(error)
                        }
                    }
                )
            },
            onError = { error ->
                Log.e(TAG, "Reader mode error: $error")
                isReaderModeActive = false
                onError(error)
            }
        )

        Log.d(TAG, "HCE reader mode enabled successfully")
    }

    /**
     * Send data and receive response in reader mode
     */
    override fun sendDataAndReceiveResponse(
        jsonData: String,
        onResponseReceived: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        Log.e(TAG, "=== SEND DATA AND RECEIVE RESPONSE ===")
        Log.e(TAG, "Sending data length: ${jsonData.length}")

        checkAndDisplayNFCUnavailability (onError)

        isReaderModeActive = true

        hceNfcUtils.enableReaderMode(
            this,
            onTagDiscovered = { tag ->
                Log.d(TAG, "Tag discovered, sending data and waiting for response")

                // First send our data
                hceNfcUtils.sendDataToHCE(
                    tag,
                    jsonData,
                    onSuccess = {
                        Log.d(TAG, "Data sent successfully, now receiving response")

                        // Then immediately try to receive response
                        hceNfcUtils.receiveDataFromHCE(
                            tag,
                            onSuccess = { responseData ->
                                Log.d(TAG, "Successfully received response from HCE")
                                runOnUiThread {
                                    isReaderModeActive = false
                                    hceNfcUtils.disableReaderMode(this)
                                    onResponseReceived(responseData)
                                }
                            },
                            onError = { error ->
                                Log.e(TAG, "Failed to receive response: $error")
                                runOnUiThread {
                                    isReaderModeActive = false
                                    hceNfcUtils.disableReaderMode(this)
                                    onError("Failed to receive response: $error")
                                }
                            }
                        )
                    },
                    onError = { error ->
                        Log.e(TAG, "Failed to send data: $error")
                        runOnUiThread {
                            isReaderModeActive = false
                            hceNfcUtils.disableReaderMode(this)
                            onError("Failed to send data: $error")
                        }
                    }
                )
            },
            onError = { error ->
                Log.e(TAG, "Reader mode error: $error")
                isReaderModeActive = false
                onError(error)
            }
        )
    }

    /**
     * Disable reader mode
     */
    override fun disableReaderMode() {
        Log.e(TAG, "=== DISABLE READER MODE ===")
        if (isReaderModeActive) {
            hceNfcUtils.disableReaderMode(this)
            isReaderModeActive = false
            Log.d(TAG, "Reader mode disabled")
        }
    }

    /**
     * Stop HCE card emulation
     */
    override fun stopHCECardEmulation() {
        Log.e(TAG, "=== STOP HCE CARD EMULATION ===")
        HCEPaymentService.clearPendingTransactionData()
        HCEPaymentService.clearOnDataReceivedCallback()
        Log.d(TAG, "HCE card emulation stopped")
    }

    fun checkAndDisplayNFCUnavailability(onError: (String) -> Unit) {
        if (!hceNfcUtils.isNFCAvailable()) {
            Log.w(TAG, "NFC is not available")
            onError("NFC is not available or disabled")
            return
        }
    }

    /**
     * The values for shared preferences used by this activity.
     */
    object EurotokenPreferences {
        const val EUROTOKEN_SHARED_PREF_NAME = "eurotoken"
        const val DEMO_MODE_ENABLED = "demo_mode_enabled"
    }
}
