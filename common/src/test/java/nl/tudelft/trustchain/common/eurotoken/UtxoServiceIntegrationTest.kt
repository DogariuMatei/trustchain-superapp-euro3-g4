import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import nl.tudelft.common.sqldelight.Database
import nl.tudelft.ipv8.attestation.trustchain.TrustChainCommunity
import nl.tudelft.trustchain.common.bloomFilter.BloomFilter
import nl.tudelft.trustchain.common.eurotoken.SqlUtxoStore
import nl.tudelft.trustchain.common.eurotoken.UTXO
import nl.tudelft.trustchain.common.eurotoken.UTXOService
import nl.tudelft.trustchain.common.eurotoken.UTXOStore
import nl.tudelft.trustchain.common.eurotoken.UTXOTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UtxoServiceIntegrationTest {
    private lateinit var utxoStore: UTXOStore
    private lateinit var utxoService: UTXOService
    private val ownerKey = byteArrayOf(1, 2, 3)
    private val recipientKey = byteArrayOf(4, 5, 6)
    private val utxo = UTXO(txId = "aa", txIndex = 0, amount = 100, owner = ownerKey)
    private val utxo2 = UTXO(txId = "bb", txIndex = 0, amount = 200, owner = ownerKey)

    @Before
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver)
        utxoStore = SqlUtxoStore(driver)
        utxoStore.createContactStateTable()
        val trustChainCommunity: TrustChainCommunity = mockk(relaxed = true)
        every { trustChainCommunity.myPeer.publicKey.keyToBin() } returns ownerKey
        utxoService = UTXOService(trustChainCommunity = trustChainCommunity, store = utxoStore)
    }

    @Test
    fun `test addUTXO adds utxo to store`() {
        utxoService.addUTXO(utxo)
        assertEquals(utxo, utxoService.getUTXO(utxo.txId, utxo.txIndex))
    }

    @Test
    fun `test removeUTXO removes utxo and adds to bloom`() {
        utxoService.addUTXO(utxo)
        utxoService.removeUTXO(utxo)
        assertNull(utxoService.getUTXO(utxo.txId, utxo.txIndex))
        assertTrue(utxoService.bloomFilter.contains(utxo.getUTXOIdString().toByteArray()))
    }

    @Test
    fun `test double spending detection returns true if bloom contains input`() {
        val tx = UTXOTransaction(txId = "aa", sender = recipientKey, recipient = ownerKey,
            inputs = listOf(UTXO(txId = "aa", txIndex = 0, amount = 100, owner = recipientKey)), outputs = listOf())
        utxoService.addUTXOTransaction(tx)
        assertTrue(utxoService.checkDoubleSpending(tx))
    }

    @Test
    fun `test double spending detection returns false if bloom does not contain input`() {
        val tx = UTXOTransaction(txId = "aa", sender = recipientKey, recipient = ownerKey,
            inputs = listOf(UTXO(txId = "aa", txIndex = 0, amount = 100, owner = recipientKey)), outputs = listOf())
        assertFalse(utxoService.checkDoubleSpending(tx))
    }

    @Test
    fun `test getMyBalance sums utxo amounts`() {
        utxoStore.addUtxo(utxo)
        utxoStore.addUtxo(utxo2)
        assertEquals(300L, utxoService.getMyBalance())
    }

    @Test
    fun `test buildUtxoTransactionSync returns null if insufficient funds`() {
        utxoStore.addUtxo(utxo)
        assertNull(utxoService.buildUtxoTransactionSync(recipientKey, 200))
    }

    @Test
    fun `test buildUtxoTransactionSync returns transaction if sufficient funds`() {
        utxoStore.addUtxo(utxo)
        utxoStore.addUtxo(utxo2)
        val tx = utxoService.buildUtxoTransactionSync(recipientKey, 100)
        assertNotNull(tx)
        assertEquals(ownerKey, tx?.sender)
        assertEquals(recipientKey, tx?.recipient)
    }
}
