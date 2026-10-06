package com.academy.paybridge.transfer.service;

import java.math.BigDecimal;

/** Input to {@link TransferService#initiate}. Plain data, independent of HTTP. */
public record CreateTransferCommand(
        String idempotencyKey,
        String sourceAccount,
        String destinationAccountNumber,
        String destinationBankCode,
        BigDecimal amount,
        String currency,
        String reason) {
}
