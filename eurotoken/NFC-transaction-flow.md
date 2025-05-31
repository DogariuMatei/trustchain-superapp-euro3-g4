# EuroToken NFC Transaction Flow Documentation

### Phase Overview

- **Phase 1**: Payment request and review phase (no blockchain operations)
- **Phase 2**: Actual transaction creation and confirmation (with blockchain operations)

## Detailed Transaction Flow

### Phase 1: Payment Request and Review

Phase 1 establishes the transaction parameters and allows both parties to review the proposed transfer before any operations occur.

#### Step 1: Request Initiation (TransferFragment)

**Location**: `TransferFragment.kt`

The transaction begins when a user (the "requester") enters an amount and clicks the "Request" button. This triggers the request button click listener in the `setupButtonListeners()` method.

The system validates that the requested amount is positive before proceeding to the payment request creation step.

#### Step 2: Payment Request Creation (TransferFragment)

**Function**: `initiatePaymentRequest(amount: Long)`

This function constructs the JSON payload that will be transmitted via NFC. The payment request contains all the information the sender needs to review the transaction.

The JSON structure includes the requester's identity, the requested amount, and a timestamp for transaction tracking purposes.

#### Step 3: Navigation to NFC Transmission Screen

**Function**: `navigateToNFCRequestScreen(paymentRequestData: String)`

The system uses Android's Navigation Component to transition from the main transfer screen to a dedicated NFC transmission interface.


#### Step 4: NFC Transmission Setup (RequestMoneyFragment)

**Location**: `RequestMoneyFragment.kt`

When the `RequestMoneyFragment` loads, it immediately begins preparing for NFC transmission through the `onViewCreated()` lifecycle method, which calls `startPhase1NFCTransmission()`.

This function provides clear user guidance and sets up success and failure handling for the NFC transmission.

#### Step 5: NFC Write Process (EurotokenNFCBaseFragment)

**Location**: `EurotokenNFCBaseFragment.kt`

The `writeToNFC()` method manages the user interface and coordinates with the activity-level NFC handling.

This design separates the UI management (fragment level) from the actual NFC hardware operations (activity level).

#### Step 6: Activity-Level NFC Handling (EuroTokenMainActivity)

**Location**: `EuroTokenMainActivity.kt`

The main activity handles the low-level NFC operations through the `setupNFCWrite()` method and responds to NFC intents via `handleNFCIntent()`.

When an NFC tag is detected, the `handleNFCIntent()` method processes the hardware event and either writes data to or reads data from the NFC tag.

#### Step 7: NFC Data Reception (Sender's Device)

**Prerequisites**: The sender must have activated NFC receive mode by clicking "Activate NFC" in their `TransferFragment`, which calls `activatePhase1Receive()`.

When the sender's device detects the NFC transmission, the same `handleNFCIntent()` process occurs, but instead of writing data, the system reads the JSON payload and passes it to the active fragment's `handleIncomingNFCIntent()` method.

#### Step 8: Payment Request Processing (TransferFragment - Sender Side)

**Function**: `onNFCDataReceived(jsonData: String)` → `handlePhase1PaymentRequest(receivedData: JSONObject)`

The sender's device processes the received payment request and extracts the transaction parameters.

```kotlin
private fun handlePhase1PaymentRequest(paymentRequest: JSONObject) {
    val amount = paymentRequest.optLong("amount", -1L)
    val publicKey = paymentRequest.optString("public_key")
    val requesterName = paymentRequest.optString("requester_name")

    if (amount <= 0 || publicKey.isEmpty()) {
        updateNFCState(NFCState.ERROR)
        Toast.makeText(requireContext(), "Invalid payment request data", Toast.LENGTH_LONG).show()
        return
    }

    updateNFCState(NFCState.SUCCESS)
    dismissNFCDialog()
    deactivateNFCReceive()

    // Navigate to transaction review screen
    val args = Bundle()
    args.putString(SendMoneyFragment.ARG_PUBLIC_KEY, publicKey)
    args.putLong(SendMoneyFragment.ARG_AMOUNT, amount)
    args.putString(SendMoneyFragment.ARG_NAME, requesterName)

    findNavController().navigate(
        R.id.action_transferFragment_to_sendMoneyFragment,
        args
    )
}
```

