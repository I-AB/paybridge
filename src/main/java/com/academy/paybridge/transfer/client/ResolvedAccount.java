package com.academy.paybridge.transfer.client;

/** Result of a name enquiry: the bank's record of who owns the account. */
public record ResolvedAccount(String accountNumber, String accountName) {
}
