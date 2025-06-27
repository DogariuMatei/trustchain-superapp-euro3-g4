# EuroToken - A Digital Euro
EU member states of the Eurozone [are considering creating a blockchain-inspired digital Eurotoken using a P2P architecture](https://haldus.eestipank.ee/sites/default/files/2021-07/Work%20stream%203%20-%20A%20New%20Solution%20-%20Blockchain%20and%20eID_1.pdf).
The superapp contains a fully functional digital cash implementation, still experimental and tested within 1 retail location for 1 morning in live production usage with real financial transactions.

## Web-of-Trust
In standard operation, EuroToken communicates with a gateway controlled by authorities to verify the authenticity of transactions.
However, EuroToken needs to be able to function in case of world-scale disasters and system outages, and thus in case of a gateway not being available for an extended period of time.
Even more, EuroToken is also built such that it works in remote locations without a large peer-to-peer network.
Not having a large network to confirm transactions with comes at the cost of induced risk, primarily due to the double spending problem.

### Double spending
Double spending is a problem that occurs when someone spends the same amount of money more than once.
This problem is very prevalent with digital offline-capable currencies.

The problem of double spending arises because digital currencies are not regulated by a central authority.
This means that there is no one to verify that a transaction is valid.
If no central authority is available to verify the validity of a transaction, then it is possible for someone to spend the same amount of money more than once.

Double spending is a major problem for digital currencies, as it undermines their trustworthiness.
If people cannot trust that a digital currency is not susceptible to double spending, then they are less likely to use it.

Double spending in EuroToken occurs when a malicious user sends a transaction to a wallet, and then sends the same transaction to another wallet whilst the second receiver is not aware of the first transaction.

#### Requirements for solution
To adhere to the standard of EuroToken, the solution for double spending has to comply to the following requirements:
* Fully offline capable
* Completely distributed
* Permissionless
* Pseudo-anonymous
* Independent of other authorities, such as banks

### Double Spending mitigation
To counter double spending, EuroToken attempts to create a [web-of-trust](https://en.wikipedia.org/wiki/Web_of_trust) to help verify the authenticity of transactions and users.
The basis of the web-of-trust is proof by participation of users in the network.
If someone participates actively in the network, by making transactions, then they are regarded as more trustworthy than someone who does not participate.
[Read about the specific implementation of the web-of-trust](web-of-trust.md)

### Future work
Some further research needs to be done on how to calculate this score, beyond our linear additive approach.
We could opt for more sophisticated methods, which allow less misuse of the system and faster dissemination of bad actors within the system.
Another action that needs to be implemented is tying an update to connect a score update to the corresponding transaction.
This prohibits adversaries from continuously updating scores.
Additional research could also be done into the prevention of cycle boosting trust scores.

# Two-Phase NFC Transaction Flow - TLDR:
## Phase 1 - First NFC 'Tap': Transaction Negotiation
### Sender → Receiver (via NFC Card Emulation)

* Sender transmits transaction details (amount, sender info, trust data, UTXOs, and bloom filter)
* Receiver reviews transaction, trust score
* Receiver computes union between his and the received bloom filer
* Double spending would be detected here
* Receiver can accept/reject before any money moves

## Phase 2 - Second NFC 'Tap': Transaction Execution
### Receiver → Sender (confirmation)
* Receiver sends acceptance confirmation with public key and his bloom filter
* Sender computes union between his and the received bloom filter (both parties now have merged b.f.)

### Sender → Receiver (final transaction)
* Sender creates UTXO transaction with:
    * Input UTXOs being spent
    * Output UTXOs (recipient + change)
    * Complete transaction data
* After sending Transaction, Sender also adds spent UTXOs to his bloom filter
* Receiver verifies transaction details match the commited to ones from Phase 1
* Receiver processes transaction locally and updates balance
* Receiver adds spent UTXOs to bloom filter to prevent future double-spending

![system](https://github.com/user-attachments/assets/69929589-015d-46a3-b11a-a5510ce65d6c)

https://github.com/user-attachments/assets/300c09fb-7143-426e-a4a0-55804dbb3f80

https://github.com/user-attachments/assets/6ba00af3-e0ba-49e6-a978-95b6d2731c72

[Link to presentation](https://docs.google.com/presentation/d/13xzy0iyQFPSbM4r0SOWafp9CVFFn3owwPmA9K0H8L2M/edit?usp=sharing)

# Added Components:

### UTXO Management System

* UTXO (`UTXO.kt`): Individual unspent transaction outputs with transaction ID, index, amount, and owner
* UTXOTransaction (`UtxoTransaction.kt`): Represents complete transactions with inputs, outputs, and metadata
* UTXOStore (`UTXOStore.kt`): Database persistence layer for UTXO storage and retrieval
* UTXOService (`UtxoService.kt`): Business logic for UTXO operations, balance calculation, and transaction building

### Bloom Filter Double-Spending

* BloomFilter (`BloomFilter.kt`): Probabilistic data structure using dual hashing (2x Murmur3) to efficiently track spent UTXOs and prevent double-spending attacks

### NFC Communication Infrastructure

* HCEPaymentService (`HCEPaymentService.kt`): Host Card Emulation service implementing ISO-DEP protocol for NFC data exchange
* EurotokenNFCBaseFragment (`EurotokenNFCBaseFragment.kt`): Base class providing NFC reader/card emulation mode management
* NFCUtils (`HCENFCUtils.kt`): Utility class for NFC operations and mode switching

# Two-Phase Transaction Flow - Full:
## Phase 1 - First NFC 'Tap' - Sender → Receiver
### Sender Side:
1. Payment Initiation (`SendMoneyFragment.createSenderPayload()`):
    * Creates comprehensive sender information JSON payload
    * Includes sender public key, display name, payment amount, UTXOs to-be-spent, and bloom filter
    * Performs UTXO input selection using first-fit algorithm, caches this value

2. HCE Card Emulation Setup (`SendMoneyFragment.startPhase1HCETransmission()`):
    * Configures device as NFC card using `HCEPaymentService.setPendingTransactionData()`
    * Activates card emulation mode with 60-second timeout
    * Displays NFC UI prompting user to tap phones

3. NFC Data Transmission (`HCEPaymentService.handleGetData()`):
* Responds to reader's SELECT AID command
* Transmits sender payload when GET_DATA command received
* Confirms successful transmission via callback

### Receiver Side:
1. NFC Reader Activation (`TransferFragment.activateNFCReceive()`):
    * Switches device to NFC reader mode
    * Sets 60-second timeout for data reception
    * Updates UI to 'ready-to-receive' state

2. Data Reception (`TransferFragment.handleReceivedSenderData()`):
    * Receives and validates sender information JSON
    * Verifies data type and required fields
    * Caches all of this data for additional Phase 2 validation (checking commitment)
    * Navigates to transaction review interface

3. Trust Score Analysis (`ReceiveMoneyFragment.parseSenderData()`):
    * Extracts sender trust data from incoming payload
    * Processes shared transaction-peer information for trust building
    * Updates local trust scores with `TrustStore.incrementTrust()`

4. Trust Score Display (`ReceiveMoneyFragment.displayTrustScore()`):
    * Retrieves sender's current trust score from `TrustStore.getScore()`
    * Categorizes trust level (High: ≥70%, Average: 30-69%, Low: <30%)
    * Displays color-coded warning/information based on trust level

5. Double spending Detection:
    * Receiver merges the 2 bloom filters (this is his new one)
    * Checks collision between the sent UTXOs and new b.f.
    * If collision is detected the transaction is aborted automatically

6. Transaction Review Step:
    * Shows payment amount, sender information, and trust assessment, double spending status
    * Provides option to save sender as contact
    * **Allows user to accept or decline transaction before any value is exchanged**

## Phase 2 - Second NFC 'Tap' - Receiver → Sender → Receiver
### Receiver Side - Confirmation Step

1. Confirmation Step (`ReceiveMoneyFragment.startPhase2Receive()`):
    * Creates receiver confirmation JSON with public key and his bloom filter
    * Switches to HCE card emulation mode for confirmation transmission
    * Sends ready signal to sender device
    * Step is necessary because Sender requires PK of Receiver to create transaction
    * All communication till now was Sender → Receiver so Sender does not have PK of Receiver

2. Mode Transition (`ReceiveMoneyFragment.switchToPaymentReceiveMode()`):
    * Add 3-second delay to wait for Sender processing (**IMPORTANT**: longer delay to ensure sender has time to process confirmation and switch to card emulation mode)
    * Switches from card emulation to reader mode
    * Prepares to receive actual payment transaction

### Sender Side - Confirmation Received, Create and Send Transaction

1. Receiver Confirmation Processing (`SendMoneyFragment.handleReceiverConfirmation()`):
    * Validates receiver response and gets public key and bloom filter
    * Merges bloom filters (now both parties have merged b.f.)
    * Initiates actual transaction creation process

2. UTXO Offline Transaction Construction (`SendMoneyFragment.createAndSendTransaction()`):
    * Calls `UTXOService.buildUtxoTransactionSync()` to create transaction
    * Generates outputs for recipient and change (if necessary), using the cached UTXOs
    * Creates transaction ID using SHA-256 hash of timestamp

4. Transaction Transmission (`SendMoneyFragment.sendTransaction()`):
    * Serializes complete UTXO transaction
    * Creates payment JSON with transaction data
    * Transmits via HCE card emulation mode

5. Transaction Completion (`completeTransaction()`):
    * Marks input UTXOs as spent in database
    * Adds new output UTXOs for recipient and change
    * Navigates to transaction history screen

### Receiver Side - Process Incoming Transaction Data

1. Payment Deserialization and Init Validation (`ReceiveMoneyFragment.handleIncomingTransaction()`):
    * Receives and deserializes UTXO transaction data
    * Validates transaction details match Phase 1 information (commitment validation step)

2. Transaction Integration (`ReceiveMoneyFragment.processOfflineTransaction()`):
    * Calls `UTXOService.addUTXOTransaction()` to add transaction
    * On success, updates bloom filter with spent input UTXOs via `BloomFilter.add()`
    * Increments sender trust score in TrustStore
    * Updates local balance calculations

3. Database Updates (UTXOStore.addUTXOTransaction()):
    * Marks input UTXOs as spent in database
    * Adds new output UTXOs for recipient and change
    * Has retry mechanism with transaction rollback on failure

### Limitations
* **Emulators do not support NFC HCE**: This flow **cannot be tested on emulators**, only on real devices with NFC.
* **Race conditions**: If either party doesn’t switch modes promptly, NFC fails
* **Device-specific quirks**: Some Android devices aggressively manage NFC/foreground dispatch, which can interrupt flow