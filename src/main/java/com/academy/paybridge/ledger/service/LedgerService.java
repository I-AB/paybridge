package com.academy.paybridge.ledger.service;

import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.LedgerEntryView;
import com.academy.paybridge.ledger.api.NewLedgerEntry;
import com.academy.paybridge.ledger.repository.LedgerRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

@Service
public class LedgerService implements LedgerApi {

    private static final int MAX_ENTRIES = 200;

    private final LedgerRepository repository;
    private final Clock clock;

    public LedgerService(LedgerRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void append(NewLedgerEntry entry) {
        repository.insert(entry, clock.instant());
    }

    @Override
    public Optional<LedgerEntryView> findByReference(String reference) {
        return repository.findByReference(reference);
    }

    @Override
    public List<LedgerEntryView> entriesFor(String accountNumber, int limit) {
        return repository.findByAccountNumber(accountNumber, Math.max(1, Math.min(limit, MAX_ENTRIES)));
    }
}
