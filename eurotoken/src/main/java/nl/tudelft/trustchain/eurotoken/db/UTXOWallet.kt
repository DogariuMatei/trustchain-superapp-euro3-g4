package nl.tudelft.trustchain.eurotoken.db

import android.content.Context
import nl.tudelft.trustchain.eurotoken.entity.UTXO

class UTXOWallet private constructor() {

    private val walletMap: MutableMap<String, UTXO> = mutableMapOf()

    fun getOrCreateUTXO(pubKeyHex: String): UTXO {
        return walletMap.getOrPut(pubKeyHex) {
            UTXO()
        }
    }

    // called when online to reset offline tokens
    fun resetUTXO(pubKeyHex: String) {
        walletMap[pubKeyHex] = UTXO()
    }

    fun hasUTXO(pubKeyHex: String): Boolean {
        return walletMap.containsKey(pubKeyHex)
    }

    companion object {
        private lateinit var instance: UTXOWallet

        fun getInstance(): UTXOWallet {
            if (!::instance.isInitialized) {
                instance = UTXOWallet()
            }
            return instance
        }
    }
}
