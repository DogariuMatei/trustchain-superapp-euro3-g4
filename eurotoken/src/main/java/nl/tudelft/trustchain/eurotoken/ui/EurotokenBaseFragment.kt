package nl.tudelft.trustchain.eurotoken.ui

import android.util.Log
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import mu.KotlinLogging
import nl.tudelft.ipv8.attestation.trustchain.BlockListener
import nl.tudelft.ipv8.attestation.trustchain.TrustChainBlock
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.contacts.ContactStore
import nl.tudelft.trustchain.common.eurotoken.GatewayStore
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import nl.tudelft.trustchain.common.eurotoken.UTXO
import nl.tudelft.trustchain.common.eurotoken.UTXOService
import nl.tudelft.trustchain.common.ui.BaseFragment
import nl.tudelft.trustchain.eurotoken.EuroTokenMainActivity
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.db.TrustStore
import nl.tudelft.trustchain.common.eurotoken.UTXOStore
import nl.tudelft.trustchain.common.eurotoken.UTXOTransaction

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
open class EurotokenBaseFragment(contentLayoutId: Int = 0) : BaseFragment(contentLayoutId) {
    protected val logger = KotlinLogging.logger {}

    /**
     * The [TrustStore] to retrieve trust scores from.
     */
    protected val trustStore by lazy {
        TrustStore.getInstance(requireContext())
    }

    protected val utxoStore by lazy {
        UTXOStore.getInstance(requireContext())
    }

    protected val utxoService by lazy {
        UTXOService(getIpv8().getOverlay()!!, utxoStore)
    }

    protected val transactionRepository by lazy {
        TransactionRepository(getIpv8().getOverlay()!!, gatewayStore)
    }

    private val contactStore by lazy {
        ContactStore.getInstance(requireContext())
    }

    private val gatewayStore by lazy {
        GatewayStore.getInstance(requireContext())
    }

