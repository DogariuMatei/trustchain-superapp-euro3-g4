package nl.tudelft.trustchain.common.eurotoken

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import nl.tudelft.common.sqldelight.Database
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.ipv8.util.hexToBytes

open class UTXOStore(val database: Database) {

    private val utxoMapper = {
            txId: ByteArray,
            txIndex: Long,
            amount: Long,
            owner: ByteArray,
            spentInTxId: ByteArray?
        ->
        UTXO(txId.toHex(), txIndex.toInt(), amount.toInt(), owner, spentInTxId?.toHex())
    }

    private val utxoTransactionMapper = {
            txId: ByteArray,
            sender: ByteArray,
            recipient: ByteArray
        ->
        UTXOTransaction(txId.toHex(), sender, recipient)
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

    fun updateSpentUtxo(txId: String, txIndex: Int, spentInTxId: String) {
        database.dbUtxoQueries.updateSpentUtxo(
            spentInTxId.hexToBytes(),
            txId.hexToBytes(),
            txIndex.toLong(),
        )
    }

    fun getUtxoTransactionsByParticipation(owner: ByteArray): List<UTXOTransaction> {
        return database.dbUtxoQueries.getUtxoTransactionsByParticipation(owner, owner, utxoTransactionMapper).executeAsList()
    }

    fun getUtxosById(txId: String): List<UTXO> {
        return database.dbUtxoQueries.getUtxosById(txId.hexToBytes(), utxoMapper).executeAsList()
    }

    fun getUtxo(txId: String, txIndex: Int): UTXO? {
        return database.dbUtxoQueries.getUtxo(txId.hexToBytes(), txIndex.toLong(), utxoMapper).executeAsOneOrNull()
    }

    fun addUtxo(utxo: UTXO) {
        database.dbUtxoQueries.addUtxo(utxo.txId.hexToBytes(), utxo.txIndex.toLong(), utxo.amount.toLong(), utxo.owner, utxo.spentInTxId?.hexToBytes())
    }

    fun removeUtxo(txId: String, txIndex: Int) {
        database.dbUtxoQueries.removeUtxo(txId.hexToBytes(), txIndex.toLong())
    }

    fun querySpentUtxos(): List<UTXO> {
        return database.dbUtxoQueries.querySpentUtxos(utxoMapper).executeAsList()
    }

    /**
     * Queries related to UTXO Transactions.
     */

    fun addUTXOTransaction(utxoTransaction: UTXOTransaction, maxRetries: Int = 3): Boolean {
        var attempt = 0
        var success = false
        while (!success && attempt < maxRetries) {
            try {
                database.transaction {
                    database.dbUtxoQueries.addUTXOTransaction(utxoTransaction.txId.hexToBytes(), utxoTransaction.sender, utxoTransaction.recipient)

                    for (input in utxoTransaction.inputs) {
                        if (getUtxo(input.txId, input.txIndex) != null) {
                            updateSpentUtxo(input.txId, input.txIndex, utxoTransaction.txId)
                        } else {
                            addUtxo(input.copy(spentInTxId = utxoTransaction.txId))
                        }
                    }

                    for (output in utxoTransaction.outputs) {
                        addUtxo(output)
                    }
                }
                success = true
            }
            catch (e: Exception) {
                attempt++
                if (attempt >= maxRetries) {
                    return false
                }
            }
        }
        return true
    }

    /**
     * Initialize the database.
     */
    fun createUtxoTables() {
        database.dbUtxoQueries.createUtxoTable()
        database.dbUtxoQueries.createUtxoTransactionTable()
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

class SqlUtxoStore(driver: SqlDriver) : UTXOStore(
    Database(driver)
) {
    constructor(context: Context) : this(
        AndroidSqliteDriver(Database.Schema, context, "common.db")
    )
}
