package com.academy.paybridge.transfer.repository;

import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.domain.TransferStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Storage for transfers. Only the transfer module may use this (module rule). */
public interface TransferRepository {

    /** @throws DuplicateIdempotencyKeyException if the idempotency key is already stored */
    void insert(Transfer transfer);

    Optional<Transfer> findById(UUID id);

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

    Optional<Transfer> findByReference(String reference);

    /** Stores the provider's code once. If a code is already stored the call changes nothing. */
    void setProviderTransferCode(UUID id, String providerTransferCode, Instant now);

    /**
     * Moves a transfer from PENDING to a final status in one atomic step.
     *
     * @return true if this call changed the transfer, false if it was already final (so the call
     *         was a duplicate or arrived too late and changed nothing)
     */
    boolean markIfPending(UUID id, TransferStatus newStatus, String failureReason, Instant now);
}
