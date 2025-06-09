import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.mockk.every
import io.mockk.mockk
import nl.tudelft.common.sqldelight.Database
import nl.tudelft.ipv8.attestation.trustchain.TrustChainCommunity
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.eurotoken.SqlUtxoStore
import nl.tudelft.trustchain.common.eurotoken.UTXO
import nl.tudelft.trustchain.common.eurotoken.UTXOService
import nl.tudelft.trustchain.common.eurotoken.UTXOService.Companion.GENESIS_AMOUNT
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class UtxoServiceDoubleSpendTest {
    private lateinit var ownerUtxoService: UTXOService
    private val ownerKey = byteArrayOf(1, 2, 3)
    private val recipientKey = byteArrayOf(4, 5, 6)
    private lateinit var recipientUtxoService: UTXOService

    @Before
    fun setUp() {
        val driver1 = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver1)
        val ownerUtxoStore = SqlUtxoStore(driver1)
        ownerUtxoStore.createContactStateTable()
        var trustChainCommunity: TrustChainCommunity = mockk(relaxed = true)
        every { trustChainCommunity.myPeer.publicKey.keyToBin() } returns ownerKey
        ownerUtxoService = UTXOService(trustChainCommunity = trustChainCommunity, store = ownerUtxoStore)
        ownerUtxoService.createGenesisUTXO()

        val driver2 = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver2)
        val recipientUtxoStore = SqlUtxoStore(driver2)
        recipientUtxoStore.createContactStateTable()
        trustChainCommunity = mockk(relaxed = true)
        every { trustChainCommunity.myPeer.publicKey.keyToBin() } returns recipientKey
        recipientUtxoService = UTXOService(trustChainCommunity = trustChainCommunity, store = recipientUtxoStore)
        recipientUtxoService.createGenesisUTXO()
    }

    @Test
    fun `test double spend`() {
        // Build a transaction from the owner to the recipient with an amount of 100.
        val tx1 = ownerUtxoService.buildUtxoTransactionSync(recipient = recipientKey, amount = 100)!!
        val genesisUtxo = UTXO(txId = "", txIndex = 1, amount = GENESIS_AMOUNT, owner = byteArrayOf())
        assertEquals(genesisUtxo.copy(txId = ownerKey.toHex(), owner = ownerKey), tx1.inputs[0])
        val transferUtxo = UTXO(txId = tx1.txId, txIndex = 0, amount = 100, owner = recipientKey)
        assertEquals(listOf(transferUtxo,
            UTXO(txId = tx1.txId, txIndex = 1, amount = GENESIS_AMOUNT - 100, owner = ownerKey)), tx1.outputs)

        // Add the transaction to the recipient's UTXO store.
        recipientUtxoService.addUTXOTransaction(tx1)
        // Build a second transaction from the recipient back to the owner.
        val tx2 = recipientUtxoService.buildUtxoTransactionSync(recipient = ownerKey, amount = GENESIS_AMOUNT.toLong() + 100)!!
        assertEquals(genesisUtxo.copy(txId = recipientKey.toHex(), owner = recipientKey), tx2.inputs[0])
        assertEquals(transferUtxo, tx2.inputs[1])
        assertEquals(listOf(UTXO(txId = tx2.txId, txIndex = 0, amount = GENESIS_AMOUNT + 100, owner = ownerKey)), tx2.outputs)

        // Assert that the recipient cannot add the first transaction again, preventing double spending.
        assertFalse(recipientUtxoService.addUTXOTransaction(tx1))
        assertEquals(tx1.inputs[0].copy(spentInTxId = tx1.txId), recipientUtxoService.store.querySpentUtxos()[1])
        assertEquals(ownerUtxoService.rebuildBloomFilter().getBitset, ownerUtxoService.bloomFilter.getBitset)
        assertEquals(recipientUtxoService.rebuildBloomFilter().getBitset, recipientUtxoService.bloomFilter.getBitset)
    }
}
