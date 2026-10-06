package com.academy.paybridge.transfer.client;

import com.academy.paybridge.shared.money.Money;

/**
 * Everything a gateway needs to send money. {@code reference} is our own unique reference, so the
 * provider can reject a second send with the same reference.
 */
public record GatewayTransferRequest(
        String reference,
        Money amount,
        String accountNumber,
        String bankCode,
        String accountName,
        String reason) {
}
