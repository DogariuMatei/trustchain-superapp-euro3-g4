package nl.tudelft.trustchain.eurotoken.entity

// TODO: Add Timestamp
data class UTXO(
    /**
     * The unique transaction ID.
     */
    val txId: String,
    /**
     * Output index from previous transaction.
     */
    val txIndex: Int,
    /**
     * The transaction amount in cent.
     */
    val amount: Int,
    /**
     * The public key of the owner.
     */
    val owner: ByteArray
) {
    /**
     * Builds a string representation of the UTXO ID by combining txId and txIndex.
     * Format: "txId:txIndex"
     */
    fun getUTXOIdString(): String {
        return "$txId:$txIndex"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as UTXO

        if (txIndex != other.txIndex) return false
        if (amount != other.amount) return false
        if (txId != other.txId) return false
        if (!owner.contentEquals(other.owner)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = txIndex
        result = 31 * result + amount
        result = 31 * result + txId.hashCode()
        result = 31 * result + owner.contentHashCode()
        return result
    }
}
