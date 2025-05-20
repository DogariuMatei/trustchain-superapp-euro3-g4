package nl.tudelft.trustchain.eurotoken.nfc

import android.content.Context
import android.util.Log
import android.widget.Toast
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.trustchain.eurotoken.community.EuroTokenCommunity

/**
 * Bridge class that simulates NFC operations using network communication for demo mode.
 * Enhanced with better status reporting and transaction decline functionality.
 */
class NFCBridgeSimulation(
    private val context: Context,
    private val getCommunity: () -> EuroTokenCommunity?
) {
    private val TAG = "NFCBridgeSimulation"

    /**
     * Simulates writing NFC data to another device by sending it over the network
     */
    fun simulateNFCPaymentRequest(jsonData: String, peerPublicKey: String, callback: (Boolean) -> Unit) {
        Log.d(TAG, "Simulating NFC payment request transmission with data: $jsonData")

        val euroTokenCommunity = getCommunity()
        if (euroTokenCommunity == null) {
            Log.e(TAG, "EuroTokenCommunity not found")
            callback(false)
            return
        }

        val success = euroTokenCommunity.sendSimulatedNFCData(jsonData, peerPublicKey)
        callback(success)

        if (success) {
            Toast.makeText(context, "Payment request sent successfully!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Failed to send payment request", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Sends a payment declined notification to a peer
     * This can be used to notify requesters when their payment request is denied
     */
    fun sendPaymentDeclined(peerPublicKey: String, callback: (Boolean) -> Unit) {
        Log.d(TAG, "Sending payment declined notification to: $peerPublicKey")

        val euroTokenCommunity = getCommunity()
        if (euroTokenCommunity == null) {
            Log.e(TAG, "EuroTokenCommunity not found")
            callback(false)
            return
        }

        // Create a JSON object with the decline notification
        val jsonData = """{"type":"payment_declined","message":"Payment request was declined"}"""

        val success = euroTokenCommunity.sendSimulatedNFCData(jsonData, peerPublicKey)
        callback(success)

        if (success) {
            Toast.makeText(context, "Decline notification sent", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Failed to send decline notification", Toast.LENGTH_SHORT).show()
        }
    }
}
