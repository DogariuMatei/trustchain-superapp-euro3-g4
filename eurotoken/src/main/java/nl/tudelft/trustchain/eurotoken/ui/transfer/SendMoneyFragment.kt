package nl.tudelft.trustchain.eurotoken.ui.transfer

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.findNavController
import nl.tudelft.ipv8.keyvault.defaultCryptoProvider
import nl.tudelft.ipv8.util.hexToBytes
import nl.tudelft.ipv8.util.toHex
import nl.tudelft.trustchain.common.contacts.ContactStore
import nl.tudelft.trustchain.common.eurotoken.TransactionRepository
import nl.tudelft.trustchain.common.util.viewBinding
import nl.tudelft.trustchain.eurotoken.EuroTokenMainActivity
import nl.tudelft.trustchain.eurotoken.R
import nl.tudelft.trustchain.eurotoken.databinding.FragmentSendMoneyBinding
import nl.tudelft.trustchain.eurotoken.ui.EurotokenNFCBaseFragment
import org.json.JSONObject

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class SendMoneyFragment : EurotokenNFCBaseFragment(R.layout.fragment_send_money) {
    private var addContact = false

    private val binding by viewBinding(FragmentSendMoneyBinding::bind)

    private val ownPublicKey by lazy {
        defaultCryptoProvider.keyFromPublicBin(
            transactionRepository.trustChainCommunity.myPeer.publicKey.keyToBin().toHex()
                .hexToBytes()
        )
    }

    // Store transaction parameters for Phase 2 execution
    private lateinit var transactionParams: TransactionParams

    private data class TransactionParams(
        val recipientPublicKey: String,
        val amount: Long,
        val contactName: String
    )

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        val publicKey = requireArguments().getString(ARG_PUBLIC_KEY)!!
        val amount = requireArguments().getLong(ARG_AMOUNT)
        val name = requireArguments().getString(ARG_NAME)!!

        // Store transaction parameters
        transactionParams = TransactionParams(publicKey, amount, name)

        val key = defaultCryptoProvider.keyFromPublicBin(publicKey.hexToBytes())
        val contact = ContactStore.getInstance(view.context).getContactFromPublicKey(key)
        binding.txtContactName.text = contact?.name ?: name

        binding.newContactName.visibility = View.GONE

        if (name.isNotEmpty()) {
            binding.newContactName.setText(name)
        }

        if (contact == null) {
            binding.addContactSwitch.toggle()
            addContact = true
            binding.newContactName.visibility = View.VISIBLE
            binding.newContactName.setText(name)
        } else {
            binding.addContactSwitch.visibility = View.GONE
            binding.newContactName.visibility = View.GONE
        }

        binding.addContactSwitch.setOnClickListener {
            addContact = !addContact
            if (addContact) {
                binding.newContactName.visibility = View.VISIBLE
            } else {
                binding.newContactName.visibility = View.GONE
            }
        }

        val pref =
            requireContext().getSharedPreferences(
                EuroTokenMainActivity.EurotokenPreferences.EUROTOKEN_SHARED_PREF_NAME,
                Context.MODE_PRIVATE
            )
        val demoModeEnabled =
            pref.getBoolean(
                EuroTokenMainActivity.EurotokenPreferences.DEMO_MODE_ENABLED,
                false
            )

        if (demoModeEnabled) {
            binding.txtBalance.text =
                TransactionRepository.prettyAmount(transactionRepository.getMyBalance())
        } else {
            binding.txtBalance.text =
                TransactionRepository.prettyAmount(transactionRepository.getMyVerifiedBalance())
        }
        binding.txtOwnPublicKey.text = ownPublicKey.toString()
        binding.txtAmount.text = TransactionRepository.prettyAmount(amount)
        binding.txtContactPublicKey.text = publicKey

        // Display trust score information
        displayTrustScore(publicKey)

        // Handle Send button - This prepares Phase 2 NFC transaction (NO blockchain transaction yet)
        binding.btnSend.setOnClickListener {
            initiatePhase2Transaction()
        }
    }

    /**
     * Display trust score information for the recipient
     */
    private fun displayTrustScore(publicKey: String) {
        val trustScore = trustStore.getScore(publicKey.toByteArray())
        logger.info { "Trustscore: $trustScore" }

        if (trustScore != null) {
            if (trustScore >= TRUSTSCORE_AVERAGE_BOUNDARY) {
                binding.trustScoreWarning.text =
                    getString(R.string.send_money_trustscore_warning_high, trustScore)
                binding.trustScoreWarning.setBackgroundColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.android_green
                    )
                )
            } else if (trustScore > TRUSTSCORE_LOW_BOUNDARY) {
                binding.trustScoreWarning.text =
                    getString(R.string.send_money_trustscore_warning_average, trustScore)
                binding.trustScoreWarning.setBackgroundColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.metallic_gold
                    )
                )
            } else {
                binding.trustScoreWarning.text =
                    getString(R.string.send_money_trustscore_warning_low, trustScore)
                binding.trustScoreWarning.setBackgroundColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.red
                    )
                )
            }
        } else {
            binding.trustScoreWarning.text =
                getString(R.string.send_money_trustscore_warning_no_score)
            binding.trustScoreWarning.setBackgroundColor(
                ContextCompat.getColor(
                    requireContext(),
                    R.color.metallic_gold
                )
            )
            binding.trustScoreWarning.visibility = View.VISIBLE
        }
    }

    /**
     * Initiate Phase 2 NFC transaction - Only prepare data, don't create blockchain transaction yet
     */
    private fun initiatePhase2Transaction() {
        // Add contact if requested
        val newName = binding.newContactName.text.toString()
        if (addContact && newName.isNotEmpty()) {
            val key = defaultCryptoProvider.keyFromPublicBin(transactionParams.recipientPublicKey.hexToBytes())
            ContactStore.getInstance(requireContext())
                .addContact(key, newName)
        }

        // Check if user has sufficient balance
        val pref = requireContext().getSharedPreferences(
            EuroTokenMainActivity.EurotokenPreferences.EUROTOKEN_SHARED_PREF_NAME,
            Context.MODE_PRIVATE
        )
        val demoModeEnabled = pref.getBoolean(
            EuroTokenMainActivity.EurotokenPreferences.DEMO_MODE_ENABLED,
            false
        )

        val currentBalance = if (demoModeEnabled) {
            transactionRepository.getMyBalance()
        } else {
            transactionRepository.getMyVerifiedBalance()
        }

        if (currentBalance < transactionParams.amount) {
            Toast.makeText(
                requireContext(),
                "Insufficient balance",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Create Phase 2 data and initiate NFC (NO blockchain transaction yet)
        prepareAndSendPhase2Data()
    }

    /**
     * Create actual transaction and send via NFC - Phase 2 (OFFLINE)
     */
    private fun prepareAndSendPhase2Data() {
        try {
            // Create the actual blockchain transaction BEFORE NFC transmission
            val transactionBlock = transactionRepository.sendTransferProposalSync(
                transactionParams.recipientPublicKey.hexToBytes(),
                transactionParams.amount
            )

            if (transactionBlock == null) {
                Toast.makeText(
                    requireContext(),
                    "Failed to create transaction",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            // Create payment confirmation with ACTUAL transaction data for offline processing
            val myPeer = transactionRepository.trustChainCommunity.myPeer
            val senderContact = ContactStore.getInstance(requireContext()).getContactFromPublicKey(myPeer.publicKey)

            val paymentConfirmation = JSONObject()
            paymentConfirmation.put("type", "payment_confirmation")
            paymentConfirmation.put("sender_public_key", myPeer.publicKey.keyToBin().toHex())
            paymentConfirmation.put("sender_name", senderContact?.name ?: "")
            paymentConfirmation.put("recipient_public_key", transactionParams.recipientPublicKey)
            paymentConfirmation.put("amount", transactionParams.amount)
            paymentConfirmation.put("timestamp", System.currentTimeMillis())

            // Include actual transaction block data for offline processing
            paymentConfirmation.put("block_hash", transactionBlock.calculateHash().toHex())
            paymentConfirmation.put("sequence_number", transactionBlock.sequenceNumber)
            paymentConfirmation.put("block_timestamp", transactionBlock.timestamp.time)

            // Initiate NFC transmission with actual transaction data
            Toast.makeText(
                requireContext(),
                "Hold your phone near the recipient's phone to complete the transaction",
                Toast.LENGTH_LONG
            ).show()

            writeToNFC(paymentConfirmation.toString()) { success ->
                if (success) {
                    Toast.makeText(
                        requireContext(),
                        "Transaction sent successfully!",
                        Toast.LENGTH_LONG
                    ).show()

                    // Navigate to transaction history
                    findNavController().navigate(R.id.action_sendMoneyFragment_to_transactionsFragment)
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Failed to send transaction. Please try again.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

        } catch (e: Exception) {
            logger.error { "Error creating offline transaction: ${e.message}" }
            Toast.makeText(
                requireContext(),
                "Error creating transaction: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Handle incoming NFC data (should not receive data in this fragment during normal flow)
     */
    override fun onNFCDataReceived(jsonData: String) {
        Toast.makeText(requireContext(), "Unexpected data received", Toast.LENGTH_SHORT).show()
    }

    /**
     * Handle NFC errors
     */
    override fun onNFCReadError(error: String) {
        super.onNFCReadError(error)
        Toast.makeText(requireContext(), "NFC Error: $error", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ARG_AMOUNT = "amount"
        const val ARG_PUBLIC_KEY = "pubkey"
        const val ARG_NAME = "name"
        const val TRUSTSCORE_AVERAGE_BOUNDARY = 70
        const val TRUSTSCORE_LOW_BOUNDARY = 30
    }
}
