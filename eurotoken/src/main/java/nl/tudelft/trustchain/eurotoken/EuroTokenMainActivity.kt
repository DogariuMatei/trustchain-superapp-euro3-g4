package nl.tudelft.trustchain.eurotoken

import android.app.AlertDialog
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.navigation.findNavController
import nl.tudelft.trustchain.common.BaseActivity
import nl.tudelft.trustchain.eurotoken.ui.EurotokenNFCBaseFragment
import nl.tudelft.trustchain.common.util.NFCUtils
import nl.tudelft.trustchain.eurotoken.ui.transfer.SendMoneyFragment
import org.json.JSONObject

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

    fun handleSimulatedNFCData(jsonData: String) {
        Log.d("EuroTokenMainActivity", "Received simulated NFC data: $jsonData")

        try {
            // Check if this is valid JSON
            val json = JSONObject(jsonData)
            Log.d("EuroTokenMainActivity", "JSON contains keys: ${json.keys().asSequence().toList()}")

            // Check if this is a payment request
            if (json.has("amount") && json.optString("type") == "transfer") {
                // This is a payment request - show a dialog and then navigate to SendMoneyFragment
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("Payment Request Received")
                        .setMessage("You've received a payment request for ${json.optLong("amount")} cents")
                        .setPositiveButton("Review Request") { _, _ ->
                            // Navigate directly to SendMoneyFragment
                            val navController = findNavController(R.id.navHostFragment)

                            val bundle = Bundle().apply {
                                putString(SendMoneyFragment.ARG_PUBLIC_KEY, json.optString("public_key"))
                                putLong(SendMoneyFragment.ARG_AMOUNT, json.optLong("amount"))
                                putString(SendMoneyFragment.ARG_NAME, json.optString("name", ""))
                            }

                            navController.navigate(R.id.sendMoneyFragment, bundle)
                        }
                        .setCancelable(false)
                        .show()
                }
                return
            }

            // For other types of data, use the original flow
            runOnUiThread {
                // Rest of your original code...
            }
        } catch (e: Exception) {
            Log.e("EuroTokenMainActivity", "Error parsing JSON data: ${e.message}", e)
            Toast.makeText(this, "Received invalid payment request data", Toast.LENGTH_LONG).show()
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
            getCurrentNFCFragment()?.handleNFCIntent(intent)
        }
    }

    /**
     * Get the current NFC-capable fragment
     */
    private fun getCurrentNFCFragment(): EurotokenNFCBaseFragment? {
        try {
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.navHostFragment)
            val currentFragment = navHostFragment?.childFragmentManager?.fragments?.firstOrNull()

            if (currentFragment is EurotokenNFCBaseFragment) {
                return currentFragment
            } else {
                Log.e("EuroTokenMainActivity", "Current fragment is not NFC capable: ${currentFragment?.javaClass?.simpleName ?: "null"}")
            }

            return null
        } catch (e: Exception) {
            Log.e("EuroTokenMainActivity", "Error getting current fragment: ${e.message}", e)
            return null
        }
    }

    /**
     * Setup NFC write operation (called from fragments)
     */
    override fun setupNFCWrite(jsonData: String, onResult: (Boolean) -> Unit) {
        Log.d("EuroTokenMainActivity", "Setting up NFC write with data: $jsonData")

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
