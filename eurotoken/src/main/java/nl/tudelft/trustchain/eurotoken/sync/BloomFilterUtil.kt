package nl.tudelft.trustchain.eurotoken.sync

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnel
import com.google.common.hash.Funnels
import java.io.*


/**
 * Tiny wrapper around Guava’s BloomFilter for easy (de)serialisation.
 */
object BloomFilterUtil {
    private val funnel: Funnel<ByteArray> = Funnels.byteArrayFunnel()

    /** Build a Bloom filter from raw public-key bytes. */
    fun create(keys: List<ByteArray>, fpp: Double = 0.03): BloomFilter<ByteArray> =
        BloomFilter.create(funnel, maxOf(1, keys.size), fpp).apply {
            keys.forEach { put(it) }
        }

    /** Serialise to a compact byte array to ship over the network. */
    fun toBytes(bf: BloomFilter<ByteArray>): ByteArray =
        ByteArrayOutputStream().use { out ->
            bf.writeTo(DataOutputStream(out))
            out.toByteArray()
        }

    /** Rebuild a Bloom filter from the received bytes. */
    fun fromBytes(bytes: ByteArray): BloomFilter<ByteArray> =
        DataInputStream(ByteArrayInputStream(bytes)).use { inp ->
            BloomFilter.readFrom(inp, funnel)
        }
}
