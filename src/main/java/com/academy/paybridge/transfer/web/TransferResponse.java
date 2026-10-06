package com.academy.paybridge.transfer.web;

import com.academy.paybridge.transfer.domain.Transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** What the API returns. It is a copy of the domain object with only what a client should see. */
public record TransferResponse(
        UUID id,
        String status,
        String reference,
        String sourceAccount,
        Destination destination,
        BigDecimal amount,
        String currency,
        String reason,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    public record Destination(String accountNumber, String bankCode, String accountName) {
    }

    public static TransferResponse from(Transfer t) {
        return new TransferResponse(
                t.id(),
                t.status().name(),
                t.reference(),
                t.sourceAccount(),
                new Destination(t.destinationAccountNumber(), t.destinationBankCode(), t.destinationAccountName()),
                t.amount().toMajor(),
                t.amount().currency().getCurrencyCode(),
                t.reason(),
                t.failureReason(),
                t.createdAt(),
                t.updatedAt());
    }
}
