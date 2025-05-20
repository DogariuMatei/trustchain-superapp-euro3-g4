package nl.tudelft.trustchain.eurotoken.community

import nl.tudelft.ipv8.messaging.Deserializable
import nl.tudelft.ipv8.messaging.Serializable
import nl.tudelft.ipv8.messaging.deserializeVarLen
import nl.tudelft.ipv8.messaging.serializeVarLen

/**
 * Class for transferring string data -> json -> over the network
 * WILL BE USED FOR DEMO NFC OPERATIONS
 */
class StringPayload(val message: String) : Serializable {
    override fun serialize(): ByteArray {
        return serializeVarLen(message.toByteArray())
    }

    companion object Deserializer : Deserializable<StringPayload> {
        override fun deserialize(
            buffer: ByteArray,
            offset: Int
        ): Pair<StringPayload, Int> {
            var localOffset = offset
            val (message, messageSize) = deserializeVarLen(buffer, localOffset)
            localOffset += messageSize

            return Pair(
                StringPayload(message.toString(Charsets.UTF_8)),
                localOffset - offset
            )
        }
    }
}
