package com.academy.paybridge.ledger.api;

import java.util.List;
import java.util.Optional;

/** What other modules may do with the ledger. It is append-only: there is no update and no delete. */
public interface LedgerApi {

    /** @throws DuplicateLedgerReferenceException if the reference was already used */
    void append(NewLedgerEntry entry);

    Optional<LedgerEntryView> findByReference(String reference);

    /** Newest first. */
    List<LedgerEntryView> entriesFor(String accountNumber, int limit);
}
