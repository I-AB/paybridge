package com.academy.paybridge.ledger.api;

import java.util.UUID;

/**
 * A movement to record. The caller (the account module) has already worked out the new balance
 * while holding a lock on the account; the ledger only writes it down.
 *
 * @param reference unique across the whole ledger; posting the same reference twice is rejected
 */
public record NewLedgerEntry(
        String reference,
        UUID accountId,
        String accountNumber,
        EntryDirection direction,
        long amountMinor,
        String currency,
        long balanceAfterMinor,
        String description) {
}
