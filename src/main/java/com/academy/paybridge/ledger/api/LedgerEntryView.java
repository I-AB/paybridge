package com.academy.paybridge.ledger.api;

import java.time.Instant;

/** Read-only view of one ledger line. */
public record LedgerEntryView(
        String reference,
        String accountNumber,
        EntryDirection direction,
        long amountMinor,
        String currency,
        long balanceAfterMinor,
        String description,
        Instant createdAt) {
}
