package nl.tudelft.trustchain.eurotoken.db

import java.util.BitSet
import kotlin.math.abs

class CustomBloomFilter(
    private val size: Int = 1024,
    private val hashFunctions: Int = 3,
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
            abs(hash % size)
        }
    }
}
