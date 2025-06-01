package nl.tudelft.trustchain.eurotoken

import nl.tudelft.trustchain.eurotoken.entity.UTXO
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.max
import kotlin.random.Random

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, (2 + 2))
    }

    @Test
    fun benchmarkBloomFilter() {
        val lastedRounds: MutableList<Int> = mutableListOf()
        val runs = 10
        val participantsNum = 30
        val maxTokensToSend = 1
        for (r in 1..runs) {
            val participants: MutableList<UTXO> = mutableListOf()
            for (i in 1..participantsNum) {
                participants.add(UTXO())
            }
            var rounds = 0
            while (true) {
                val sender = participants.random()
                val receiver = participants.random()
                if (sender.numTokensAvailable() < maxTokensToSend || sender === receiver) {
                    continue
                }
                val tokens = sender.sendTokens(Random.nextInt(max(maxTokensToSend, sender.numTokensAvailable())) + 1)
                try {
                    receiver.receiveTokens(tokens)
                } catch (e: RuntimeException) {
                    break
                }
                rounds += 1
            }
            println("Benchmarking lasted $rounds rounds until collision")
            lastedRounds.add(rounds)
        }
        println("avg=${lastedRounds.average()} min=${lastedRounds.min()} max=${lastedRounds.max()}")
    }
    }
