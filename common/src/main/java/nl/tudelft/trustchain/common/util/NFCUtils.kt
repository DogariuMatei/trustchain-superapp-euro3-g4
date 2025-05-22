package nl.tudelft.trustchain.common.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.util.Log
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
        return nfcAdapter != null && nfcAdapter.isEnabled
    }

    /**
     * Enable NFC reading mode
     */
    fun enableNFCReading(activity: Activity) {
        nfcAdapter?.let { adapter ->
            val intent = Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pendingIntent = android.app.PendingIntent.getActivity(
                activity, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            adapter.enableForegroundDispatch(activity, pendingIntent, null, null)
            Log.d(TAG, "NFC reading enabled")
        }
    }

    /**
     * Disable NFC reading mode
     */
    fun disableNFCReading(activity: Activity) {
        nfcAdapter?.disableForegroundDispatch(activity)
        Log.d(TAG, "NFC reading disabled")
    }

    fun writeJSONToTag(tag: Tag, jsonData: String): Boolean {
        return try {
            val ndef = Ndef.get(tag) ?: run {
                Log.e(TAG, "Tag does not support NDEF")
                return false
            }

            ndef.connect()

            if (!ndef.isWritable) {
                Log.e(TAG, "Tag is not writable")
                ndef.close()
                return false
            }

            val ndefMessage = createNDEFMessageFromJSON(jsonData)
            if (ndefMessage.toByteArray().size > ndef.maxSize) {
                Log.e(TAG, "Message too large for tag")
                ndef.close()
                return false
            }

            ndef.writeNdefMessage(ndefMessage)
            ndef.close()

            Log.d(TAG, "Successfully wrote to NFC tag")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to NFC tag", e)
            false
        }
    }

    /**
     * Process NFC intent and extract JSON data
     */
    fun processIncomingNFCIntent(intent: Intent): String? {
        val action = intent.action
        if (action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            action == NfcAdapter.ACTION_TECH_DISCOVERED) {

            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            return tag?.let { readJSONFromTag(it) }
        }
        return null
    }

    /**
     * Read JSON data from NFC tag
     */
    fun readJSONFromTag(tag: Tag): String? {
        return try {
            val ndef = Ndef.get(tag) ?: run {
                Log.e(TAG, "Tag does not support NDEF")
                return null
            }

            ndef.connect()
            val ndefMessage = ndef.ndefMessage
            ndef.close()

            if (ndefMessage != null && ndefMessage.records.isNotEmpty()) {
                val record = ndefMessage.records[0]

                // Check if it's our JSON MIME type
                if (record.tnf == NdefRecord.TNF_MIME_MEDIA) {
                    val mimeType = String(record.type, Charset.forName("UTF-8"))
                    if (mimeType == MIME_TYPE_JSON) {
                        val payload = String(record.payload, Charset.forName("UTF-8"))
                        Log.d(TAG, "Successfully read from NFC tag: $payload")
                        return payload
                    }
                }
            }

            Log.w(TAG, "No valid JSON data found on tag")
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
