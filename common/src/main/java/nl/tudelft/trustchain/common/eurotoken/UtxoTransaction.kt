package nl.tudelft.trustchain.common.eurotoken

import java.util.BitSet

/**
 * A fully-formed UTXO transaction (transfer proposal), bundling
 * the spent inputs, the newly created outputs, and all the proofs
 * needed to validate the spend.
 */
data class UTXOTransaction(
    /** A unique transaction identifier (e.g. SHA‐256 over nonce + inputs). */
    val txId: String,

    /** The UTXO identifiers being consumed by this transaction. */
    val inputs: List<UTXO>,

    /** The new UTXOs created by this transaction (recipient + optional change). */
    val outputs: List<UTXO>,

    /** A Bloom filter committing to the set of spent input‐keys. */
    /*val bloomFilter: BitSet,*/

    /**
     * Merkle‐Patricia inclusion proofs for each input UTXO,
     * keyed by UTXOId.
     */
    /*val proofs: Map<UTXOId, List<ByteArray>>,*/

    /** The root hash of the state trie at the point of spending. */
    /*val rootHash: ByteArray,*/
) { /*{
    companion object {
        private fun generateTxId(inputs: List<UTXOId>): ByteArray {
            // simple example: hash concat of input IDs
            val md = MessageDigest.getInstance("SHA-256")
            inputs.sortedBy { it.toString() }
                .forEach { md.update(it.toBytes()) }
            return md.digest()
        }
    }
}*/
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UTXOTransaction

        if (inputs != other.inputs) return false
        if (outputs != other.outputs) return false
        if (txId != other.txId) return false

        return true
    }

    override fun hashCode(): Int {
        var result = inputs.hashCode()
        result = 31 * result + outputs.hashCode()
        result = 31 * result + txId.hashCode()
        return result
    }
}
