package com.academy.paybridge.transfer.domain;

/**
 * Our own view of a transfer. This is deliberately smaller than any provider's list of statuses.
 *
 * <ul>
 *   <li>PENDING: accepted and recorded, final outcome not known yet (also used when the provider
 *       could not be reached, because the money may or may not have moved).</li>
 *   <li>SUCCESS: the provider confirmed the money was sent. Final.</li>
 *   <li>FAILED: the provider rejected, failed or reversed the transfer. Final.</li>
 * </ul>
 */
public enum TransferStatus {
    PENDING,
    SUCCESS,
    FAILED;

    public boolean isFinal() {
        return this != PENDING;
    }
}
