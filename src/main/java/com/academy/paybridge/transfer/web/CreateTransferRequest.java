package com.academy.paybridge.transfer.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * The JSON body of POST /api/transfers. Bean Validation rejects bad input at the API boundary,
 * before any business logic or provider call happens.
 *
 * @param amount amount in major units, for example 5000.00 naira
 */
public record CreateTransferRequest(
        @NotBlank(message = "sourceAccount is required")
        @Size(max = 64, message = "sourceAccount must be at most 64 characters")
        String sourceAccount,

        @NotNull(message = "destination is required")
        @Valid
        Destination destination,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
        @Digits(integer = 12, fraction = 2, message = "amount must have at most 2 decimal places")
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3 letter ISO code such as NGN")
        String currency,

        @Size(max = 100, message = "reason must be at most 100 characters")
        String reason) {

    public record Destination(
            @NotBlank(message = "accountNumber is required")
            @Pattern(regexp = "\\d{10}", message = "accountNumber must be exactly 10 digits")
            String accountNumber,

            @NotBlank(message = "bankCode is required")
            @Pattern(regexp = "\\d{3,6}", message = "bankCode must be 3 to 6 digits")
            String bankCode) {
    }
}
