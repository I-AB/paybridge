package com.academy.paybridge.transfer.domain;

import com.academy.paybridge.shared.exception.PayBridgeException;

/** The same Idempotency-Key was sent again with a different request body. */
public class IdempotencyKeyReuseException extends PayBridgeException {

    public IdempotencyKeyReuseException() {
        super(422, "IDEMPOTENCY_KEY_REUSED",
                "This Idempotency-Key was already used with a different request. Use a new key for a new transfer.");
    }
}
