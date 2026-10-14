package com.academy.paybridge.transfer.client.paystack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request and response shapes for the Paystack API. They exist only inside this package, so the
 * rest of the application never sees Paystack field names such as account_number.
 *
 * NOTE: field names follow Paystack's public API as known when this was written. They could not
 * be re-checked against the live documentation in the build environment, so confirm them against
 * https://paystack.com/docs/api/ if a call behaves unexpectedly.
 */
public final class PaystackDtos {

    private PaystackDtos() {
    }

    /** Every Paystack response is wrapped as { status, message, data }. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Envelope<T>(boolean status, String message, T data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResolveData(
            @JsonProperty("account_number") String accountNumber,
            @JsonProperty("account_name") String accountName) {
    }

    public record RecipientBody(
            String type,
            String name,
            @JsonProperty("account_number") String accountNumber,
            @JsonProperty("bank_code") String bankCode,
            String currency) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RecipientData(@JsonProperty("recipient_code") String recipientCode) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TransferBody(
            String source,
            long amount,
            String recipient,
            String reference,
            String reason,
            String currency) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransferData(
            String reference,
            @JsonProperty("transfer_code") String transferCode,
            String status) {
    }
}
