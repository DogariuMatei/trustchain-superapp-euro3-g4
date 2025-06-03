import io.mockk.*
import nl.tudelft.ipv8.android.IPv8Android
import nl.tudelft.trustchain.common.eurotoken.*
import nl.tudelft.ipv8.attestation.trustchain.TrustChainCommunity
import nl.tudelft.trustchain.common.bloomFilter.BloomFilter
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UTXOServiceTest {
    private lateinit var utxoStore: UTXOStore
    private lateinit var bloomFilter: BloomFilter
    private lateinit var trustChainCommunity: TrustChainCommunity
    private lateinit var utxoService: UTXOService

    private val ownerKey = byteArrayOf(1, 2, 3)
    private val recipientKey = byteArrayOf(4, 5, 6)
    private val utxo = UTXO("txid1", 0, 100, ownerKey)
    private val utxo2 = UTXO("txid2", 0, 200, ownerKey)

    @Before
    fun setUp() {
        utxoStore = mockk(relaxed = true)
        bloomFilter = mockk(relaxed = true)
        trustChainCommunity = mockk(relaxed = true)
        every { trustChainCommunity.myPeer.publicKey.keyToBin() } returns ownerKey

        utxoService = spyk(UTXOService(trustChainCommunity, utxoStore), recordPrivateCalls = true)

        val bloomField = utxoService.javaClass.getDeclaredField("bloom")
        bloomField.isAccessible = true
        bloomField.set(utxoService, bloomFilter)
    }

    @Test
    fun `test addUTXO adds utxo to store`() {
        utxoService.addUTXO(utxo)
        verify { utxoStore.addUtxo(utxo) }
    }

    @Test
    fun `test removeUTXO removes utxo and adds to bloom`() {
        utxoService.removeUTXO(utxo)
        verify { utxoStore.removeUtxo(utxo.txId, utxo.txIndex) }
        verify { bloomFilter.add(utxo.getUTXOIdString().toByteArray()) }
    }

    @Test
    fun `test double spending detection returns true if bloom contains input`() {
        val tx = UTXOTransaction("txid", ownerKey, recipientKey, listOf(utxo), listOf())
        every { bloomFilter.contains(utxo.getUTXOIdString().toByteArray()) } returns true
        assertTrue(utxoService.checkDoubleSpending(tx))
    }

    @Test
    fun `test double spending detection returns false if bloom does not contain input`() {
        val tx = UTXOTransaction("txid", ownerKey, recipientKey, listOf(utxo), listOf())
        every { bloomFilter.contains(utxo.getUTXOIdString().toByteArray()) } returns false
        assertFalse(utxoService.checkDoubleSpending(tx))
    }

    @Test
    fun `test getMyBalance sums utxo amounts`() {
        every { utxoStore.getUtxosByOwner(ownerKey) } returns listOf(utxo, utxo2)
        assertEquals(300L, utxoService.getMyBalance())
    }

    @Test
    fun `test buildUtxoTransactionSync returns null if insufficient funds`() {
        every { utxoStore.getUtxosByOwner(ownerKey) } returns listOf(utxo)
        assertNull(utxoService.buildUtxoTransactionSync(recipientKey, 200))
    }

    @Test
    fun `test buildUtxoTransactionSync returns transaction if sufficient funds`() {
        every { utxoStore.getUtxosByOwner(ownerKey) } returns listOf(utxo, utxo2)
        val tx = utxoService.buildUtxoTransactionSync(recipientKey, 100)
        assertNotNull(tx)
        assertEquals(ownerKey, tx?.sender)
        assertEquals(recipientKey, tx?.recipient)
    }
}
