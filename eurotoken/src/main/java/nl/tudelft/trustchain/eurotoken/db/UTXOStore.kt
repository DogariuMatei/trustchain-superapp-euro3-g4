package nl.tudelft.trustchain.eurotoken.db

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import nl.tudelft.eurotoken.sqldelight.Database
import nl.tudelft.trustchain.eurotoken.entity.UTXO
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.ipv8.util.hexToBytes

class UTXOStore(context: Context) {
    private val driver = AndroidSqliteDriver(Database.Schema, context, "eurotoken.db")
    private val database = Database(driver)

    private val utxoMapper = {
            txId: ByteArray,
            txIndex: Long,
            amount: Long,
            owner: ByteArray
        ->
        UTXO(txId.toHex(), txIndex.toInt(), amount.toInt(), owner)
    }

    /**
     * Retrieve all [UTXO]s from the database.
     */
    fun getAllUTXOs(): List<UTXO> {
        return database.dbUtxoQueries.getAllUTXOs(utxoMapper).executeAsList()
    }

    /**
     * Retrieve the [UTXO]s of a specific public key.
     */
    fun getUtxosByOwner(owner: ByteArray): List<UTXO> {
        return database.dbUtxoQueries.getUTXOsByOwner(owner, utxoMapper).executeAsList()
    }

    fun getUtxosById(txId: String): List<UTXO> {
        return database.dbUtxoQueries.getUTXOsByOwner(txId.hexToBytes(), utxoMapper).executeAsList()
    }

    fun getUtxo(txId: String, txIndex: Int): UTXO? {
        return database.dbUtxoQueries.getUTXO(txId.hexToBytes(), txIndex.toLong(), utxoMapper).executeAsOneOrNull()
    }

    fun addUtxo(utxo: UTXO) {
        database.dbUtxoQueries.addUTXO(utxo.txId.hexToBytes(), utxo.txIndex.toLong(), utxo.amount.toLong(), utxo.owner)
    }

    fun removeUtxo(txId: String, txIndex: Int) {
        database.dbUtxoQueries.removeUTXO(txId.hexToBytes(), txIndex.toLong())
    }

    /**
     * Initialize the database.
     */
    fun createContactStateTable() {
        database.dbUtxoQueries.createContactStateTable()
    }

    companion object {
        private lateinit var instance: UTXOStore

        fun getInstance(context: Context): UTXOStore {
            if (!::instance.isInitialized) {
                instance = UTXOStore(context)
                instance.createContactStateTable()
            }
            return instance
        }
    }
}
