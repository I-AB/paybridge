package com.academy.paybridge.transfer.client;

/** Provider-neutral result of a transfer. Each gateway maps its own statuses onto these three. */
public enum GatewayStatus {
    PENDING,
    SUCCESS,
    FAILED
}
