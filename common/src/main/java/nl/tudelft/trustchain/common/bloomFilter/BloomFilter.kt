package nl.tudelft.trustchain.common.bloomFilter

import com.google.common.hash.HashFunction
import com.google.common.hash.Hashing
import java.util.BitSet
import kotlin.math.*
import java.nio.ByteBuffer

class BloomFilter {
    private val hashFunctionCount: Int
    private val bitsetSize: Int
    private val bitset: BitSet
    val getBitset: BitSet
        get() = bitset
    private val hashFunction: HashFunction

    constructor(capacity: Int): this(capacity, 0.01f)

    constructor(capacity: Int, errorRate: Float)
        : this(BestM(capacity, errorRate), BestK(capacity, errorRate))

    private constructor(m: Int, k: Int) {
        // Initialize Guava hash functions
        this.hashFunction = Hashing.murmur3_128()
        this.hashFunctionCount = k
        this.bitsetSize = m
        this.bitset = BitSet(m)
    }

    /**
     * Adds an item to the Bloom filter.
     */
    fun add(item: ByteArray) {
        val hash = hashFunction.hashBytes(item).asBytes()
        val primaryHash = ByteBuffer.wrap(hash.copyOfRange(0, 8)).long.toULong()
        val secondaryHash = ByteBuffer.wrap(hash.copyOfRange(8, 16)).long.toULong()
        for (i in 1..hashFunctionCount) {
            val idx = computeHash(primaryHash, secondaryHash, i)
            bitset.set(idx)
        }
    }

    /**
     * Checks whether an item might be in the Bloom filter.
     */
    fun contains(item: ByteArray): Boolean {
        val hash = hashFunction.hashBytes(item).asBytes()
        val primaryHash = ByteBuffer.wrap(hash.copyOfRange(0, 8)).long.toULong()
        val secondaryHash = ByteBuffer.wrap(hash.copyOfRange(8, 16)).long.toULong()
        for (i in 1..hashFunctionCount) {
            val idx = computeHash(primaryHash, secondaryHash, i)
            if (!bitset.get(idx)) return false
        }
        return true
    }

    private fun computeHash(primary: ULong, secondary: ULong, i: Int): Int {
        // Double hashing: combine primary and secondary
        val combined = primary + secondary * i.toULong()
        return (combined % bitsetSize.toULong()).toInt()
    }

    companion object {
        /**
         * Optimal number of hash functions k = (m/n) * ln(2)
         */
        fun BestK(capacity: Int, errorRate: Float): Int =
            round(ln(2.0) * BestM(capacity, errorRate) / capacity).toInt()

        /**
         * Optimal size of bitset m = ceil((n * ln(errorRate)) / ln(1 / 2^{ln(2)}))
         */
        fun BestM(capacity: Int, errorRate: Float): Int =
            ceil(capacity * ln(errorRate) / ln(1.0 / 2.0.pow(ln(2.0)))).toInt()

        /**
         * TODO: Compute the actual error rate for given parameters.
         */
        fun ErrorRate(capacity: Int, m: Int, k: Int): Double {
            val x = 1 - exp(-k.toDouble() * capacity / m.toDouble())
            return x.pow(k)
        }

        /**
         * TODO: Approximate best error rate.
         */
        fun BestErrorRate(capacity: Int, m: Int): Float {
            val c = 1f / capacity
            return if (c != 0f) c
            else 0.6185f.pow(m.toFloat() / capacity)
        }
    }
}
