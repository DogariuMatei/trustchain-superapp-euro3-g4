package nl.tudelft.trustchain.eurotoken.ui.transfer

import android.util.Log
import android.content.Context
import android.content.Intent
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
import nl.tudelft.trustchain.eurotoken.nfc.HCEPaymentService
import nl.tudelft.trustchain.eurotoken.ui.EurotokenNFCBaseFragment
import org.json.JSONObject

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class SendMoneyFragment : EurotokenNFCBaseFragment(R.layout.fragment_send_money) {

    companion object {
        private const val TAG = "SendMoneyFragment"
        const val ARG_AMOUNT = "amount"
        const val ARG_PUBLIC_KEY = "pubkey"
        const val ARG_NAME = "name"
        const val TRUSTSCORE_AVERAGE_BOUNDARY = 70
        const val TRUSTSCORE_LOW_BOUNDARY = 30
    }

    @JvmName("getEuroTokenCommunity1")
    private fun getEuroTokenCommunity(): nl.tudelft.trustchain.eurotoken.community.EuroTokenCommunity {
        return getIpv8().getOverlay()
            ?: throw IllegalStateException("EuroTokenCommunity is not configured")
    }

    private val euroTokenCommunity by lazy {
        getEuroTokenCommunity()
    }

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
        Log.d(TAG, "=== SEND MONEY FRAGMENT VIEW CREATED ===")

        val publicKey = requireArguments().getString(ARG_PUBLIC_KEY)!!
        val amount = requireArguments().getLong(ARG_AMOUNT)
        val name = requireArguments().getString(ARG_NAME)!!

        Log.d(TAG, "Transaction parameters - Amount: $amount, Recipient: ${publicKey.take(20)}...")

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

        // Handle Send button - This prepares Phase 2 HCE transaction
        binding.btnSend.setOnClickListener {
            Log.d(TAG, "Send button clicked - initiating Phase 2 transaction")
            initiatePhase2Transaction()
        }
    }

    /**
     * Display trust score information for the recipient
     */
    private fun displayTrustScore(publicKey: String) {
        val trustScore = trustStore.getScore(publicKey.toByteArray())
        Log.d(TAG, "Trust score for recipient: $trustScore")

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
     * Initiate Phase 2 HCE transaction
     */
    private fun initiatePhase2Transaction() {
        Log.d(TAG, "=== INITIATE PHASE 2 TRANSACTION ===")

        // Add contact if requested
        val newName = binding.newContactName.text.toString()
        if (addContact && newName.isNotEmpty()) {
            val key = defaultCryptoProvider.keyFromPublicBin(transactionParams.recipientPublicKey.hexToBytes())
            ContactStore.getInstance(requireContext())
                .addContact(key, newName)
            Log.d(TAG, "Contact added: $newName")
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

        Log.d(TAG, "Current balance: $currentBalance, Required amount: ${transactionParams.amount}")

        if (currentBalance < transactionParams.amount) {
            Log.w(TAG, "Insufficient balance")
            Toast.makeText(
                requireContext(),
                "Insufficient balance",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Create Phase 2 data and initiate HCE transaction
        prepareAndSendPhase2Data()
    }

    /**
     * Create actual transaction and send via HCE - Phase 2
     */
    private fun prepareAndSendPhase2Data() {
        Log.d(TAG, "=== PREPARE AND SEND PHASE 2 DATA ===")

        try {
            // Create the actual blockchain transaction
            Log.d(TAG, "Creating blockchain transaction...")
            val transactionBlock = transactionRepository.sendTransferProposalSync(
                transactionParams.recipientPublicKey.hexToBytes(),
                transactionParams.amount
            )

            if (transactionBlock == null) {
                Log.e(TAG, "Failed to create transaction block")
                Toast.makeText(
                    requireContext(),
                    "Failed to create transaction",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            Log.d(TAG, "Transaction block created successfully")
            Log.d(TAG, "Block hash: ${transactionBlock.calculateHash().toHex()}")
            Log.d(TAG, "Sequence number: ${transactionBlock.sequenceNumber}")

            // Create payment confirmation with transaction data
            val myPeer = transactionRepository.trustChainCommunity.myPeer
            val senderContact = ContactStore.getInstance(requireContext()).getContactFromPublicKey(myPeer.publicKey)

            val paymentConfirmation = JSONObject()
            paymentConfirmation.put("type", "payment_confirmation")
            paymentConfirmation.put("sender_public_key", myPeer.publicKey.keyToBin().toHex())
            paymentConfirmation.put("sender_name", senderContact?.name ?: "")
            paymentConfirmation.put("recipient_public_key", transactionParams.recipientPublicKey)
            paymentConfirmation.put("amount", transactionParams.amount)
            paymentConfirmation.put("timestamp", System.currentTimeMillis())

            // Include actual transaction block data
            paymentConfirmation.put("block_hash", transactionBlock.calculateHash().toHex())
            paymentConfirmation.put("sequence_number", transactionBlock.sequenceNumber)
            paymentConfirmation.put("block_timestamp", transactionBlock.timestamp.time)

            Log.d(TAG, "Payment confirmation created: ${paymentConfirmation.toString(2)}")

            // Start HCE service explicitly
            requireContext().startService(Intent(requireContext(), HCEPaymentService::class.java))

            // Start HCE card emulation mode to send payment confirmation
            Toast.makeText(
                requireContext(),
                "Hold your phone near the recipient's phone to complete the transaction",
                Toast.LENGTH_LONG
            ).show()

            startHCECardEmulation(
                jsonData = paymentConfirmation.toString(),
                message = "Sending payment confirmation...",
                timeoutSeconds = 30,
                expectResponse = false,
                onSuccess = {
                    Log.d(TAG, "HCE card emulation started successfully")

                    // Send trust score data to recipient
                    euroTokenCommunity.sendAddressesOfLastTransactions(
                        transactionRepository.trustChainCommunity.getPeers().find {
                            it.publicKey.keyToBin().toHex() == transactionParams.recipientPublicKey
                        } ?: return@startHCECardEmulation
                    )

                    Toast.makeText(
                        requireContext(),
                        "Transaction sent successfully!",
                        Toast.LENGTH_LONG
                    ).show()

                    // Navigate to transaction history
                    findNavController().navigate(R.id.action_sendMoneyFragment_to_transactionsFragment)
                }
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error creating offline transaction: ${e.message}", e)
            Toast.makeText(
                requireContext(),
                "Error creating transaction: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Handle NFC errors
     */
    override fun onNFCReadError(error: String) {
        super.onNFCReadError(error)
        Log.e(TAG, "NFC Error: $error")
        Toast.makeText(requireContext(), "NFC Error: $error", Toast.LENGTH_SHORT).show()
    }

    override fun onNFCTimeout() {
        super.onNFCTimeout()
        Log.w(TAG, "NFC operation timed out")
        Toast.makeText(
            requireContext(),
            "Transaction timed out. Please ensure both phones are close together and try again.",
            Toast.LENGTH_LONG
        ).show()
    }
}
