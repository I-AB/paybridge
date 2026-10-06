package com.academy.paybridge.shared.exception;

/**
 * Base class for errors that should be shown to API callers.
 *
 * <p>Each subclass carries the HTTP status and a stable machine readable code, so the global
 * handler can turn any of them into a consistent JSON error without knowing which module
 * threw it.
 */
public class PayBridgeException extends RuntimeException {

    private final int status;
    private final String code;

    public PayBridgeException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
