package com.academy.paybridge.transfer.service;

import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.domain.TransferStatus;
import com.academy.paybridge.transfer.repository.DuplicateIdempotencyKeyException;
import com.academy.paybridge.transfer.repository.TransferRepository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** Test double that behaves like the SQL repository: unique idempotency key, PENDING-only updates. */
public class InMemoryTransferRepository implements TransferRepository {

    private final Map<UUID, Transfer> byId = new ConcurrentHashMap<>();
    private final Set<UUID> fundsReleased = ConcurrentHashMap.newKeySet();

    @Override
    public synchronized void insert(Transfer transfer) {
        boolean keyTaken = byId.values().stream()
                .anyMatch(t -> t.idempotencyKey().equals(transfer.idempotencyKey()));
        if (keyTaken) {
            throw new DuplicateIdempotencyKeyException(transfer.idempotencyKey(), null);
        }
        byId.put(transfer.id(), transfer);
    }

    @Override
    public Optional<Transfer> findById(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<Transfer> findByIdempotencyKey(String key) {
        return byId.values().stream().filter(t -> t.idempotencyKey().equals(key)).findFirst();
    }

    @Override
    public Optional<Transfer> findByReference(String reference) {
        return byId.values().stream().filter(t -> t.reference().equals(reference)).findFirst();
    }

    @Override
    public synchronized void setProviderTransferCode(UUID id, String code, Instant now) {
        byId.computeIfPresent(id, (k, t) -> t.providerTransferCode() != null ? t : new Transfer(t.id(), t.idempotencyKey(), t.requestFingerprint(),
                t.reference(), t.sourceAccount(), t.destinationAccountNumber(), t.destinationBankCode(),
                t.destinationAccountName(), t.amount(), t.reason(), t.status(), code, t.failureReason(),
                t.createdAt(), now));
    }

    @Override
    public synchronized boolean markIfPending(UUID id, TransferStatus status, String failureReason, Instant now) {
        Transfer t = byId.get(id);
        if (t == null || t.status() != TransferStatus.PENDING) {
            return false;
        }
        byId.put(id, new Transfer(t.id(), t.idempotencyKey(), t.requestFingerprint(), t.reference(),
                t.sourceAccount(), t.destinationAccountNumber(), t.destinationBankCode(),
                t.destinationAccountName(), t.amount(), t.reason(), status, t.providerTransferCode(),
                failureReason, t.createdAt(), now));
        return true;
    }

    @Override
    public void markFundsReleased(UUID id, Instant now) {
        fundsReleased.add(id);
    }

    @Override
    public List<Transfer> findPendingCreatedBefore(Instant cutoff, int limit) {
        return byId.values().stream()
                .filter(t -> t.status() == TransferStatus.PENDING && t.createdAt().isBefore(cutoff))
                .sorted(Comparator.comparing(Transfer::createdAt))
                .limit(limit).collect(Collectors.toList());
    }

    @Override
    public List<Transfer> findFailedAwaitingRelease(int limit) {
        return byId.values().stream()
                .filter(t -> t.status() == TransferStatus.FAILED && !fundsReleased.contains(t.id()))
                .sorted(Comparator.comparing(Transfer::createdAt))
                .limit(limit).collect(Collectors.toList());
    }

    @Override
    public List<Transfer> findRecent(int limit) {
        return byId.values().stream()
                .sorted(Comparator.comparing(Transfer::createdAt).reversed())
                .limit(limit).collect(Collectors.toList());
    }

    public boolean isFundsReleased(UUID id) {
        return fundsReleased.contains(id);
    }

    public int size() {
        return byId.size();
    }
}
