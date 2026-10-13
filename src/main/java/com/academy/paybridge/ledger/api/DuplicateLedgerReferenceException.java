package com.academy.paybridge.ledger.api;

/** A ledger entry with this reference already exists, so the movement has already happened. */
public class DuplicateLedgerReferenceException extends RuntimeException {

    public DuplicateLedgerReferenceException(String reference, Throwable cause) {
        super("Ledger reference '" + reference + "' was already posted", cause);
    }
}
