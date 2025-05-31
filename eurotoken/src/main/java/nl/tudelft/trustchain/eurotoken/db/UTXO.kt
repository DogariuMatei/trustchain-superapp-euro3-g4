package nl.tudelft.trustchain.eurotoken.db
import nl.tudelft.ipv8.util.toHex

import kotlin.random.Random

class UTXO {
    private val generatedTokenIds: MutableList<String> = mutableListOf()
    private val receivedTokenIds: MutableList<String> = mutableListOf()
    private val bloomFilter: CustomBloomFilter = CustomBloomFilter()

    companion object {
        private const val TOKEN_VALUE = 1
        private const val TOTAL_VALUE = 10
        private const val NUM_TOKENS = TOTAL_VALUE / TOKEN_VALUE
    }

    init {
        repeat(NUM_TOKENS) {
            generatedTokenIds.add(generateTokenId())
        }
    }

    fun numTokensAvailable(): Int {
        return generatedTokenIds.size
    }

    fun sendTokens(amount: Int): ByteArray {
        // Logic to convert amount to match token value, but for now assume each token as 1$
        val numTokens = amount
        if (numTokensAvailable() >= numTokens) {
            val tokensToSend = generatedTokenIds.take(numTokens).toMutableList()
            generatedTokenIds.removeAll(tokensToSend)
            val serialized = tokensToSend.joinToString(separator = ",")
            return serialized.encodeToByteArray()
        } else {
            throw RuntimeException("Not enough tokens!")
        }
    }

    fun receiveTokens(serializedTokens: ByteArray) {
        val tokens = serializedTokens.decodeToString().split(",")
        val hasFraud = tokens.any { bloomFilter.contain(it) }
        if (hasFraud) {
            throw RuntimeException("Fraud detected: One or more TokenIDs already exist in Bloom filter!")
        }
        for (tokenId in tokens) {
            bloomFilter.add(tokenId)
            receivedTokenIds.add(tokenId)
        }
    }

    fun sendFilter(): ByteArray {
        return bloomFilter.toByteArray()
    }

    fun receiveFilter(filterBytes: ByteArray) {
        val receivedFilter = CustomBloomFilter()
        receivedFilter.loadFromByteArray(filterBytes)
        bloomFilter.mergeFrom(receivedFilter)
    }

    private fun generateTokenId(): String {
        val random = Random.Default
        val bytes = ByteArray(42)
        random.nextBytes(bytes)
        return bytes.toHex()
    }
}