    private val onReceiveListener =
        object : BlockListener {
            override fun onBlockReceived(block: TrustChainBlock) {
                if (block.isAgreement &&
                    block.publicKey.contentEquals(
                        transactionRepository.trustChainCommunity.myPeer.publicKey.keyToBin()
                    )
                ) {
                    playMoneySound()
                    makeMoneyToast()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        setHasOptionsMenu(true)
        trustStore.createContactStateTable()
        utxoStore.createUtxoTables()

        lifecycleScope.launchWhenResumed {
        }
    }

    fun makeMoneyToast() {
        Toast.makeText(requireContext(), "Money received!", Toast.LENGTH_LONG).show()
    }

    fun playMoneySound() {
        val afd = activity?.assets?.openFd("Coins.mp3") ?: return
        val player = MediaPlayer()
        player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
        player.prepare()
        player.start()
    }

    override fun onResume() {
        transactionRepository.trustChainCommunity.addListener(
            TransactionRepository.BLOCK_TYPE_TRANSFER,
            onReceiveListener
        )
        transactionRepository.trustChainCommunity.addListener(
            TransactionRepository.BLOCK_TYPE_CREATE,
            onReceiveListener
        )
        super.onResume()
    }

    override fun onPause() {
        transactionRepository.trustChainCommunity.removeListener(
            onReceiveListener,
            TransactionRepository.BLOCK_TYPE_TRANSFER
        )
        transactionRepository.trustChainCommunity.removeListener(
            onReceiveListener,
            TransactionRepository.BLOCK_TYPE_CREATE
        )
        super.onPause()
    }

    override fun onCreateOptionsMenu(
        menu: Menu,
        inflater: MenuInflater
    ) {
        inflater.inflate(R.menu.eurotoken_options, menu)
        menu.findItem(R.id.toggleDemoMode).setTitle(getDemoModeMenuItemText())

        val sharedPreferences =
            requireContext().getSharedPreferences(
                EuroTokenMainActivity.EurotokenPreferences.EUROTOKEN_SHARED_PREF_NAME,
                Context.MODE_PRIVATE
            )
        val genesisCreated = sharedPreferences.getBoolean(
            EuroTokenMainActivity.EurotokenPreferences.GENESIS_UTXO_CREATED, false)

        val item = menu.findItem(R.id.createGenesisUtxo)
        item.setTitle(getString(R.string.create_genesis_utxo))
        item.isEnabled = !genesisCreated
    }

    private fun createGenesisUTXO() {
        val success = utxoService.createGenesisUTXO()
        if(!success) {
            Log.e("GENESIS", "Failed to add genesis transaction")
            Toast.makeText(requireContext(), "Failed to add genesis transaction", Toast.LENGTH_LONG).show()
        } else {
            Log.e("GENESIS", "Genesis transaction added successfully")
            Toast.makeText(requireContext(), "Genesis transaction added successfully", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Get the text for the demo mode menu item.
     */
    private fun getDemoModeMenuItemText(): String {
        val pref =
            requireContext().getSharedPreferences(
                EuroTokenMainActivity.EurotokenPreferences.EUROTOKEN_SHARED_PREF_NAME,
                Context.MODE_PRIVATE
            )
        val demoModeEnabled =
            pref.getBoolean(EuroTokenMainActivity.EurotokenPreferences.DEMO_MODE_ENABLED, false)
        if (demoModeEnabled) {
            TransactionRepository.initialBalance = 1000
        } else {
            TransactionRepository.initialBalance = 0
        }
        return getString(R.string.toggle_demo_mode, if (demoModeEnabled) "OFF" else "ON")
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val myPublicKey = getIpv8().myPeer.publicKey.keyToBin().toHex()
        @Suppress("DEPRECATION")
        return when (item.itemId) {
            R.id.verifyBalance -> {
                val gateway = transactionRepository.getGatewayPeer()
                if (gateway == null) {
                    Toast.makeText(requireContext(), "No preferred gateway set", Toast.LENGTH_SHORT)
                        .show()
                } else {
                    transactionRepository.sendCheckpointProposal(gateway)
                    Toast.makeText(requireContext(), "CHECKPOINT", Toast.LENGTH_SHORT).show()
                }
                true
            }

            R.id.copyKey -> {
                val clipboard =
                    ContextCompat.getSystemService(requireContext(), ClipboardManager::class.java)
                val clip = ClipData.newPlainText("Public Key", myPublicKey)
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
                true
            }

            R.id.renameSelf -> {
                renameSelf()
                true
            }

            R.id.gateways -> {
                findNavController().navigate(R.id.gatewaysFragment)
                true
            }

            R.id.toggleDemoMode -> {
                val sharedPreferences =
                    requireContext().getSharedPreferences(
                        EuroTokenMainActivity.EurotokenPreferences.EUROTOKEN_SHARED_PREF_NAME,
                        Context.MODE_PRIVATE
                    )
                val edit = sharedPreferences.edit()
                edit.putBoolean(
                    EuroTokenMainActivity.EurotokenPreferences.DEMO_MODE_ENABLED,
                    !sharedPreferences.getBoolean(
                        EuroTokenMainActivity.EurotokenPreferences.DEMO_MODE_ENABLED,
                        false
                    )
                )
                edit.commit()

                item.setTitle(getDemoModeMenuItemText())
                true
            }

            R.id.createGenesisUtxo -> {
                val sharedPreferences =
                    requireContext().getSharedPreferences(
                        EuroTokenMainActivity.EurotokenPreferences.EUROTOKEN_SHARED_PREF_NAME,
                        Context.MODE_PRIVATE
                    )
                val genesisCreated = sharedPreferences.getBoolean(
                    EuroTokenMainActivity.EurotokenPreferences.GENESIS_UTXO_CREATED, false)
                if (genesisCreated) {
                    Toast.makeText(requireContext(), "Genesis UTXO already created", Toast.LENGTH_SHORT).show()
                    return true
                }

                // Create a genesis UTXO for demo mode
                val edit = sharedPreferences.edit()
                edit.putBoolean(
                    EuroTokenMainActivity.EurotokenPreferences.GENESIS_UTXO_CREATED,
                    !sharedPreferences.getBoolean(
                        EuroTokenMainActivity.EurotokenPreferences.GENESIS_UTXO_CREATED,
                        false
                    )
                )
                edit.commit()

                createGenesisUTXO()
                requireActivity().invalidateOptionsMenu()
                true
            }

            R.id.trustScoresMenuItem -> {
                findNavController().navigate(R.id.trustScoresFragment)
                true
            }

            R.id.utxoTransactionsMenuItem -> {
                findNavController().navigate(R.id.utxoTransactionsFragment)
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun renameSelf() {
        val myKey = getIpv8().myPeer.publicKey

        val contact = contactStore.getContactFromPublicKey(myKey)
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Rename Contact")

        // Set up the input
        val input = EditText(requireContext())
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setText(contact?.name ?: "")
        builder.setView(input)

        // Set up the buttons
        builder.setPositiveButton(
            "Rename"
        ) { _, _ ->
            val ans = input.text.toString()
            if (ans == "") {
                if (contact != null) {
                    contactStore.deleteContact(contact)
                }
            } else {
                contactStore.updateContact(myKey, input.text.toString())
            }
        }
        builder.setNegativeButton(
            "Cancel"
        ) { dialog, _ -> dialog.cancel() }

        builder.show()
    }
}
