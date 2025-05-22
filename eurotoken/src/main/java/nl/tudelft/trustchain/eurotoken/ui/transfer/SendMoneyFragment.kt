package nl.tudelft.trustchain.eurotoken.ui.transfer

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
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

class SendMoneyFragment : EurotokenNFCBaseFragment(R.layout.fragment_send_money) {
    private var addContact = false

    private val binding by viewBinding(FragmentSendMoneyBinding::bind)

    private val ownPublicKey by lazy {
        defaultCryptoProvider.keyFromPublicBin(
            transactionRepository.trustChainCommunity.myPeer.publicKey.keyToBin().toHex()
                .hexToBytes()
        )
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        val publicKey = requireArguments().getString(ARG_PUBLIC_KEY)!!
        val amount = requireArguments().getLong(ARG_AMOUNT)
        val name = requireArguments().getString(ARG_NAME)!!

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

        // Handle Send button - This will trigger Phase 2 NFC transaction
        binding.btnSend.setOnClickListener {
            handleSendTransaction(publicKey, amount, name)
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
     * Handle the send transaction process - Phase 2 NFC transaction
     */
    private fun handleSendTransaction(publicKey: String, amount: Long, contactName: String) {
        // Add contact if requested
        val newName = binding.newContactName.text.toString()
        if (addContact && newName.isNotEmpty()) {
            val key = defaultCryptoProvider.keyFromPublicBin(publicKey.hexToBytes())
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

        if (currentBalance < amount) {
            Toast.makeText(
                requireContext(),
                "Insufficient balance",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Create the transaction data for Phase 2
        createAndSendTransaction(publicKey, amount, contactName)
    }

    /**
     * Create transaction data and initiate NFC transmission - Phase 2
     */
    private fun createAndSendTransaction(recipientPublicKey: String, amount: Long, contactName: String) {
        try {
            // Create offline transaction
            val success = transactionRepository.sendTransferProposal(recipientPublicKey.hexToBytes(), amount)

            if (!success) {
                Toast.makeText(
                    requireContext(),
                    "Failed to create transaction",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            // Create payment confirmation data for NFC transmission
            val myPeer = transactionRepository.trustChainCommunity.myPeer
            val senderContact = ContactStore.getInstance(requireContext()).getContactFromPublicKey(myPeer.publicKey)

            val paymentConfirmation = JSONObject()
            paymentConfirmation.put("type", "payment_confirmation")
            paymentConfirmation.put("sender_public_key", myPeer.publicKey.keyToBin().toHex())
            paymentConfirmation.put("sender_name", senderContact?.name ?: "")
            paymentConfirmation.put("recipient_public_key", recipientPublicKey)
            paymentConfirmation.put("amount", amount)
            paymentConfirmation.put("timestamp", System.currentTimeMillis())
            // TODO: Add actual transaction block data in Phase 4

            // Initiate NFC transmission
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
            logger.error { "Error creating transaction: ${e.message}" }
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
