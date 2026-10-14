package com.academy.paybridge.transfer.service;

/** What happened when a provider event was applied. All of these are answered with HTTP 200. */
public enum WebhookResult {
    /** The event moved a PENDING transfer to its final status. */
    APPLIED,
    /** The transfer was already final, so the event changed nothing (duplicate or late event). */
    DUPLICATE,
    /** The event does not say anything final yet. */
    IGNORED,
    /** We have no transfer with that reference. */
    UNKNOWN_REFERENCE
}
