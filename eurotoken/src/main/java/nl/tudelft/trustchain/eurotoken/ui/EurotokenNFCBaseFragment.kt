package nl.tudelft.trustchain.eurotoken.ui

import android.content.Intent
import android.widget.Toast
import androidx.annotation.LayoutRes
import nl.tudelft.trustchain.common.util.NFCUtils
import nl.tudelft.trustchain.eurotoken.nfc.NFCBridgeSimulation
import nl.tudelft.ipv8.util.toHex
import android.app.AlertDialog
import android.content.Context
import android.util.Log
import android.widget.ArrayAdapter
import nl.tudelft.trustchain.eurotoken.EuroTokenMainActivity
import nl.tudelft.ipv8.IPv8
import nl.tudelft.trustchain.common.contacts.ContactStore
import nl.tudelft.trustchain.common.ui.BaseFragment
import nl.tudelft.trustchain.eurotoken.community.EuroTokenCommunity
import org.json.JSONObject

/**
 * Base fragment that handles NFC operations for EuroToken transfers
 * Enhanced with better payment request processing and status tracking
 */
abstract class EurotokenNFCBaseFragment(@LayoutRes contentLayoutId: Int = 0) : EurotokenBaseFragment(contentLayoutId) {

    protected val nfcUtils by lazy { NFCUtils(requireContext()) }

    // Add this property to the class
    protected val nfcBridgeSimulation by lazy {
        NFCBridgeSimulation(
            requireContext()
        ) { getIpv8().getOverlay() }
    }

    // Track whether a payment request is pending to avoid duplicates
    private var isPaymentRequestPending = false

