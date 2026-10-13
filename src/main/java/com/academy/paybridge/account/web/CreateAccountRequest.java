package com.academy.paybridge.account.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank(message = "holderName is required")
        @Size(min = 2, max = 100, message = "holderName must be between 2 and 100 characters")
        String holderName) {
}
