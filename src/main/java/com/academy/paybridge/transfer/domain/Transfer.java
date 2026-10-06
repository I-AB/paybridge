package com.academy.paybridge.transfer.domain;

import com.academy.paybridge.shared.money.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * A single money transfer request and its current state.
 *
 * <p>This is an immutable snapshot. State changes happen through the repository, which only moves
 * a transfer out of PENDING once, so a late or duplicate provider event can never overwrite a
 * final result.
 *
 * @param reference            our own unique reference, sent to the provider so both sides can
 *                             identify the same transfer
 * @param sourceAccount        internal wallet or account identifier supplied by the caller (the
 *                             account module is not built yet, so it is recorded, not debited)
 * @param providerTransferCode the provider's own identifier, known only after the provider answers
 */
public record Transfer(
        UUID id,
        String idempotencyKey,
        String requestFingerprint,
        String reference,
        String sourceAccount,
        String destinationAccountNumber,
        String destinationBankCode,
        String destinationAccountName,
        Money amount,
        String reason,
        TransferStatus status,
        String providerTransferCode,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {
}
