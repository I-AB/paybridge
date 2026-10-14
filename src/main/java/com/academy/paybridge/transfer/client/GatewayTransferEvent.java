package com.academy.paybridge.transfer.client;

/** A verified, provider-neutral webhook event about one transfer. */
public record GatewayTransferEvent(
        String reference,
        String providerTransferCode,
        GatewayStatus status,
        String message) {
}
