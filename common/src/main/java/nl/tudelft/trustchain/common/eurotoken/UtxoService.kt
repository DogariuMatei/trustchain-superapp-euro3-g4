/*
package nl.tudelft.trustchain.common.eurotoken.utxo

import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import nl.tudelft.ipv8.attestation.trustchain.TrustChainCommunity
import nl.tudelft.ipv8.attestation.trustchain.store.TrustChainStore
import nl.tudelft.trustchain.common.bloomFilter.BloomFilter
import java.security.MessageDigest

// TODO: Merkle-Patricia Trie interface for inclusion and removal proofs
*/
/*typealias Proof = List<ByteArray>
interface MerklePatriciaTrie {
    fun getRootHash(): ByteArray
    fun put(key: ByteArray, utxo: UTXO)
    fun remove(key: ByteArray)
    fun getProof(key: ByteArray): Proof
    fun verifyProof(root: ByteArray, key: ByteArray, proof: Proof): Boolean
}*//*


// 4. Service integrating Bloom filter + MPT
class UTXOService(
    //trie: MerklePatriciaTrie,
    private val trustChainCommunity: TrustChainCommunity,
    private val store: TrustChainStore,
    expectedUTXOs: Int = 2_000,
    falsePositiveRate: Float = 0.01f
) {
    private var trieImpl : HashMap<ByteArray, UTXO> = HashMap<ByteArray, UTXO> ()
    //private var currentRoot: ByteArray = trieImpl.getRootHash()
    private var bloom = BloomFilter(expectedUTXOs, falsePositiveRate)

    */
/** Add a new UTXO to local state and update commitments *//*

    fun addUTXO(utxo: UTXO) {
        val key = utxo.id.toBytes()
        trieImpl.put(key, utxo)
        bloom.add(key)
        //currentRoot = trieImpl.getRootHash()
    }

    */
/** Spend (remove) an existing UTXO *//*

    fun removeUTXO(id: UTXOId) {
        val key = id.toBytes()
        trieImpl.remove(key)
        // TODO: bloom filters cannot delete; rebuild periodically?
        //currentRoot = trieImpl.getRootHash()
    }

    */
/** Build a transfer with inputs, outputs, bloom, proofs, and root *//*

    fun buildTransferProposal(
        recipient: ByteArray,
        amount: Long,
        gatherUTXOs: () -> List<UTXO>
    ): TransactionRepository {
        // 1) gather available UTXOs
        val utxos = gatherUTXOs()
        // 2) select coins (naive: first-fit)
        val inputs = mutableListOf<UTXO>()
        var sum = 0L
        for (u in utxos) {
            inputs += u; sum += u.amount
            if (sum >= amount) break
        }
        require(sum >= amount) { "Insufficient funds" }

        // 3) prepare outputs: recipient + change
        val txid = MessageDigest.getInstance("SHA-256").digest(System.nanoTime().toString().toByteArray())
        val outs = mutableListOf<UTXO>()
        outs += UTXO(UTXOId(txid, 0), amount, recipient)
        val change = sum - amount
        if (change > 0) outs += UTXO(UTXOId(txid, 1), change, trustChainCommunity.myPeer.publicKey.keyToBin())

        // 4) bloom + proof for each spent input
        val bf = BloomFilter(inputs.size, falsePositiveRate)
        val proofs = mutableMapOf<UTXOId, Proof>()
        inputs.forEach { utxo ->
            val key = utxo.id.toBytes()
            bf.put(key)
            proofs[utxo.id] = trieImpl.getProof(key)
        }

        return TransactionRepository.TransferProposal(
            inputs = inputs.map { it.id },
            outputs = outs,
            bloomFilter = bf,
            proofs = proofs,
            rootHash = currentRoot
        )
    }

    */
/** On receive: validate bloom, proofs, then apply state changes and checkpoint root *//*

    fun validateAndCommit(proposal: TransactionRepository.TransferProposal, peerPub: ByteArray): Boolean {
        // quick reject
        proposal.inputs.forEach { id ->
            if (!bloom.mightContain(id.toBytes())) return false
        }
        // verify proofs
        proposal.proofs.forEach { (id, proof) ->
            if (!trieImpl.verifyProof(proposal.rootHash, id.toBytes(), proof)) return false
        }
        // apply removals and additions
        proposal.inputs.forEach { removeUTXO(it) }
        proposal.outputs.forEach { addUTXO(it) }
        // commit via TrustChain block
        val block = trustChainCommunity.createProposalBlock(
            TransactionRepository.BLOCK_TYPE_TRANSFER,
            mapOf(
                "root" to proposal.rootHash.toHex(),
                "inputs" to proposal.inputs.map { it.toBytes().toHex() },
                "outputs" to proposal.outputs.map { it.id.toBytes().toHex() }
            ),
            peerPub
        )
        trustChainCommunity.sendBlock(block, trustChainCommunity.getPeer(peerPub)!!)
        store.saveBlock(block)
        return true
    }
}
*/
