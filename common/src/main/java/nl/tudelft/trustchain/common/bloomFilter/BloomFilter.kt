@file:OptIn(ExperimentalUnsignedTypes::class)
package nl.tudelft.trustchain.common.bloomFilter

import kotlin.math.*

class BloomFilter {
    private val hashFunctionCount: Int
    private val hashBits: BitSet
    private val getHashSecondary: (Int) -> UInt

    constructor(capacity: Int): this(capacity, 0.01f)

    constructor(capacity: Int, errorRate: Float)
        : this(BestM(capacity, errorRate), BestK(capacity, errorRate))

    private constructor(m: Int, k: Int) {
        this.getHashSecondary = HashFunctions::hashInt32Shift
        this.hashFunctionCount = k
        this.hashBits = BitSet(m)
    }

    fun add(item: Int) {
        val primaryHash = HashFunctions.hashInt32Jenkins(item)
        val secondaryHash = getHashSecondary(item)
        repeat(hashFunctionCount) { i ->
            val h = computeHash(primaryHash, secondaryHash, i + 1)
            hashBits.add(h.toInt())
        }
    }

    fun contains(item: Int): Boolean {
        val primaryHash = HashFunctions.hashInt32Jenkins(item)
        val secondaryHash = getHashSecondary(item)
        repeat(hashFunctionCount) { i ->
            val h = computeHash(primaryHash, secondaryHash, i + 1)
            if (!hashBits.contains(h.toInt())) return false
        }
        return true
    }

    private fun computeHash(primary: UInt, secondary: UInt, i: Int): UInt {
        val c = primary + secondary * i.toUInt()
        return c % hashBits.size.toUInt()
    }

    companion object {
        fun BestK(capacity: Int, errorRate: Float): Int =
            round(ln(2.0) * BestM(capacity, errorRate) / capacity).toInt()

        fun BestM(capacity: Int, errorRate: Float): Int =
            ceil(capacity * ln(errorRate) / ln(1.0 / 2.0.pow(ln(2.0)))).toInt()

        fun ErrorRate(capacity: Int, m: Int, k: Int): Double {
            val x = 1 - exp(-k.toDouble() * capacity / m.toDouble())
            return x.pow(k)
        }

        fun BestErrorRate(capacity: Int, m: Int): Float {
            val c = 1f / capacity
            return if (c != 0f) c
            else 0.6185f.pow(m.toFloat() / capacity)
        }
    }
}