After successful validation, the system navigates to the transaction review screen where the sender can examine the details before proceeding.

### Phase 2: Transaction Execution and Confirmation

Phase 2 represents the commitment phase where the actual blockchain transaction is created and transmitted to the requester for final processing.

#### Step 9: Transaction Review (SendMoneyFragment)

**Location**: `SendMoneyFragment.kt`

The `SendMoneyFragment` provides a comprehensive review interface that displays transaction details, current balance, and trust score information. The `displayTrustScore()` method shows the sender how trustworthy the requester is based on previous interactions.

```kotlin
private fun displayTrustScore(publicKey: String) {
    val trustScore = trustStore.getScore(publicKey.toByteArray())
    
    if (trustScore != null) {
        if (trustScore >= TRUSTSCORE_AVERAGE_BOUNDARY) {
            binding.trustScoreWarning.text = 
                getString(R.string.send_money_trustscore_warning_high, trustScore)
            binding.trustScoreWarning.setBackgroundColor(
                ContextCompat.getColor(requireContext(), R.color.android_green)
            )
        } else if (trustScore > TRUSTSCORE_LOW_BOUNDARY) {
            // Average trust score warning
        } else {
            // Low trust score warning
        }
    }
}
```


#### Step 10: Transaction Creation and NFC Preparation (SendMoneyFragment)

**Function**: `btnSend.setOnClickListener` → `initiatePhase2Transaction()` → `prepareAndSendPhase2Data()`

This represents the most critical step in the entire process. The `prepareAndSendPhase2Data()` function creates the actual blockchain transaction before transmitting it via NFC.

```kotlin
private fun prepareAndSendPhase2Data() {
    try {
        // Create the actual blockchain transaction BEFORE NFC transmission
        val transactionBlock = transactionRepository.sendTransferProposalSync(
            transactionParams.recipientPublicKey.hexToBytes(),
            transactionParams.amount
        )

        if (transactionBlock == null) {
            Toast.makeText(requireContext(), "Failed to create transaction", Toast.LENGTH_LONG).show()
            return
        }

        // Create payment confirmation with ACTUAL transaction data
        val paymentConfirmation = JSONObject()
        paymentConfirmation.put("type", "payment_confirmation")
        paymentConfirmation.put("sender_public_key", myPeer.publicKey.keyToBin().toHex())
        paymentConfirmation.put("amount", transactionParams.amount)
        
        // Include actual blockchain data for offline processing
        paymentConfirmation.put("block_hash", transactionBlock.calculateHash().toHex())
        paymentConfirmation.put("sequence_number", transactionBlock.sequenceNumber)
        paymentConfirmation.put("block_timestamp", transactionBlock.timestamp.time)

        writeToNFC(paymentConfirmation.toString()) { success ->
            if (success) {
                // Navigate to transaction history
                findNavController().navigate(R.id.action_sendMoneyFragment_to_transactionsFragment)
            }
        }
    } catch (e: Exception) {
        logger.error { "Error creating offline transaction: ${e.message}" }
    }
}
```

#### Step 11: Blockchain Transaction Creation (TransactionRepository)

**Location**: `java/nl/tudelft/trustchain/common/eurotoken/TransactionRepository.kt`

The `sendTransferProposalSync()` method handles the actual blockchain operations.

This method validates the sender has sufficient balance, calculates the new balance after the transaction, and creates a TrustChain block that represents the transfer on the blockchain.

#### Step 12: Phase 2 NFC Transmission

The NFC writing process follows the same technical pattern as Phase 1, utilizing the same `writeToNFC()` infrastructure, but now transmits the payment confirmation containing actual blockchain transaction data.

#### Step 13: Phase 2 Activation (Back to the Requester's Device)

**Location**: `RequestMoneyFragment.kt` → `TransferFragment.kt`

After Phase 1 completes successfully, the requester sees a "Activate Phase 2" button. Clicking this button navigates back to `TransferFragment` with a special flag indicating that Phase 2 should be automatically activated.

