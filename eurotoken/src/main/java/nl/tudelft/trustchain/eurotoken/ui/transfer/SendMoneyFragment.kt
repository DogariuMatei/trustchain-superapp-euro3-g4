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
import nl.tudelft.trustchain.eurotoken.ui.EurotokenBaseFragment
import nl.tudelft.trustchain.eurotoken.community.EuroTokenCommunity

class SendMoneyFragment : EurotokenBaseFragment(R.layout.fragment_send_money) {
    private var addContact = false

    private val binding by viewBinding(FragmentSendMoneyBinding::bind)

    private val ownPublicKey by lazy {
        defaultCryptoProvider.keyFromPublicBin(
            transactionRepository.trustChainCommunity.myPeer.publicKey.keyToBin().toHex()
                .hexToBytes()
        )
    }

    @JvmName("getEuroTokenCommunity1")
    private fun getEuroTokenCommunity(): EuroTokenCommunity {
        return getIpv8().getOverlay()
            ?: throw java.lang.IllegalStateException("EuroTokenCommunity is not configured")
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

        val trustScore = trustStore.getScore(publicKey.hexToBytes())
        logger.info { "Trustscore: $trustScore" }

        if (trustScore != null) {
            if (trustScore >= TRUSTSCORE_AVERAGE_BOUNDARY) {
                binding.trustScoreWarning.text =
                    getString(R.string.send_money_trustscore_warning_high, trustScore.toInt())
                binding.trustScoreWarning.setBackgroundColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.android_green
                    )
                )
            } else if (trustScore > TRUSTSCORE_LOW_BOUNDARY) {
                binding.trustScoreWarning.text =
                    getString(R.string.send_money_trustscore_warning_average, trustScore.toInt())
                binding.trustScoreWarning.setBackgroundColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.metallic_gold
                    )
                )
            } else {
                binding.trustScoreWarning.text =
                    getString(R.string.send_money_trustscore_warning_low, trustScore.toInt())
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

        // Set up the Accept button
        binding.btnAccept.setOnClickListener {
            val newName = binding.newContactName.text.toString()
            if (addContact && newName.isNotEmpty()) {
                ContactStore.getInstance(requireContext())
                    .addContact(key, newName)
            }

            val success = transactionRepository.sendTransferProposal(publicKey.hexToBytes(), amount)
            if (!success) {
                Toast.makeText(
                    requireContext(),
                    "Insufficient balance",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }


            try {
                val euroTokenCommunity = getEuroTokenCommunity()
                val peer = euroTokenCommunity.getPeers().find {
                    it.publicKey.keyToBin().contentEquals(publicKey.hexToBytes())
                }

                if (peer != null) {
                    euroTokenCommunity.sendAddressesOfLastTransactions(peer)
                }
            } catch (e: Exception) {
                logger.error { "Failed to send trust data: ${e.message}" }
            }

            Toast.makeText(
                requireContext(),
                "Payment accepted and sent!",
                Toast.LENGTH_SHORT
            ).show()

            findNavController().navigate(R.id.action_sendMoneyFragment_to_transactionsFragment)
        }


        binding.btnDeny.setOnClickListener {
            Toast.makeText(
                requireContext(),
                "Payment request denied",
                Toast.LENGTH_SHORT
            ).show()

            // Try to notify the requester that the payment was declined
            try {
                val euroTokenCommunity = getEuroTokenCommunity()
                val peer = euroTokenCommunity.getPeers().find {
                    it.publicKey.keyToBin().contentEquals(publicKey.hexToBytes())
                }

                if (peer != null) {
                    // This is optional - would require a separate message type for declined payments
                    // euroTokenCommunity.sendPaymentDeclined(peer)
                }
            } catch (e: Exception) {
                logger.error { "Failed to send payment declined notification: ${e.message}" }
            }

            findNavController().navigate(R.id.action_sendMoneyFragment_to_transactionsFragment)
        }
    }

    companion object {
        const val ARG_AMOUNT = "amount"
        const val ARG_PUBLIC_KEY = "pubkey"
        const val ARG_NAME = "name"
        const val TRUSTSCORE_AVERAGE_BOUNDARY = 70
        const val TRUSTSCORE_LOW_BOUNDARY = 30
    }
}
