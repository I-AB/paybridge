package com.academy.paybridge.transfer.repository;

import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.domain.TransferStatus;

import java.time.Instant;
import java.util.List;
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

    /** Records that the money held for a failed transfer has been returned (or never needed returning). */
    void markFundsReleased(UUID id, Instant now);

    /** PENDING transfers created before the cutoff, oldest first. Used by the reconciler. */
    List<Transfer> findPendingCreatedBefore(Instant cutoff, int limit);

    /** FAILED transfers whose held money has not been returned yet, oldest first. */
    List<Transfer> findFailedAwaitingRelease(int limit);

    /** Newest first. */
    List<Transfer> findRecent(int limit);
}
