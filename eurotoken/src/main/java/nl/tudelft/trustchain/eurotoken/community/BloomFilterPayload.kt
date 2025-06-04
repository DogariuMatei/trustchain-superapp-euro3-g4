package nl.tudelft.trustchain.eurotoken.community

import nl.tudelft.ipv8.messaging.*

/** Carries a serialised Bloom filter. */
class BloomFilterPayload(val filter: ByteArray) : Serializable {
    override fun serialize(): ByteArray = serializeVarLen(filter)

    companion object Deserializer : Deserializable<BloomFilterPayload> {
        override fun deserialize(buffer: ByteArray, offset: Int): Pair<BloomFilterPayload, Int> {
            val (data, size) = deserializeVarLen(buffer, offset)
            return BloomFilterPayload(data) to size
        }
    }
}