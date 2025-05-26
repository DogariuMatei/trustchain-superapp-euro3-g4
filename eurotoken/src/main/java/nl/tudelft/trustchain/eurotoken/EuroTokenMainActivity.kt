package nl.tudelft.trustchain.eurotoken

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import nl.tudelft.trustchain.common.BaseActivity
import nl.tudelft.trustchain.eurotoken.ui.EurotokenNFCBaseFragment
import nl.tudelft.trustchain.common.util.NFCUtils

class EuroTokenMainActivity : BaseActivity(), EurotokenNFCBaseFragment.NFCWriteCapable {
    override val navigationGraph = R.navigation.nav_graph_eurotoken
    override val bottomNavigationMenu = R.menu.eurotoken_navigation_menu

    private val nfcUtils by lazy { NFCUtils(this) }
    private var pendingNFCWrite: PendingNFCWrite? = null

    companion object {
        private const val TAG = "EuroTokenMainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log.e(TAG, "onCreate called")

        // Handle NFC intent if app was launched via NFC
        handleNFCIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        log.e(TAG, "onNewIntent called with action: ${intent?.action}")

        if (intent != null) {
            // Set this as the current intent
            setIntent(intent)
            handleNFCIntent(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        log.e(TAG, "onResume called")

        // Enable NFC reading when activity is resumed
        if (nfcUtils.isNFCAvailable()) {
            nfcUtils.enableNFCReading(this)
            log.e(TAG, "NFC reading enabled in onResume")
        } else {
            Log.w(TAG, "NFC not available in onResume")
        }
    }

    override fun onPause() {
        super.onPause()
        log.e(TAG, "onPause called")

        // Disable NFC reading when activity is paused
        if (nfcUtils.isNFCAvailable()) {
            nfcUtils.disableNFCReading(this)
            log.e(TAG, "NFC reading disabled in onPause")
        }
    }

    /**
     * Handle NFC intents
     */
    private fun handleNFCIntent(intent: Intent) {
        val action = intent.action
        log.e(TAG, "handleNFCIntent called with action: $action")

        // Check if it's an NFC intent
        if (action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            action == NfcAdapter.ACTION_TECH_DISCOVERED) {

            log.e(TAG, "NFC intent detected")

            // If we have pending write data, write to the tag
            pendingNFCWrite?.let { pendingWrite ->
                log.e(TAG, "THERE IS A PENDING WRITE -> WRITE JSON TO NFC TAG")
                val tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
                if (tag != null) {
                    log.e(TAG, "THERE IS A VALID TAG DETECTED -> WRITE JSON TO NFC TAG")
                    val success = nfcUtils.writeJSONToTag(tag, pendingWrite.jsonData)
                    pendingWrite.callback(success)
                    pendingNFCWrite = null

                    if (success) {
                        Toast.makeText(this, "Payment request sent successfully!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Failed to send payment request", Toast.LENGTH_SHORT).show()
                    }
                    return
                } else {
                    Log.w(TAG, "No tag found in intent, but pending write exists")
                }
            }

            log.e(TAG, "TRYING TO READ FROM NFC TAG")
            // Otherwise, try to read from the tag and pass to current fragment
            getCurrentNFCFragment()?.let { fragment ->
                log.e(TAG, "Passing NFC intent to fragment: ${fragment.javaClass.simpleName}")
                fragment.handleIncomingNFCIntent(intent)
            } ?: run {
                Log.w(TAG, "No current NFC fragment found to handle intent")
            }
        } else {
            log.e(TAG, "Not an NFC intent, action: $action")
        }
    }

    /**
     * Get the current NFC-capable fragment
     */
    private fun getCurrentNFCFragment(): EurotokenNFCBaseFragment? {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.navHostFragment)
        val currentFragment = navHostFragment?.childFragmentManager?.fragments?.firstOrNull()
        val nfcFragment = currentFragment as? EurotokenNFCBaseFragment

        log.e(TAG, "Current fragment: ${currentFragment?.javaClass?.simpleName}, is NFC capable: ${nfcFragment != null}")

        return nfcFragment
    }

    /**
     * Setup NFC write operation (called from fragments)
     */
    override fun setupNFCWrite(jsonData: String, onResult: (Boolean) -> Unit) {
        log.e(TAG, "setupNFCWrite called with data length: ${jsonData.length}")

        if (!nfcUtils.isNFCAvailable()) {
            Log.w(TAG, "NFC is not available for write operation")
            Toast.makeText(this, "NFC is not available or disabled", Toast.LENGTH_SHORT).show()
            onResult(false)
            return
        }

        log.e(TAG, "Setting up pending NFC write operation")
        // Store the data to write when tag is detected
        pendingNFCWrite = PendingNFCWrite(jsonData, onResult)

        Toast.makeText(
            this,
            "Hold your phone against the other device to send payment request",
            Toast.LENGTH_LONG
        ).show()

        log.e(TAG, "Pending NFC write operation set up successfully")
    }

    /**
     * Data class for pending NFC write operations
     */
    private data class PendingNFCWrite(
        val jsonData: String,
        val callback: (Boolean) -> Unit
    )

    /**
     * The values for shared preferences used by this activity.
     */
    object EurotokenPreferences {
        const val EUROTOKEN_SHARED_PREF_NAME = "eurotoken"
        const val DEMO_MODE_ENABLED = "demo_mode_enabled"
    }
}
