package nl.tudelft.trustchain.eurotoken.community

import nl.tudelft.ipv8.messaging.*

/** Returns the keys the receiving peer is still missing. */
class MissingKeysPayload(val keysCsv: ByteArray) : Serializable {
    override fun serialize(): ByteArray = serializeVarLen(keysCsv)

    companion object Deserializer : Deserializable<MissingKeysPayload> {
        override fun deserialize(buffer: ByteArray, offset: Int): Pair<MissingKeysPayload, Int> {
            val (data, size) = deserializeVarLen(buffer, offset)
            return MissingKeysPayload(data) to size
        }
    }
}
