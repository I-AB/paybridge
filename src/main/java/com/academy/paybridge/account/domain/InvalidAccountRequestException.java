package com.academy.paybridge.account.domain;

import com.academy.paybridge.shared.exception.PayBridgeException;

public class InvalidAccountRequestException extends PayBridgeException {

    public InvalidAccountRequestException(String message) {
        super(400, "INVALID_ACCOUNT_REQUEST", message);
    }
}
