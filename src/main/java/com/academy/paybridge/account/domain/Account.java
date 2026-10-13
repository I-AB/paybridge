package com.academy.paybridge.account.domain;

import java.time.Instant;
import java.util.UUID;

public record Account(UUID id, String accountNumber, String holderName, String currency, long balanceMinor, Instant createdAt) {
}
