package nl.tudelft.trustchain.common.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.nio.charset.Charset

/**
 *  class for handling NFC operations
 */
class NFCUtils(private val context: Context) {
    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(context)

    companion object {
        private const val TAG = "NFCUtils"
        private const val MIME_TYPE_JSON = "application/json"
    }

    fun isNFCAvailable(): Boolean {
        Log.d(TAG, "Checking NFC availability...")
        val adapter = nfcAdapter

        if (adapter == null) {
            Log.w(TAG, "NFC adapter is null - device does not support NFC")
            return false
        }

        val isEnabled = adapter.isEnabled
        Log.d(TAG, "NFC adapter exists: true, NFC enabled: $isEnabled")

        return isEnabled
//        return true
    }

    /**
     * Enable NFC reading mode
     */
    fun enableNFCReading(activity: Activity) {
        Log.d(TAG, "Attempting to enable NFC reading...")

        nfcAdapter?.let { adapter ->
            if (!adapter.isEnabled) {
                Log.w(TAG, "NFC is not enabled on device")
                return
            }

            val intent = Intent(activity, activity.javaClass).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

            val pendingIntent = android.app.PendingIntent.getActivity(
                activity, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            // Enable foreground dispatch for all NFC types
            adapter.enableForegroundDispatch(activity, pendingIntent, null, null)
            Log.d(TAG, "NFC foreground dispatch enabled successfully")
        } ?: run {
            Log.e(TAG, "Cannot enable NFC reading - adapter is null")
        }
    }

    /**
     * Disable NFC reading mode
     */
    fun disableNFCReading(activity: Activity) {
        Log.d(TAG, "Disabling NFC reading...")
        nfcAdapter?.let { adapter ->
            try {
                adapter.disableForegroundDispatch(activity)
                Log.d(TAG, "NFC foreground dispatch disabled successfully")
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not disable foreground dispatch: ${e.message}")
            }
        }
    }

    fun writeJSONToTag(tag: Tag, jsonData: String): Boolean {
        Log.d(TAG, "Attempting to write JSON to NFC tag: ${jsonData.take(100)}...")

        return try {
            val ndef = Ndef.get(tag) ?: run {
                Log.e(TAG, "Tag does not support NDEF")
                return false
            }

            ndef.connect()
            Log.d(TAG, "Connected to NFC tag")

            if (!ndef.isWritable) {
                Log.e(TAG, "Tag is not writable")
                ndef.close()
                return false
            }

            val ndefMessage = createNDEFMessageFromJSON(jsonData)
            val messageSize = ndefMessage.toByteArray().size
            val maxSize = ndef.maxSize

            Log.d(TAG, "Message size: $messageSize, Tag max size: $maxSize")

            if (messageSize > maxSize) {
                Log.e(TAG, "Message too large for tag ($messageSize > $maxSize)")
                ndef.close()
                return false
            }

            ndef.writeNdefMessage(ndefMessage)
            ndef.close()

            Log.d(TAG, "Successfully wrote JSON to NFC tag")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to NFC tag", e)
            false
        }
    }

    /**
     * Process NFC intent and extract JSON data
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun processIncomingNFCIntent(intent: Intent): String? {
        Log.d(TAG, "Processing incoming NFC intent: ${intent.action}")

        val action = intent.action
        if (action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            action == NfcAdapter.ACTION_TECH_DISCOVERED) {

            val tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
            return tag?.let {
                Log.d(TAG, "Tag found, attempting to read JSON")
                readJSONFromTag(it)
            } ?: run {
                Log.w(TAG, "No tag found in intent")
                null
            }
        }

        Log.w(TAG, "Intent action not recognized: $action")
        return null
    }

    /**
     * Read JSON data from NFC tag
     */
    fun readJSONFromTag(tag: Tag): String? {
        Log.d(TAG, "Attempting to read JSON from NFC tag")

        return try {
            val ndef = Ndef.get(tag) ?: run {
                Log.e(TAG, "Tag does not support NDEF")
                return null
            }

            ndef.connect()
            val ndefMessage = ndef.ndefMessage
            ndef.close()

            if (ndefMessage != null && ndefMessage.records.isNotEmpty()) {
                Log.d(TAG, "Found ${ndefMessage.records.size} NDEF records")

                val record = ndefMessage.records[0]

                // Check if it's our JSON MIME type
                if (record.tnf == NdefRecord.TNF_MIME_MEDIA) {
                    val mimeType = String(record.type, Charset.forName("UTF-8"))
                    Log.d(TAG, "MIME type: $mimeType")

                    if (mimeType == MIME_TYPE_JSON) {
                        val payload = String(record.payload, Charset.forName("UTF-8"))
                        Log.d(TAG, "Successfully read JSON from NFC tag: ${payload.take(100)}...")
                        return payload
                    } else {
                        Log.w(TAG, "MIME type mismatch: expected $MIME_TYPE_JSON, got $mimeType")
                    }
                } else {
                    Log.w(TAG, "Record TNF is not MIME_MEDIA: ${record.tnf}")
                }
            } else {
                Log.w(TAG, "No NDEF message or records found on tag")
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Error reading from NFC tag", e)
            null
        }
    }

    /**
     * Create NDEF message from JSON string
     */
    fun createNDEFMessageFromJSON(jsonData: String): NdefMessage {
        val mimeRecord = NdefRecord.createMime(
            MIME_TYPE_JSON,
            jsonData.toByteArray(Charset.forName("UTF-8"))
        )
        return NdefMessage(mimeRecord)
    }
}
