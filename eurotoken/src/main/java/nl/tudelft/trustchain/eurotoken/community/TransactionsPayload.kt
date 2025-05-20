package nl.tudelft.trustchain.eurotoken.community

import nl.tudelft.ipv8.messaging.Deserializable
import nl.tudelft.ipv8.messaging.Serializable
import nl.tudelft.ipv8.messaging.deserializeVarLen
import nl.tudelft.ipv8.messaging.serializeVarLen

/**
 * This class is used to serialize and deserialize
 * the transactions payload message. In essence, this payload
 * encodes public keys such that trust scores can be updated.
 * Used by EuroTokenCommunity
 */
class TransactionsPayload(
    val id: String,
    val data: ByteArray,
    val filter: ByteArray,
    val tokenid: String
) : Serializable {
    override fun serialize(): ByteArray {
        return serializeVarLen(id.toByteArray()) +
            serializeVarLen(data) +
            serializeVarLen(filter) +
            serializeVarLen(tokenid.toByteArray())
    }

    companion object Deserializer : Deserializable<TransactionsPayload> {
        override fun deserialize(
            buffer: ByteArray,
            offset: Int
        ): Pair<TransactionsPayload, Int> {
            var localOffset = offset
            val (idBytes, idSize) = deserializeVarLen(buffer, localOffset)
            localOffset += idSize
            val (data, dataSize) = deserializeVarLen(buffer, localOffset)
            localOffset += dataSize
            val (filter, filterSize) = deserializeVarLen(buffer, localOffset)
            localOffset += filterSize
            val (tokenidBytes, tokenidSize) = deserializeVarLen(buffer, localOffset)
            localOffset += tokenidSize
            return Pair(
                TransactionsPayload(
                    idBytes.toString(Charsets.UTF_8),
                    data,
                    filter,
                    tokenidBytes.toString(Charsets.UTF_8)
                ),
                localOffset - offset
            )
        }
    }
}
