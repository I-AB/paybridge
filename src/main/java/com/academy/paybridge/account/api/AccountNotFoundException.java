package com.academy.paybridge.account.api;

import com.academy.paybridge.shared.exception.PayBridgeException;

public class AccountNotFoundException extends PayBridgeException {

    public AccountNotFoundException(String accountNumber) {
        super(404, "ACCOUNT_NOT_FOUND", "Account " + accountNumber + " was not found");
    }
}
