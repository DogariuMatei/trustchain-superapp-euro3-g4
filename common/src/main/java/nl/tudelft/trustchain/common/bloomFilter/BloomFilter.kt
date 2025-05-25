package nl.tudelft.trustchain.common.bloomFilter

import com.google.common.hash.HashFunction
import com.google.common.hash.Hashing
import java.util.BitSet
import kotlin.math.*

class BloomFilter {
    private val hashFunctionCount: Int
    private val bitsetSize: Int
    private val bitset: BitSet
    private val primaryHash: HashFunction
    private val secondaryHash: HashFunction

    constructor(capacity: Int): this(capacity, 0.01f)

    constructor(capacity: Int, errorRate: Float)
        : this(BestM(capacity, errorRate), BestK(capacity, errorRate))

    private constructor(m: Int, k: Int) {
        // Initialize Guava hash functions
        this.primaryHash = Hashing.sipHash24()
        this.secondaryHash = Hashing.murmur3_32()
        this.hashFunctionCount = k
        this.bitsetSize = m
        this.bitset = BitSet(m)
    }

    /**
     * Adds an item to the Bloom filter.
     */
    fun add(item: ByteArray) {
        val primaryHash = primaryHash.hashBytes(item).asLong().toULong()
        val secondaryHash = secondaryHash.hashBytes(item).asLong().toULong()
        for (i in 1..hashFunctionCount) {
            val idx = computeHash(primaryHash, secondaryHash, i)
            bitset.set(idx)
        }
    }

    /**
     * Checks whether an item might be in the Bloom filter.
     */
    fun contains(item: ByteArray): Boolean {
        val primaryHash = primaryHash.hashBytes(item).asLong().toULong()
        val secondaryHash = secondaryHash.hashBytes(item).asLong().toULong()
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
