# Two-Phase Transaction Flow:  
## Phase 1 - First NFC 'Tap': Transaction Negotiation 
### Sender → Receiver (via NFC Card Emulation)  

* Sender transmits transaction details (amount, sender info, trust data)  
* Receiver reviews transaction and trust score  
* Receiver can accept/reject before any money moves  

## Phase 2 - Second NFC 'Tap': Transaction Execution  
### Receiver → Sender (confirmation)
* Receiver sends acceptance confirmation with public key

### Sender → Receiver (final transaction)
* Sender creates UTXO transaction with:
    * Input UTXOs being spent
    * Output UTXOs (recipient + change)
    * Complete transaction data
* Receiver verifies input UTXOs against local bloom filter for double-spending
* Receiver processes transaction locally and updates balance
* Receiver adds spent UTXOs to bloom filter to prevent future double-spending