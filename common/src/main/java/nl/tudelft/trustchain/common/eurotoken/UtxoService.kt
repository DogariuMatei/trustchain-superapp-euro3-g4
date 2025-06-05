package nl.tudelft.trustchain.common.eurotoken

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nl.tudelft.ipv8.android.IPv8Android
import nl.tudelft.ipv8.attestation.trustchain.TrustChainCommunity
import nl.tudelft.ipv8.util.*
import nl.tudelft.trustchain.common.bloomFilter.BloomFilter
import java.lang.Math.abs
import java.security.MessageDigest

class UTXOService(
     val trustChainCommunity: TrustChainCommunity,
     val store: UTXOStore,
     expectedUTXOs: Int = 2_000,
     falsePositiveRate: Float = 0.01f
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    // TODO store the bloom filter in the database
    private var bloom = BloomFilter(expectedUTXOs, falsePositiveRate)
    val bloomFilter: BloomFilter
        get() = bloom

    /*
    * Add a new UTXO to local state and update commitments
    */
    fun addUTXO(utxo: UTXO) {
        store.addUtxo(utxo)
    }
    /*
    * Spend (remove) an existing UTXO
    */
    fun removeUTXO(utxo: UTXO) {
        store.removeUtxo(utxo.txId, utxo.txIndex)

        // Add spent UTXO to bloom filter
        bloom.add(utxo.getUTXOIdString().toByteArray())
    }

    fun getUTXO(txId: String, txIndex: Int): UTXO? {
        return store.getUtxo(txId, txIndex)
    }

    fun getUtxosByOwner(owner: ByteArray): List<UTXO> {
        Log.e("UTXOService", "Got UTXOs for owner: ${owner.toHex()}")
        return store.getUtxosByOwner(owner)
    }

    fun getUtxoTransactionsByParticipation(myPublicKey: ByteArray): List<UTXOTransaction> {
        Log.e("UTXOService", "Got UTXO Transactions for participant: ${myPublicKey.toHex()}")
        return store.getUtxoTransactionsByParticipation(myPublicKey)
    }

    fun getUtxosById(txId: String): List<UTXO> {
        Log.e("UTXOService", "Got UTXOs for txId: $txId")
        return store.getUtxosById(txId)
    }

    fun getMyBalance(): Long {
        val myPublicKey = trustChainCommunity.myPeer.publicKey.keyToBin()
        val available_utxos: List<UTXO> = store.getUtxosByOwner(myPublicKey)

        var balance = 0L
        for (utxo in available_utxos) {
            balance += utxo.amount
        }

        return balance
    }

    fun buildUtxoTransaction(
        recipient: ByteArray,
        amount: Long
    ): Boolean {
        Log.d("UTXOService", "sending amount: $amount")
        if (getMyBalance() - amount < 0) {
            return false
        }
        scope.launch {
            buildUtxoTransactionSync(recipient, amount)
        }
        return true
    }

    fun buildUtxoTransactionSync(
        recipient: ByteArray,
        amount: Long,
    ): UTXOTransaction? {
        Log.d("UTXOService", "sending amount: $amount")
        val myPublicKey = trustChainCommunity.myPeer.publicKey.keyToBin()

        // 1) gather available UTXOs
        val utxos: List<UTXO> = store.getUtxosByOwner(myPublicKey)

        if (getMyBalance() - amount < 0) {
            Log.d("UTXOService", "Insufficient funds")
            return null
        }

        // 2) select utxos (naive: first-fit)
        val inputs = mutableListOf<UTXO>()
        var sum = 0L
        for (u in utxos) {
            inputs += u; sum += u.amount
            if (sum >= amount) break
        }

        // 3) prepare outputs: recipient + change
        val txid = MessageDigest.getInstance("SHA-256").digest(System.nanoTime().toString().toByteArray())
        val outs = mutableListOf<UTXO>()
        outs += UTXO(txid.toHex(), 0, amount.toInt(), recipient)
        val change = sum - amount
        if (change > 0) outs += UTXO(txid.toHex(), 1, change.toInt(), trustChainCommunity.myPeer.publicKey.keyToBin())

        // 4) Build the UTXO Transaction
        val utxoTransaction = UTXOTransaction.create(txid.toHex(), myPublicKey, recipient, inputs, outs)

        return utxoTransaction
    }

    fun checkDoubleSpending(utxoTransaction: UTXOTransaction): Boolean {
        // Check if any input UTXO is already spent
        for (input in utxoTransaction.inputs) {
            if (bloom.contains(input.getUTXOIdString().toByteArray())) {
                Log.d("UTXOService", "Double spending detected for input: ${input.getUTXOIdString()}")
                return true
            }
        }
        return false
    }

    fun addUTXOTransaction(utxoTransaction: UTXOTransaction): Boolean {
        // Check for double spending
        if (checkDoubleSpending(utxoTransaction)) {
            Log.e("UTXOService", "Double spending detected for transaction: ${utxoTransaction.txId}")
            return false
        }

        val success = store.addUTXOTransaction(utxoTransaction)
        if (success) {
            Log.d("UTXOService", "UTXOTransaction added successfully: ${utxoTransaction.txId}")
            // Update bloom filter with inputs
            utxoTransaction.inputs.forEach { input ->
                bloom.add(input.getUTXOIdString().toByteArray())
                return true
            }
        }
        else {
            Log.e("UTXOService", "Failed to add UTXOTransaction: ${utxoTransaction.txId}")
            return false
        }
        return false
    }

    fun createGenesisUTXO(): Boolean {
        val genesisUtxo = UTXO(
            txId = "genesis_" +
                trustChainCommunity.myPeer.publicKey.keyToBin().toHex(),
            txIndex = 0,
            amount = GENESIS_AMOUNT,
            owner = "_genesis_".toByteArray(),
        )

        addUTXO(genesisUtxo)

        val outputUTXO = UTXO(
            txId = "genesis_" +
                trustChainCommunity.myPeer.publicKey.keyToBin().toHex(),
            txIndex = 1,
            amount = GENESIS_AMOUNT,
            owner = trustChainCommunity.myPeer.publicKey.keyToBin()
        )

        Log.e("GENESIS", "Genesis txId: ${outputUTXO.txId}")

        val genesisTransaction = UTXOTransaction(
            "genesis_" +
                trustChainCommunity.myPeer.publicKey.keyToBin().toHex(),
            "_genesis_".toByteArray(),
            trustChainCommunity.myPeer.publicKey.keyToBin(),
            listOf(genesisUtxo),
            listOf(outputUTXO)
        )
        val success = addUTXOTransaction(genesisTransaction)
        return success
    }

    companion object {
        const val GENESIS_AMOUNT = 10000

        fun prettyAmount(amount: Long): String {
            return "€" + (amount / 100).toString() + "," +
                (abs(amount) % 100).toString()
                    .padStart(2, '0')
        }
    }
}
