package com.academy.paybridge.transfer.client;

/** What the provider told us right after we asked it to send (or when we asked for the status). */
public record GatewayTransferResult(GatewayStatus status, String providerTransferCode, String message) {
}
