package nl.tudelft.trustchain.common.util

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Utility class for handling HCE-based NFC operations
 */
class HCENFCUtils(private val context: Context) {

    companion object {
        private const val TAG = "HCENFCUtils"

        // EuroToken AID (Application Identifier)
        private val SELECT_AID_HEADER = byteArrayOf(
            0x00.toByte(), // CLA
            0xA4.toByte(), // INS (SELECT)
            0x04.toByte(), // P1 (Select by AID)
            0x00.toByte()  // P2
        )

        // Our custom AID: F0457572546F6B656E (hex for "EurToken" with F0 prefix)
        private val AID = hexStringToByteArray("F0457572546F6B656E")

        // APDU Commands
        private const val GET_DATA_INS = 0xCA.toByte()
        private const val PUT_DATA_INS = 0xDA.toByte()

        // Max APDU data size (conservative estimate)
        private const val MAX_APDU_DATA_SIZE = 250

        // Reader mode flags
        const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

        private fun hexStringToByteArray(s: String): ByteArray {
            val len = s.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
                i += 2
            }
            return data
        }

        private fun byteArrayToHexString(bytes: ByteArray): String {
            val hexArray = "0123456789ABCDEF".toCharArray()
            val hexChars = CharArray(bytes.size * 2)
            for (j in bytes.indices) {
                val v = bytes[j].toInt() and 0xFF
                hexChars[j * 2] = hexArray[v ushr 4]
                hexChars[j * 2 + 1] = hexArray[v and 0x0F]
            }
            return String(hexChars)
        }
    }

    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(context)

    /**
     * Check if NFC is available and enabled
     */
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
    }

    /**
     * Enable reader mode for HCE communication
     */
    fun enableReaderMode(
        activity: Activity,
        onTagDiscovered: (Tag) -> Unit,
        onError: (String) -> Unit
    ) {
        Log.d(TAG, "=== ENABLING READER MODE ===")

        nfcAdapter?.let { adapter ->
            if (!adapter.isEnabled) {
                Log.w(TAG, "NFC is not enabled on device")
                onError("NFC is not enabled")
                return
            }

            try {
                adapter.enableReaderMode(
                    activity,
                    { tag ->
                        Log.d(TAG, "Tag discovered in reader mode")
                        Log.d(TAG, "Tag ID: ${tag.id?.let { byteArrayToHexString(it) }}")
                        Log.d(TAG, "Tech list: ${tag.techList?.joinToString()}")
                        onTagDiscovered(tag)
                    },
                    READER_FLAGS,
                    Bundle()
                )
                Log.d(TAG, "Reader mode enabled successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable reader mode: ${e.message}", e)
                onError("Failed to enable reader mode: ${e.message}")
            }
        } ?: run {
            Log.e(TAG, "Cannot enable reader mode - adapter is null")
            onError("NFC adapter not available")
        }
    }

    /**
     * Disable reader mode
     */
    fun disableReaderMode(activity: Activity) {
        Log.d(TAG, "=== DISABLING READER MODE ===")

        nfcAdapter?.let { adapter ->
            try {
                adapter.disableReaderMode(activity)
                Log.d(TAG, "Reader mode disabled successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Could not disable reader mode: ${e.message}")
            }
        }
    }

    /**
     * Send data to HCE service
     */
    fun sendDataToHCE(
        tag: Tag,
        jsonData: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        Log.d(TAG, "=== SEND DATA TO HCE START ===")
        Log.d(TAG, "Attempting to send JSON data to HCE")
        Log.d(TAG, "JSON data length: ${jsonData.length}")
        Log.d(TAG, "JSON data preview: ${jsonData.take(200)}...")

        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            Log.e(TAG, "IsoDep not supported by tag")
            onError("IsoDep not supported")
            return
        }

        try {
            Log.d(TAG, "Connecting to IsoDep tag...")
            isoDep.connect()
            Log.d(TAG, "Connected successfully")
            Log.d(TAG, "Max transceive length: ${isoDep.maxTransceiveLength}")

            // Step 1: Select application by AID
            Log.d(TAG, "Step 1: Selecting application by AID")
            val selectCommand = createSelectAidApdu()
            Log.d(TAG, "SELECT AID APDU: ${byteArrayToHexString(selectCommand)}")

            val selectResponse = isoDep.transceive(selectCommand)
            Log.d(TAG, "SELECT response: ${byteArrayToHexString(selectResponse)}")

            if (!isSuccessResponse(selectResponse)) {
                Log.e(TAG, "Failed to select application")
                onError("Failed to select application")
                return
            }

            // Step 2: Send data using PUT DATA command
            Log.d(TAG, "Step 2: Sending data using PUT DATA")
            val dataBytes = jsonData.toByteArray(StandardCharsets.UTF_8)

            // Check if data needs to be chunked
            if (dataBytes.size > MAX_APDU_DATA_SIZE) {
                Log.d(TAG, "Data too large (${dataBytes.size} bytes), sending in chunks")
                // For now, we'll truncate. In production, implement proper chunking
                Log.w(TAG, "WARNING: Data truncated to $MAX_APDU_DATA_SIZE bytes")
            }

            val putDataCommand = createPutDataApdu(dataBytes)
            Log.d(TAG, "PUT DATA APDU length: ${putDataCommand.size}")

            val putResponse = isoDep.transceive(putDataCommand)
            Log.d(TAG, "PUT DATA response: ${byteArrayToHexString(putResponse)}")

            if (!isSuccessResponse(putResponse)) {
                Log.e(TAG, "Failed to send data")
                onError("Failed to send data")
                return
            }

            Log.d(TAG, "Data sent successfully!")
            Log.d(TAG, "=== SEND DATA TO HCE SUCCESS ===")
            onSuccess()

        } catch (e: IOException) {
            Log.e(TAG, "IOException during HCE communication: ${e.message}", e)
            onError("Communication error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during HCE communication: ${e.message}", e)
            onError("Unexpected error: ${e.message}")
        } finally {
            try {
                isoDep.close()
                Log.d(TAG, "IsoDep connection closed")
            } catch (e: IOException) {
                Log.w(TAG, "Error closing IsoDep: ${e.message}")
            }
        }
    }

    /**
     * Receive data from HCE service
     */
    fun receiveDataFromHCE(
        tag: Tag,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        Log.d(TAG, "=== RECEIVE DATA FROM HCE START ===")
        Log.d(TAG, "Attempting to receive data from HCE")

        val isoDep = IsoDep.get(tag)
        if (isoDep == null) {
            Log.e(TAG, "IsoDep not supported by tag")
            onError("IsoDep not supported")
            return
        }

        try {
            Log.d(TAG, "Connecting to IsoDep tag...")
            isoDep.connect()
            Log.d(TAG, "Connected successfully")

            // Step 1: Select application by AID
            Log.d(TAG, "Step 1: Selecting application by AID")
            val selectCommand = createSelectAidApdu()
            val selectResponse = isoDep.transceive(selectCommand)
            Log.d(TAG, "SELECT response: ${byteArrayToHexString(selectResponse)}")

            if (!isSuccessResponse(selectResponse)) {
                Log.e(TAG, "Failed to select application")
                onError("Failed to select application")
                return
            }

            // Step 2: Get data using GET DATA command
            Log.d(TAG, "Step 2: Getting data using GET DATA")
            val getDataCommand = createGetDataApdu()
            Log.d(TAG, "GET DATA APDU: ${byteArrayToHexString(getDataCommand)}")

            val getResponse = isoDep.transceive(getDataCommand)
            Log.d(TAG, "GET DATA response length: ${getResponse.size} bytes")

            // Check response status (last 2 bytes)
            if (getResponse.size < 2) {
                Log.e(TAG, "Invalid response length")
                onError("Invalid response")
                return
            }

            val sw1 = getResponse[getResponse.size - 2]
            val sw2 = getResponse[getResponse.size - 1]
            Log.d(TAG, "Response status: ${String.format("%02X %02X", sw1, sw2)}")

            if (sw1 == 0x90.toByte() && sw2 == 0x00.toByte()) {
                // Success - extract data (excluding status bytes)
                val dataBytes = getResponse.sliceArray(0 until getResponse.size - 2)
                val jsonData = String(dataBytes, StandardCharsets.UTF_8)

                Log.d(TAG, "Received data successfully")
                Log.d(TAG, "Data length: ${jsonData.length} chars")
                Log.d(TAG, "Data preview: ${jsonData.take(200)}...")
                Log.d(TAG, "=== RECEIVE DATA FROM HCE SUCCESS ===")

                onSuccess(jsonData)
            } else {
                Log.e(TAG, "Error response from HCE")
                onError("Error response: ${String.format("%02X %02X", sw1, sw2)}")
            }

        } catch (e: IOException) {
            Log.e(TAG, "IOException during HCE communication: ${e.message}", e)
            onError("Communication error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during HCE communication: ${e.message}", e)
            onError("Unexpected error: ${e.message}")
        } finally {
            try {
                isoDep.close()
                Log.d(TAG, "IsoDep connection closed")
            } catch (e: IOException) {
                Log.w(TAG, "Error closing IsoDep: ${e.message}")
            }
        }
    }

    /**
     * Create SELECT AID APDU command
     */
    private fun createSelectAidApdu(): ByteArray {
        return SELECT_AID_HEADER + byteArrayOf(AID.size.toByte()) + AID + byteArrayOf(0x00)
    }

    /**
     * Create GET DATA APDU command
     */
    private fun createGetDataApdu(): ByteArray {
        return byteArrayOf(
            0x00.toByte(), // CLA
            GET_DATA_INS,   // INS
            0x00.toByte(), // P1
            0x00.toByte(), // P2
            0x00.toByte()  // Le (expected length - 0 means up to 256 bytes)
        )
    }

    /**
     * Create PUT DATA APDU command
     */
    private fun createPutDataApdu(data: ByteArray): ByteArray {
        val truncatedData = if (data.size > MAX_APDU_DATA_SIZE) {
            data.sliceArray(0 until MAX_APDU_DATA_SIZE)
        } else {
            data
        }

        return byteArrayOf(
            0x00.toByte(), // CLA
            PUT_DATA_INS,   // INS
            0x00.toByte(), // P1
            0x00.toByte(), // P2
            truncatedData.size.toByte() // Lc (data length)
        ) + truncatedData
    }

    /**
     * Check if response indicates success (SW1=90, SW2=00)
     */
    private fun isSuccessResponse(response: ByteArray): Boolean {
        return response.size >= 2 &&
            response[response.size - 2] == 0x90.toByte() &&
            response[response.size - 1] == 0x00.toByte()
    }
}
