package com.academy.paybridge.transfer.client;

/**
 * A verified, provider-neutral webhook event about one transfer.
 *
 * @param amountMinorUnits the amount the provider says it moved, in minor units, or null if the event did not say.
 *                         The service compares it with the amount we stored.
 */
public record GatewayTransferEvent(
        String reference,
        String providerTransferCode,
        GatewayStatus status,
        String message,
        Long amountMinorUnits) {

    /** For events that carry no amount. */
    public GatewayTransferEvent(String reference, String providerTransferCode, GatewayStatus status, String message) {
        this(reference, providerTransferCode, status, message, null);
    }
}
