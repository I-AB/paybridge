package com.academy.paybridge.transfer.repository;

import java.time.Instant;
import java.util.List;

/** Keeps a trail of every verified webhook and what we did with it. The raw body is never stored. */
public interface WebhookAuditRepository {

    void record(String reference, String eventStatus, String providerTransferCode,
                Long amountMinor, String outcome, Instant receivedAt);

    /** Newest first. */
    List<WebhookAuditRecord> findRecent(int limit);

    record WebhookAuditRecord(String reference, String eventStatus, String providerTransferCode,
                              Long amountMinor, String outcome, Instant receivedAt) {
    }
}
