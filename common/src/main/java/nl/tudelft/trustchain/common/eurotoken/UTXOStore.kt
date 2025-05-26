package nl.tudelft.trustchain.common.eurotoken

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import nl.tudelft.common.sqldelight.Database
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.ipv8.util.hexToBytes

open class UTXOStore(val database: Database) {

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
    fun getAllUtxos(): List<UTXO> {
        return database.dbUtxoQueries.getAllUtxos(utxoMapper).executeAsList()
    }

    /**
     * Retrieve the [UTXO]s of a specific public key.
     */
    fun getUtxosByOwner(owner: ByteArray): List<UTXO> {
        return database.dbUtxoQueries.getUtxosByOwner(owner, utxoMapper).executeAsList()
    }

    fun getUtxosById(txId: String): List<UTXO> {
        return database.dbUtxoQueries.getUtxosById(txId.hexToBytes(), utxoMapper).executeAsList()
    }

    fun getUtxo(txId: String, txIndex: Int): UTXO? {
        return database.dbUtxoQueries.getUtxo(txId.hexToBytes(), txIndex.toLong(), utxoMapper).executeAsOneOrNull()
    }

    fun addUtxo(utxo: UTXO) {
        database.dbUtxoQueries.addUtxo(utxo.txId.hexToBytes(), utxo.txIndex.toLong(), utxo.amount.toLong(), utxo.owner)
    }

    fun removeUtxo(txId: String, txIndex: Int) {
        database.dbUtxoQueries.removeUtxo(txId.hexToBytes(), txIndex.toLong())
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
            if (!Companion::instance.isInitialized) {
                instance = SqlUtxoStore(context)
            }
            return instance
        }
    }
}

class SqlUtxoStore(context: Context) : UTXOStore(
    Database(AndroidSqliteDriver(Database.Schema, context, "common.db"))
)
