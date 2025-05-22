package nl.tudelft.trustchain.eurotoken

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.Toast
import nl.tudelft.trustchain.common.BaseActivity
import nl.tudelft.trustchain.eurotoken.ui.EurotokenNFCBaseFragment
import nl.tudelft.trustchain.common.util.NFCUtils

class EuroTokenMainActivity : BaseActivity(), EurotokenNFCBaseFragment.NFCWriteCapable {
    override val navigationGraph = R.navigation.nav_graph_eurotoken
    override val bottomNavigationMenu = R.menu.eurotoken_navigation_menu

    private val nfcUtils by lazy { NFCUtils(this) }
    private var pendingNFCWrite: PendingNFCWrite? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Handle NFC intent if app was launched via NFC
        handleNFCIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            handleNFCIntent(intent)
        }
    }

    /**
     * Handle NFC intents
     */
    private fun handleNFCIntent(intent: Intent) {
        // Check if it's an NFC intent
        if (intent.action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_TECH_DISCOVERED) {

            // If we have pending write data, write to the tag
            pendingNFCWrite?.let { pendingWrite ->
                val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
                if (tag != null) {
                    val success = nfcUtils.writeJSONToTag(tag, pendingWrite.jsonData)
                    pendingWrite.callback(success)
                    pendingNFCWrite = null

                    if (success) {
                        Toast.makeText(this, "Payment request sent successfully!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Failed to send payment request", Toast.LENGTH_SHORT).show()
                    }
                    return
                }
            }

            // Otherwise, try to read from the tag and pass to current fragment
            getCurrentNFCFragment()?.handleIncomingNFCIntent(intent)
        }
    }

    /**
     * Get the current NFC-capable fragment
     */
    private fun getCurrentNFCFragment(): EurotokenNFCBaseFragment? {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.navHostFragment)
        val currentFragment = navHostFragment?.childFragmentManager?.fragments?.firstOrNull()
        return currentFragment as? EurotokenNFCBaseFragment
    }

    /**
     * Setup NFC write operation (called from fragments)
     */
    override fun setupNFCWrite(jsonData: String, onResult: (Boolean) -> Unit) {
        if (!nfcUtils.isNFCAvailable()) {
            Toast.makeText(this, "NFC is not available or disabled", Toast.LENGTH_SHORT).show()
            onResult(false)
            return
        }

        // Store the data to write when tag is detected
        pendingNFCWrite = PendingNFCWrite(jsonData, onResult)

        Toast.makeText(
            this,
            "Hold your phone against the other device to send payment request",
            Toast.LENGTH_LONG
        ).show()
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
