package com.academy.paybridge.transfer.repository;

/** Thrown when a transfer with the same idempotency key is already stored. */
public class DuplicateIdempotencyKeyException extends RuntimeException {

    public DuplicateIdempotencyKeyException(String key, Throwable cause) {
        super("A transfer with idempotency key '" + key + "' already exists", cause);
    }
}
