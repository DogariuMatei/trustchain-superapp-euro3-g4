package nl.tudelft.trustchain.eurotoken.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import java.nio.charset.StandardCharsets

/**
 * HCE service for handling offline transactions with ISO-DEP protocol
 */
class HCEPaymentService : HostApduService() {

    companion object {
        private const val TAG = "HCEPaymentService"

        // AID for EuroToken payment application
        private val SELECT_AID = hexStringToByteArray("F0457572546F6B656E")

        // APDU Commands
        private const val SELECT_APDU_HEADER = "00A40400"
        private const val GET_DATA_INS = 0xCA.toByte()
        private const val PUT_DATA_INS = 0xDA.toByte()

        // Status codes
        private val STATUS_SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val STATUS_FAILED = byteArrayOf(0x6F.toByte(), 0x00.toByte())
        private val STATUS_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())

        // Singleton pattern for data exchange with activities/fragments
        private var pendingTransactionData: String? = null
        private var onDataReceivedCallback: ((String) -> Unit)? = null
        private var onDataTransmittedCallback: (() -> Unit)? = null

        fun hasPendingData(): Boolean {
            return pendingTransactionData != null
        }

        fun setOnDataTransmittedCallback(callback: () -> Unit) {
            Log.d(TAG, "Setting data transmitted callback")
            onDataTransmittedCallback = callback
        }

        fun clearOnDataTransmittedCallback() {
            Log.d(TAG, "Clearing data transmitted callback")
            onDataTransmittedCallback = null
        }

        fun setPendingTransactionData(data: String) {
            Log.d(TAG, "Setting pending transaction data: ${data.take(100)}...")
            pendingTransactionData = data
        }

        fun clearPendingTransactionData() {
            Log.d(TAG, "Clearing pending transaction data")
            pendingTransactionData = null
        }

        fun setOnDataReceivedCallback(callback: (String) -> Unit) {
            Log.d(TAG, "Setting data received callback")
            onDataReceivedCallback = callback
        }

        fun clearOnDataReceivedCallback() {
            Log.d(TAG, "Clearing data received callback")
            onDataReceivedCallback = null
        }

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

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "=== HCE SERVICE CREATED ===")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "=== HCE SERVICE DESTROYED ===")
        clearPendingTransactionData()
        clearOnDataReceivedCallback()
        clearOnDataTransmittedCallback()
    }

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        Log.d(TAG, "=== PROCESS COMMAND APDU START ===")

        if (commandApdu == null || commandApdu.size < 4) {
            Log.e(TAG, "Invalid APDU received: null or too short")
            return STATUS_FAILED
        }

        val hexApdu = byteArrayToHexString(commandApdu)
        Log.d(TAG, "Received APDU: $hexApdu")
        Log.d(TAG, "APDU length: ${commandApdu.size}")

        // Parse APDU header
        val cla = commandApdu[0]
        val ins = commandApdu[1]
        val p1 = commandApdu[2]
        val p2 = commandApdu[3]

        Log.d(TAG, "APDU Header - CLA: ${String.format("%02X", cla)}, INS: ${String.format("%02X", ins)}, P1: ${String.format("%02X", p1)}, P2: ${String.format("%02X", p2)}")

        return when {
            // Handle SELECT AID command
            isSelectAidApdu(commandApdu) -> {
                Log.d(TAG, "SELECT AID command received")
                handleSelectAid()
            }

            // Handle GET DATA command (reader wants our data)
            ins == GET_DATA_INS -> {
                Log.d(TAG, "GET DATA command received")
                handleGetData()
            }

            // Handle PUT DATA command (reader sending us data)
            ins == PUT_DATA_INS -> {
                Log.d(TAG, "PUT DATA command received")
                handlePutData(commandApdu)
            }

            else -> {
                Log.w(TAG, "Unknown APDU command: INS=${String.format("%02X", ins)}")
                STATUS_FAILED
            }
        }
    }

    private fun isSelectAidApdu(apdu: ByteArray): Boolean {
        if (apdu.size < 5) return false

        val selectHeader = byteArrayToHexString(apdu.sliceArray(0..3))
        if (!selectHeader.startsWith(SELECT_APDU_HEADER)) {
            return false
        }

        // Check if this is selecting our AID
        val lc = apdu[4].toInt() and 0xFF
        if (apdu.size < 5 + lc) return false

        val aid = apdu.sliceArray(5 until 5 + lc)
        val isOurAid = aid.contentEquals(SELECT_AID)

        Log.d(TAG, "SELECT AID check - Our AID: $isOurAid")
        return isOurAid
    }

    private fun handleSelectAid(): ByteArray {
        Log.d(TAG, "=== HANDLE SELECT AID ===")
        Log.d(TAG, "Application selected successfully")
        return STATUS_SUCCESS
    }

    private fun handleGetData(): ByteArray {
        Log.d(TAG, "=== HANDLE GET DATA ===")

        val data = pendingTransactionData
        if (data == null) {
            Log.w(TAG, "No pending transaction data available")
            return STATUS_NOT_FOUND
        }

        Log.d(TAG, "Sending transaction data: ${data.take(100)}...")
        Log.d(TAG, "Data length: ${data.length} chars")

        // Convert string to bytes and append status
        val dataBytes = data.toByteArray(StandardCharsets.UTF_8)
        val response = dataBytes + STATUS_SUCCESS

        // Notify that data was successfully transmitted
        val callback = onDataTransmittedCallback
        if (callback != null) {
            Log.d(TAG, "Invoking data transmitted callback")
            callback()
        }

        Log.d(TAG, "Response length: ${response.size} bytes")
        Log.d(TAG, "=== GET DATA COMPLETE ===")

        return response
    }

    private fun handlePutData(apdu: ByteArray): ByteArray {
        Log.d(TAG, "=== HANDLE PUT DATA ===")

        if (apdu.size < 5) {
            Log.e(TAG, "PUT DATA APDU too short")
            return STATUS_FAILED
        }

        val lc = apdu[4].toInt() and 0xFF
        Log.d(TAG, "Data length (LC): $lc")

        if (apdu.size < 5 + lc) {
            Log.e(TAG, "PUT DATA APDU length mismatch")
            return STATUS_FAILED
        }

        // Extract data
        val dataBytes = apdu.sliceArray(5 until 5 + lc)
        val data = String(dataBytes, StandardCharsets.UTF_8)

        Log.d(TAG, "Received data: ${data.take(100)}...")
        Log.d(TAG, "Data length: ${data.length} chars")

        // Notify callback if set
        val callback = onDataReceivedCallback
        if (callback != null) {
            Log.d(TAG, "Invoking data received callback")
            callback(data)
        } else {
            Log.w(TAG, "No callback set for received data")
        }

        Log.d(TAG, "=== PUT DATA COMPLETE ===")
        return STATUS_SUCCESS
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "=== HCE DEACTIVATED ===")
        Log.d(TAG, "Deactivation reason: ${getDeactivationReasonString(reason)}")
    }

    private fun getDeactivationReasonString(reason: Int): String {
        return when (reason) {
            DEACTIVATION_LINK_LOSS -> "LINK_LOSS"
            DEACTIVATION_DESELECTED -> "DESELECTED"
            else -> "UNKNOWN($reason)"
        }
    }
}
