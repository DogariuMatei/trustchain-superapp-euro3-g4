package nl.tudelft.trustchain.eurotoken.ui.transfer

import android.util.Log
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
import nl.tudelft.trustchain.eurotoken.databinding.FragmentReceiveMoneyBinding
import nl.tudelft.trustchain.eurotoken.ui.EurotokenBaseFragment
import org.json.JSONException
import org.json.JSONObject

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class ReceiveMoneyFragment : EurotokenBaseFragment(R.layout.fragment_receive_money) {

    companion object {
        private const val TAG = "ReceiveMoneyFragment"
        const val ARG_DATA = "data"
        const val TRUSTSCORE_AVERAGE_BOUNDARY = 70
        const val TRUSTSCORE_LOW_BOUNDARY = 30
    }

    private var addContact = false
    private val binding by viewBinding(FragmentReceiveMoneyBinding::bind)

    private val ownPublicKey by lazy {
        defaultCryptoProvider.keyFromPublicBin(
            transactionRepository.trustChainCommunity.myPeer.publicKey.keyToBin().toHex()
                .hexToBytes()
        )
    }

    // Store sender information for Phase 2
    private lateinit var senderInfo: SenderInfo

    private data class SenderInfo(
        val senderPublicKey: String,
        val senderName: String,
        val amount: Long,
        val senderBalance: Long,
        val recentCounterparties: List<String>
    )

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "=== RECEIVE MONEY FRAGMENT VIEW CREATED ===")

        val senderDataJson = requireArguments().getString(ARG_DATA)!!
        Log.d(TAG, "Received sender data: ${senderDataJson.take(100)}...")

        try {
            parseSenderData(senderDataJson)
            setupUI()
            displayTrustScore()
        } catch (e: JSONException) {
            Log.e(TAG, "Error parsing sender data: ${e.message}")
            Toast.makeText(requireContext(), "Invalid sender data", Toast.LENGTH_LONG).show()
            findNavController().popBackStack()
        }
    }

    /**
     * Parse sender data from JSON
     */
    private fun parseSenderData(jsonData: String) {
        val senderData = JSONObject(jsonData)

        val senderPublicKey = senderData.optString("sender_public_key")
        val senderName = senderData.optString("sender_name")
        val amount = senderData.optLong("amount", -1L)
        val senderBalance = senderData.optLong("sender_balance", -1L)
        val counterpartiesStr = senderData.optString("recent_counterparties", "")
        val recentCounterparties = if (counterpartiesStr.isNotEmpty()) {
            counterpartiesStr.split(",")
        } else {
            emptyList()
        }

        if (senderPublicKey.isEmpty() || amount <= 0) {
            throw JSONException("Invalid sender data: missing required fields")
        }

        senderInfo = SenderInfo(senderPublicKey, senderName, amount, senderBalance, recentCounterparties)
        Log.d(TAG, "Parsed sender info - Amount: ${senderInfo.amount}, From: ${senderInfo.senderName}")

        // Update trust scores based on received counterparties
        updateTrustScores(recentCounterparties)
    }

    /**
     * Update trust scores based on sender's recent counterparties
     */
    private fun updateTrustScores(counterparties: List<String>) {
        Log.d(TAG, "Updating trust scores for ${counterparties.size} counterparties")
        counterparties.forEach { publicKeyHex ->
            try {
                val publicKeyBytes = publicKeyHex.hexToBytes()
                trustStore.incrementTrust(publicKeyBytes)
                Log.d(TAG, "Incremented trust for: ${publicKeyHex.take(10)}...")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update trust for: ${publicKeyHex.take(10)}...")
            }
        }
    }

    /**
     * Setup UI with sender information
     */
    private fun setupUI() {
        val key = defaultCryptoProvider.keyFromPublicBin(senderInfo.senderPublicKey.hexToBytes())
        val contact = ContactStore.getInstance(requireContext()).getContactFromPublicKey(key)

        binding.txtContactName.text = contact?.name ?: senderInfo.senderName.ifEmpty { "Unknown" }
        binding.txtContactPublicKey.text = senderInfo.senderPublicKey
        binding.txtAmount.text = TransactionRepository.prettyAmount(senderInfo.amount)

        // Setup contact saving
        binding.newContactName.visibility = View.GONE

        if (senderInfo.senderName.isNotEmpty()) {
            binding.newContactName.setText(senderInfo.senderName)
        }

        if (contact == null) {
            binding.addContactSwitch.toggle()
            addContact = true
            binding.newContactName.visibility = View.VISIBLE
            binding.newContactName.setText(senderInfo.senderName)
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

        // Display current balance
        val pref = requireContext().getSharedPreferences(
            EuroTokenMainActivity.EurotokenPreferences.EUROTOKEN_SHARED_PREF_NAME,
            Context.MODE_PRIVATE
        )
        val demoModeEnabled = pref.getBoolean(
            EuroTokenMainActivity.EurotokenPreferences.DEMO_MODE_ENABLED,
            false
        )

        val balance = if (demoModeEnabled) {
            transactionRepository.getMyBalance()
        } else {
            transactionRepository.getMyVerifiedBalance()
        }

        binding.txtBalance.text = TransactionRepository.prettyAmount(balance)
        binding.txtOwnPublicKey.text = ownPublicKey.toString()

        // Setup trust button
        binding.btnTrustSender.setOnClickListener {
            Log.d(TAG, "Trust Sender button clicked")
            proceedWithTransaction()
        }
    }

    /**
     * Display trust score information for the sender
     */
    private fun displayTrustScore() {
        val trustScore = trustStore.getScore(senderInfo.senderPublicKey.toByteArray())
        Log.d(TAG, "Trust score for sender: $trustScore")

        if (trustScore != null) {
            if (trustScore >= TRUSTSCORE_AVERAGE_BOUNDARY) {
                binding.trustScoreWarning.text =
                    getString(R.string.send_money_trustscore_warning_high, trustScore)
                binding.trustScoreWarning.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.android_green)
                )
            } else if (trustScore > TRUSTSCORE_LOW_BOUNDARY) {
                binding.trustScoreWarning.text =
                    getString(R.string.send_money_trustscore_warning_average, trustScore)
                binding.trustScoreWarning.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.metallic_gold)
                )
            } else {
                binding.trustScoreWarning.text =
                    getString(R.string.send_money_trustscore_warning_low, trustScore)
                binding.trustScoreWarning.setBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.red)
                )
            }
        } else {
            binding.trustScoreWarning.text =
                getString(R.string.send_money_trustscore_warning_no_score)
            binding.trustScoreWarning.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.metallic_gold)
            )
        }
        binding.trustScoreWarning.visibility = View.VISIBLE
    }

    /**
     * Proceed with transaction - save contact and navigate back for Phase 2
     */
    private fun proceedWithTransaction() {
        Log.d(TAG, "=== PROCEED WITH TRANSACTION ===")

        // Add contact if requested
        val newName = binding.newContactName.text.toString()
        if (addContact && newName.isNotEmpty()) {
            val key = defaultCryptoProvider.keyFromPublicBin(senderInfo.senderPublicKey.hexToBytes())
            ContactStore.getInstance(requireContext()).addContact(key, newName)
            Log.d(TAG, "Contact added: $newName")
        }

        Toast.makeText(
            requireContext(),
            "Ready for Phase 2. Return to Balance screen and activate NFC to receive payment.",
            Toast.LENGTH_LONG
        ).show()

        // Navigate back to transfer fragment with Phase 2 ready flag
        val args = Bundle()
        args.putBoolean("phase2_ready", true)
        args.putString("expected_sender", senderInfo.senderPublicKey)
        args.putLong("expected_amount", senderInfo.amount)
        findNavController().navigate(R.id.transferFragment, args)
    }
}
