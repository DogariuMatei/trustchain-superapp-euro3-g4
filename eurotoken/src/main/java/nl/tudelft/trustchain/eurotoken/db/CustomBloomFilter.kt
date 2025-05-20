package nl.tudelft.trustchain.eurotoken.db

import java.util.BitSet

/*
    This class should probably be in the NFC/Bluetooth folder since there is no tokenID in either
    EuroToken to ValueTransfer: they use int amount of cents not the tokenID of the coin.
    (might also be wrong :p)


    Logic is pretty straightforward:
    Sender sends bloomfilter alongside the transaction payload (which should also contain the tokenID)
    and the transactions (notice the plural) payload (for updating trustscores)
    Receiver receives and sends back their bloomfilter alongside acknowldgement payload and transactions payload
    Receiver checks for fraud and processes transaction and merges bloom filter and updates trust
    Sender updates trust and merges bloom filter as well
 */
class CustomBloomFilter(
    private val size: Int = 1024,
    private val hashFunctions: Int = 4,
) {
    private val bitArray = BitSet(size)

    /**
     * Add the tokenID to the filter
     */
    fun add(item: String) {
        val hashes = getHashes(item)
        for (hash in hashes) {
            bitArray.set(hash)
        }
    }

    /**
     * Check if bloom filter already contains the token
     * This will be called to detect double spending
     */
    fun contain(item: String): Boolean {
        val hashes = getHashes(item)
        return hashes.all { bitArray[it] }
    }

    /**
     * Update local bloom filter with new information
     */
    fun mergeFrom(other: CustomBloomFilter) {
        this.bitArray.or(other.bitArray)
    }

    fun toByteArray(): ByteArray {
        return bitArray.toByteArray()
    }

    fun loadFromByteArray(bytes: ByteArray) {
        bitArray.clear()
        bitArray.or(BitSet.valueOf(bytes))
    }

    private fun getHashes(item: String): List<Int> {
        return List(hashFunctions) { i ->
            val hash = item.hashCode() + i * 31
            Math.abs(hash % size)
        }
    }
}