    override fun onResume() {
        super.onResume()

        // Check if we're in demo mode
        val pref = requireContext().getSharedPreferences(
            EuroTokenMainActivity.EurotokenPreferences.EUROTOKEN_SHARED_PREF_NAME,
            Context.MODE_PRIVATE
        )
        val demoModeEnabled = pref.getBoolean(
            EuroTokenMainActivity.EurotokenPreferences.DEMO_MODE_ENABLED,
            false
        )

        // Only check for real NFC if not in demo mode
        if (!demoModeEnabled) {
            if (nfcUtils.isNFCAvailable()) {
                nfcUtils.enableNFCReading(requireActivity())
            } else {
                Toast.makeText(requireContext(), "NFC is not available or disabled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()

        // Check if we're in demo mode
        val pref = requireContext().getSharedPreferences(
            EuroTokenMainActivity.EurotokenPreferences.EUROTOKEN_SHARED_PREF_NAME,
            Context.MODE_PRIVATE
        )
        val demoModeEnabled = pref.getBoolean(
            EuroTokenMainActivity.EurotokenPreferences.DEMO_MODE_ENABLED,
            false
        )

        // Only disable real NFC if not in demo mode
        if (!demoModeEnabled && nfcUtils.isNFCAvailable()) {
            nfcUtils.disableNFCReading(requireActivity())
        }

        // Reset payment request state when fragment is paused
        isPaymentRequestPending = false
    }

    /**
     * Public method for receiving simulated NFC data (from the activity)
     */
    fun handleSimulatedNFCData(jsonData: String) {
        try {
            val json = JSONObject(jsonData)

            // Check if this is a payment declined notification
            if (json.optString("type") == "payment_declined") {
                onPaymentDeclined(json.optString("message", "Payment was declined"))
                return
            }

            // Regular payment request or other NFC data
            onNFCDataReceived(jsonData)

        } catch (e: Exception) {
            Log.e("EurotokenNFCBaseFragment", "Error processing NFC data: ${e.message}")
            onNFCReadError("Invalid data format: ${e.message}")
        }
    }

    /**
     * Called when a payment request is declined
     */
    protected open fun onPaymentDeclined(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        isPaymentRequestPending = false
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
        val pref = requireContext().getSharedPreferences(
            EuroTokenMainActivity.EurotokenPreferences.EUROTOKEN_SHARED_PREF_NAME,
            Context.MODE_PRIVATE
        )
        val demoModeEnabled = pref.getBoolean(
            EuroTokenMainActivity.EurotokenPreferences.DEMO_MODE_ENABLED,
            false
        )

        if (demoModeEnabled) {
            // In demo mode, show peer selection dialog
            if (!isPaymentRequestPending) {
                isPaymentRequestPending = true
                showPeerSelectionDialog(jsonData, onResult)
            } else {
                Toast.makeText(requireContext(), "A payment request is already pending", Toast.LENGTH_SHORT).show()
                onResult(false)
            }
        } else {
            // Use real NFC in normal mode
            setupNFCWrite(jsonData, onResult)
        }
    }

    /**
     * Send a payment declined notification to a peer
     */
    protected fun sendPaymentDeclined(peerPublicKey: String, onResult: (Boolean) -> Unit) {
        val pref = requireContext().getSharedPreferences(
            EuroTokenMainActivity.EurotokenPreferences.EUROTOKEN_SHARED_PREF_NAME,
            Context.MODE_PRIVATE
        )
        val demoModeEnabled = pref.getBoolean(
            EuroTokenMainActivity.EurotokenPreferences.DEMO_MODE_ENABLED,
            false
        )

        if (demoModeEnabled) {
            nfcBridgeSimulation.sendPaymentDeclined(peerPublicKey, onResult)
        } else {
            // In real NFC mode, there's no way to actively notify
            // We just consider it declined locally
            onResult(true)
        }
    }

    /**
     * Setup for NFC writing - stores data until tag is detected
     */
    private fun setupNFCWrite(jsonData: String, onResult: (Boolean) -> Unit) {
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
     * Shows dialog to select a peer for simulated NFC transfer
     */
    private fun showPeerSelectionDialog(jsonData: String, onResult: (Boolean) -> Unit) {
        val community = getIpv8().getOverlay<EuroTokenCommunity>()
        if (community == null) {
            Log.e("NFCDemo", "Community not available - cannot find peers")
            Toast.makeText(requireContext(), "Network community not available", Toast.LENGTH_SHORT).show()
            isPaymentRequestPending = false
            onResult(false)
            return
        }

        // Get all peers first
        val allPeers = community.getPeers()
        Log.d("NFCDemo", "Total peers found: ${allPeers.size}")

        // Get my own public key to filter it out
        val myPeer = getIpv8().myPeer
        val myPublicKey = myPeer.publicKey.keyToBin()
        val myPublicKeyHex = myPublicKey.toHex()
        Log.d("NFCDemo", "My public key: $myPublicKeyHex")

        // Create a map to deduplicate peers by their public key (hex string)
        // This ensures we don't show the same peer multiple times with different addresses
        val uniquePeersMap = mutableMapOf<String, nl.tudelft.ipv8.Peer>()

        // Filter and deduplicate peers
        allPeers.forEach { peer ->
            val peerKeyHex = peer.publicKey.keyToBin().toHex()

            // Skip if this is our own peer
            if (peerKeyHex != myPublicKeyHex) {
                uniquePeersMap[peerKeyHex] = peer
                Log.d("NFCDemo", "Adding unique peer: $peerKeyHex - ${peer.address}")
            } else {
                Log.d("NFCDemo", "Skipping own peer: $peerKeyHex - ${peer.address}")
            }
        }

        // Convert to list for the dialog
        val uniquePeers = uniquePeersMap.values.toList()

        Log.d("NFCDemo", "Filtered unique peers (excluding myself): ${uniquePeers.size}")

        // Check if we have any peers after filtering
        if (uniquePeers.isNotEmpty()) {
            // Create display items for the dialog with friendly names
            val peerDisplayNames = uniquePeers.map { peer ->
                try {
                    val peerKey = peer.publicKey
                    val contact = ContactStore.getInstance(requireContext()).getContactFromPublicKey(peerKey)
                    if (contact?.name != null) {
                        "${contact.name} (${peer.publicKey.keyToHash().toHex().take(8)}...)"
                    } else {
                        "${peer.address} (${peer.publicKey.keyToHash().toHex().take(8)}...)"
                    }
                } catch (e: Exception) {
                    "${peer.address} (${peer.publicKey.keyToHash().toHex().take(8)}...)"
                }
            }

            // Now explicitly specify the type for the adapter
            val adapter = ArrayAdapter<String>(
                requireContext(),
                android.R.layout.simple_list_item_1,
                peerDisplayNames
            )

            val builder = AlertDialog.Builder(requireContext())
                .setTitle("Select who to request payment from")
                .setAdapter(adapter) { _, which ->
                    val selectedPeer = uniquePeers[which]
                    val selectedPeerKeyHex = selectedPeer.publicKey.keyToBin().toHex()

                    Log.d("NFCDemo", "Selected peer: $selectedPeerKeyHex - ${selectedPeer.address}")

                    nfcBridgeSimulation.simulateNFCPaymentRequest(
                        jsonData,
                        selectedPeerKeyHex,
                        onResult
                    )
                }
                .setNegativeButton("Cancel") { _, _ ->
                    isPaymentRequestPending = false
                    onResult(false)
                }
                .setOnCancelListener {
                    isPaymentRequestPending = false
                    onResult(false)
                }

            builder.show()
        } else {
            Log.d("NFCDemo", "No Peers Available Near You")
            Toast.makeText(requireContext(), "No Peers Available Near You", Toast.LENGTH_LONG).show()
            isPaymentRequestPending = false
            onResult(false)
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
