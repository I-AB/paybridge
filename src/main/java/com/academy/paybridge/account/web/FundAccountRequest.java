package com.academy.paybridge.account.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** @param amount in naira, for example 25000.00 */
public record FundAccountRequest(
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
        @Digits(integer = 9, fraction = 2, message = "amount must have at most 2 decimal places")
        BigDecimal amount) {
}