```kotlin
// In RequestMoneyFragment
binding.btnContinue.setOnClickListener {
    if (isPhase1Complete) {
        val args = Bundle()
        args.putBoolean("activate_phase2", true)
        findNavController().navigate(R.id.transferFragment, args)
    }
}

// In TransferFragment.onViewCreated()
if (arguments?.getBoolean("activate_phase2") == true) {
    activatePhase2Receive()
    arguments?.remove("activate_phase2")
}
```

The `activatePhase2Receive()` method configures the fragment to receive transaction confirmations rather than payment requests.

#### Step 14: Receiving Transaction Confirmation (TransferFragment - Requester Side)

**Function**: `onNFCDataReceived()` → `handlePhase2PaymentConfirmation(paymentConfirmation: JSONObject)`

When the payment confirmation is received, the system processes the actual transaction data.

```kotlin
private fun handlePhase2PaymentConfirmation(paymentConfirmation: JSONObject) {
    try {
        val senderPublicKey = paymentConfirmation.optString("sender_public_key")
        val senderName = paymentConfirmation.optString("sender_name")
        val amount = paymentConfirmation.optLong("amount", -1L)
        val blockHash = paymentConfirmation.optString("block_hash")
        val sequenceNumber = paymentConfirmation.optLong("sequence_number", -1L)
        val blockTimestamp = paymentConfirmation.optLong("block_timestamp", -1L)

        // Validate required data
        if (senderPublicKey.isEmpty() || amount <= 0 || blockHash.isEmpty()) {
            updateNFCState(NFCState.ERROR)
            return
        }

        processOfflineTransaction(
            senderPublicKey = senderPublicKey,
            senderName = senderName,
            amount = amount,
            blockHash = blockHash,
            sequenceNumber = sequenceNumber,
            blockTimestamp = blockTimestamp
        )

        updateNFCState(NFCState.SUCCESS)
        findNavController().navigate(R.id.transactionsFragment)
    } catch (e: Exception) {
        logger.error { "Error processing payment confirmation: ${e.message}" }
    }
}
```

This function extracts both the transaction metadata and the blockchain-specific information needed for offline processing.

#### Step 15: Offline Transaction Processing (TransferFragment)

**Function**: `processOfflineTransaction()`

The final step completes the transaction from the requester's perspective by updating local data structures and building trust relationships.

```kotlin
private fun processOfflineTransaction(
    senderPublicKey: String,
    senderName: String,
    amount: Long,
    blockHash: String,
    sequenceNumber: Long,
    blockTimestamp: Long
) {
    try {
        val senderKeyBytes = senderPublicKey.hexToBytes()

        // Update trust score for sender
        trustStore.incrementTrust(senderKeyBytes)

        // Add contact if we have a name and don't already have this contact
        if (senderName.isNotEmpty()) {
            val senderKey = defaultCryptoProvider.keyFromPublicBin(senderKeyBytes)
            val existingContact = ContactStore.getInstance(requireContext()).getContactFromPublicKey(senderKey)
            if (existingContact == null) {
                ContactStore.getInstance(requireContext()).addContact(senderKey, senderName)
            }
        }

        logger.info { "Processed offline transaction: $amount from $senderPublicKey ($senderName)" }
    } catch (e: Exception) {
        logger.error { "Error in processOfflineTransaction: ${e.message}" }
        throw e
    }
}
```

This function handles the social aspects of the transaction system by building trust scores and maintaining contact relationships between users.

## Architecture Insights and Design Patterns

### State Management

The transaction system uses a clear state machine pattern with defined phases:

```kotlin
private enum class TransactionPhase {
    IDLE,           // No active transaction
    WAITING_PHASE1, // Waiting to receive payment request
    WAITING_PHASE2  // Waiting to receive payment confirmation
}
```

This makes the system behavior predictable and helps prevent race conditions or invalid state transitions.

## Side Notes

### NFC Data Format
- All NFC transmissions use JSON format with MIME type `application/json`.

### NFC Not Working
- Ensure both devices have NFC enabled in settings
- Verify the app has NFC permissions in AndroidManifest.xml

### Transaction Failures
- Verify sufficient balance before Phase 2
- Check that blockchain connectivity exists during transaction creation
- Ensure JSON data integrity during NFC transmission

