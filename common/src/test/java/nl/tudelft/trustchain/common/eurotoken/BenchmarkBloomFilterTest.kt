import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.mockk.every
import io.mockk.mockk
import nl.tudelft.common.sqldelight.Database
import nl.tudelft.ipv8.attestation.trustchain.TrustChainCommunity
import nl.tudelft.trustchain.common.eurotoken.SqlUtxoStore
import nl.tudelft.trustchain.common.eurotoken.UTXOService
import org.junit.Ignore
import org.junit.Test
import java.security.SecureRandom

class BenchmarkBloomFilterTest {
//    @Ignore("Benchmark")
    @Test
    fun `benchmark bloom filter`() {
        val lastedRounds: MutableList<Int> = mutableListOf()
        val runs = 1
        val participantsNum = 30
        val tokensToSend: Long = 10
        for (r in 1..runs) {
            val participants: MutableList<UTXOService> = mutableListOf()
            for (i in 1..participantsNum) {
                val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
                Database.Schema.create(driver)
                val utxoStore = SqlUtxoStore(driver)
                utxoStore.createUtxoTables()
                val trustChainCommunity: TrustChainCommunity = mockk(relaxed = true)
                every { trustChainCommunity.myPeer.publicKey.keyToBin() } returns generateRandomPublicKey()
                val utxoService = UTXOService(trustChainCommunity = trustChainCommunity, store = utxoStore)
                utxoService.createGenesisUTXO()
                participants.add(utxoService)
            }
            var rounds = 0
            while (true) {
                val sender = participants.random()
                val receiver = participants.random()
                val senderKey = sender.trustChainCommunity.myPeer.publicKey.keyToBin()
                val receiverKey = receiver.trustChainCommunity.myPeer.publicKey.keyToBin()
                if (sender.getMyBalance() < tokensToSend || senderKey.contentEquals(receiverKey)) {
                    continue
                }
                // Sender → Receiver
                val pairOfInputUtxosAndSum = sender.commitUtxoInputs(tokensToSend)!!
                val bloomBitSet = sender.bloomFilter.getBitset
                // Receiver → Sender (confirmation)
                receiver.mergeBloomFilters(bloomBitSet)
                var doubleSpendingDetected = receiver.checkDoubleSpending(pairOfInputUtxosAndSum.first)
                if (doubleSpendingDetected) {
                    break
                }
                val receiverBloomBitSet = receiver.bloomFilter.getBitset
                // Sender → Receiver (final transaction)
                sender.mergeBloomFilters(receiverBloomBitSet)
                val utxoTransaction = sender.buildUtxoTransactionSync(
                    receiverKey,
                    tokensToSend,
                    pairOfInputUtxosAndSum.first,
                    pairOfInputUtxosAndSum.second
                )!!
                var success = sender.addUTXOTransaction(utxoTransaction)
                if (!success) {
                    break
                }

                doubleSpendingDetected = receiver.checkDoubleSpending(pairOfInputUtxosAndSum.first)
                if (doubleSpendingDetected) {
                    break
                }
                success = receiver.addUTXOTransaction(utxoTransaction)
                if (!success) {
                    break
                }
                rounds += 1
            }
            println("Benchmarking lasted $rounds rounds until collision")
            lastedRounds.add(rounds)
        }
        println("avg=${lastedRounds.average()} min=${lastedRounds.min()} max=${lastedRounds.max()}")
    }

    private fun generateRandomPublicKey(length: Int = 32): ByteArray {
        val random = SecureRandom()
        val bytes = ByteArray(length)
        random.nextBytes(bytes)
        return bytes
    }
}
