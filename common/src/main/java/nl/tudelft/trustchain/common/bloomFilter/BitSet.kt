@file:OptIn(ExperimentalUnsignedTypes::class)
package nl.tudelft.trustchain.common.bloomFilter

class BitSet(val size: Int) {
    private val bitset = ULongArray((size / 64) + 1)

    fun add(index: Int) {
        val word = index / 64
        val bit = index % 64
        bitset[word] = bitset[word] or (1UL shl bit)
    }

    fun contains(index: Int): Boolean {
        val word = index / 64
        val bit = index % 64
        return (bitset[word] and (1UL shl bit)) != 0UL
    }
}
