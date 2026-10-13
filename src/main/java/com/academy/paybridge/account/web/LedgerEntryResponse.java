package com.academy.paybridge.account.web;

import com.academy.paybridge.ledger.api.LedgerEntryView;

import java.math.BigDecimal;
import java.time.Instant;

public record LedgerEntryResponse(
        String reference, String direction, BigDecimal amount, String currency,
        BigDecimal balanceAfter, String description, Instant createdAt) {

    public static LedgerEntryResponse from(LedgerEntryView e) {
        return new LedgerEntryResponse(e.reference(), e.direction().name(), BigDecimal.valueOf(e.amountMinor(), 2),
                e.currency(), BigDecimal.valueOf(e.balanceAfterMinor(), 2), e.description(), e.createdAt());
    }
}
