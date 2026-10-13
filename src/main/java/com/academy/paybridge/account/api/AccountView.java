package com.academy.paybridge.account.api;

import java.time.Instant;

/** What other modules (and the web layer) see of an account. Money is in minor units (kobo). */
public record AccountView(String accountNumber, String holderName, String currency, long balanceMinor, Instant createdAt) {
}
