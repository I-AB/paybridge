package com.academy.paybridge.transfer.service;

import com.academy.paybridge.transfer.domain.Transfer;

/**
 * @param replayed true if this call found an existing transfer for the same idempotency key
 *                 instead of creating a new one
 */
public record TransferOutcome(Transfer transfer, boolean replayed) {
}
