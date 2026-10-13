package com.academy.paybridge.ledger.repository;

import com.academy.paybridge.ledger.api.LedgerEntryView;
import com.academy.paybridge.ledger.api.NewLedgerEntry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LedgerRepository {

    void insert(NewLedgerEntry entry, Instant now);

    Optional<LedgerEntryView> findByReference(String reference);

    List<LedgerEntryView> findByAccountNumber(String accountNumber, int limit);
}
