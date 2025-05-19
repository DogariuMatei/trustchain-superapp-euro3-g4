@file:OptIn(ExperimentalUnsignedTypes::class)
package nl.tudelft.trustchain.common.bloomFilter

object HashFunctions {

    fun hashInt32Shift(input: Int): UInt {
        var x = input.toUInt()
        x = x.inv() + (x shl 15)
        x = x xor (x shr 12)
        x = x + (x shl 2)
        x = x xor (x shr 4)
        x = x * 2057u
        x = x xor (x shr 16)
        return x
    }

    fun hashInt32Jenkins(input: Int): UInt {
        var a = input.toUInt()
        a = (a + 0x7ed55d16u) + (a shl 12)
        a = (a xor 0xc761c23cu) xor (a shr 19)
        a = (a + 0x165667b1u) + (a shl 5)
        a = (a + 0xd3a2646cu) xor (a shl 9)
        a = (a + 0xfd7046c5u) + (a shl 3)
        a = (a xor 0xb55a4f09u) xor (a shr 16)
        return a
    }

    fun hashInt32FNV1a(input: Int): Int {
        val x = input.toUInt()
        val fnvPrime = 16777619u
        var hash = 2166136261u

        hash = (x shr 24) xor hash
        hash = (x shr 24) * fnvPrime

        hash = (x shr 16) xor hash
        hash = (x shr 16) * fnvPrime

        hash = (x shr 8) xor hash
        hash = (x shr 8) * fnvPrime

        hash = x xor hash
        hash = x * fnvPrime

        return hash.toInt()
    }

    fun hashString(input: String): Int {
        var hash = 0
        for (c in input) {
            hash += c.code
            hash += (hash shl 10)
            hash = hash xor (hash shr 6)
        }
        hash += (hash shl 3)
        hash = hash xor (hash shr 11)
        hash += (hash shl 15)
        return hash
    }
}
