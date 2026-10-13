package com.academy.paybridge.account.web;

import com.academy.paybridge.account.api.AccountView;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountResponse(String accountNumber, String holderName, String currency, BigDecimal balance, Instant createdAt) {

    public static AccountResponse from(AccountView a) {
        return new AccountResponse(a.accountNumber(), a.holderName(), a.currency(),
                BigDecimal.valueOf(a.balanceMinor(), 2), a.createdAt());
    }
}
