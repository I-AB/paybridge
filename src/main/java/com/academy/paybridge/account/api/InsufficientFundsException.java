package com.academy.paybridge.account.api;

import com.academy.paybridge.shared.exception.PayBridgeException;

public class InsufficientFundsException extends PayBridgeException {

    /** The message is also stored as the failure reason of a transfer, so keep it short and safe to show. */
    public static final String MESSAGE = "Insufficient funds in the source account";

    public InsufficientFundsException() {
        super(422, "INSUFFICIENT_FUNDS", MESSAGE);
    }
}
