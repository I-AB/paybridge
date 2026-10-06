package com.academy.paybridge.transfer.domain;

import com.academy.paybridge.shared.exception.PayBridgeException;

/** The request is well formed JSON but breaks a business rule (bad amount, unsupported currency). */
public class InvalidTransferException extends PayBridgeException {

    public InvalidTransferException(String message) {
        super(400, "INVALID_TRANSFER", message);
    }
}
