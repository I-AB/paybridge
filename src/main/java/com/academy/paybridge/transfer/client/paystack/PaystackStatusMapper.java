package com.academy.paybridge.transfer.client.paystack;

import com.academy.paybridge.transfer.client.GatewayStatus;

import java.util.Locale;

/**
 * Translates Paystack's transfer status words into our three neutral statuses.
 * Anything we do not recognise (pending, otp, received, queued...) is treated as PENDING,
 * because "not final yet" is the safe assumption.
 */
final class PaystackStatusMapper {

    private PaystackStatusMapper() {
    }

    static GatewayStatus map(String paystackStatus) {
        if (paystackStatus == null) {
            return GatewayStatus.PENDING;
        }
        return switch (paystackStatus.toLowerCase(Locale.ROOT)) {
            case "success" -> GatewayStatus.SUCCESS;
            case "failed", "reversed" -> GatewayStatus.FAILED;
            default -> GatewayStatus.PENDING;
        };
    }
}
