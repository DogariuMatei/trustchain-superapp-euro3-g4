package nl.tudelft.trustchain.common.eurotoken

import android.content.Context
import android.util.Log
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

    private var bloom = BloomFilter(expectedUTXOs, falsePositiveRate)

    /*
    * Add a new UTXO to local state and update commitments
    */
    fun addUTXO(utxo: UTXO) {
        store.addUtxo(utxo)
    }
    /*
    * Spend (remove) an existing UTXO
    */
    fun removeUTXO(txId: String, txIndex: Int) {
        store.removeUtxo(txId, txIndex)
    }

    fun getUTXO(txId: String, txIndex: Int): UTXO? {
        return store.getUtxo(txId, txIndex)
    }

    fun getUtxosByOwner(owner: ByteArray): List<UTXO> {
        Log.e("UTXOService", "Got UTXOs for owner: ${owner.toHex()}")
        return store.getUtxosByOwner(owner)
    }

    fun getMyBalance(): Long {
        val myPublicKey = IPv8Android.getInstance().myPeer.publicKey.keyToBin()
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
        val myPublicKey = IPv8Android.getInstance().myPeer.publicKey.keyToBin()
        // 1) gather available UTXOs
        val utxos: List<UTXO> = store.getUtxosByOwner(myPublicKey)

        if (getMyBalance() - amount < 0) {
            Log.d("UTXOService", "Insufficient funds")
            return null
        }

        // 2) select coins (naive: first-fit)
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

        // 5) Build the UTXO Transaction
        val utxoTransaction = UTXOTransaction(txid.toHex(), inputs, outs)

        return utxoTransaction
    }

    /*
    * On receive: validate bloom, proofs, then apply state changes and checkpoint root
    */
    /*fun validateAndCommit(utxoTx: UTXOTransaction, peerPub: ByteArray): Boolean {
        // quick reject
        proposal.inputs.forEach { id ->
            if (!bloom.mightContain(id.toBytes())) return false
        }
        // verify proofs
        proposal.proofs.forEach { (id, proof) ->
            if (!trieImpl.verifyProof(proposal.rootHash, id.toBytes(), proof)) return false
        }
        // apply removals and additions
        proposal.inputs.forEach { removeUTXO(it) }
        proposal.outputs.forEach { addUTXO(it) }
        // commit via TrustChain block
        val block = trustChainCommunity.createProposalBlock(
            TransactionRepository.BLOCK_TYPE_TRANSFER,
            mapOf(
                "root" to proposal.rootHash.toHex(),
                "inputs" to proposal.inputs.map { it.toBytes().toHex() },
                "outputs" to proposal.outputs.map { it.id.toBytes().toHex() }
            ),
            peerPub
        )
        trustChainCommunity.sendBlock(block, trustChainCommunity.getPeer(peerPub)!!)
        store.saveBlock(block)
        return true
    }*/

    companion object {
        fun prettyAmount(amount: Long): String {
            return "€" + (amount / 100).toString() + "," +
                (abs(amount) % 100).toString()
                    .padStart(2, '0')
        }

        var genesisUtxoId: String? = null
        var GENESIS_UTXO_CREATED: Boolean = false
    }
}
