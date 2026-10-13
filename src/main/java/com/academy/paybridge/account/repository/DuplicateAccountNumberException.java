package com.academy.paybridge.account.repository;

public class DuplicateAccountNumberException extends RuntimeException {

    public DuplicateAccountNumberException(String accountNumber, Throwable cause) {
        super("Account number " + accountNumber + " already exists", cause);
    }
}
