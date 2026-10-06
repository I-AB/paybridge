package com.academy.paybridge.transfer.domain;

import com.academy.paybridge.shared.exception.PayBridgeException;

import java.util.UUID;

public class TransferNotFoundException extends PayBridgeException {

    public TransferNotFoundException(UUID id) {
        super(404, "TRANSFER_NOT_FOUND", "Transfer " + id + " was not found");
    }
}
