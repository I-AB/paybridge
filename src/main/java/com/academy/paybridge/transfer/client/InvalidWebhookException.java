package com.academy.paybridge.transfer.client;

import com.academy.paybridge.shared.exception.PayBridgeException;

/** A webhook failed signature verification or could not be decoded. */
public class InvalidWebhookException extends PayBridgeException {

    private InvalidWebhookException(int status, String code, String message) {
        super(status, code, message);
    }

    public static InvalidWebhookException badSignature() {
        return new InvalidWebhookException(401, "INVALID_WEBHOOK_SIGNATURE", "Webhook signature is invalid");
    }

    public static InvalidWebhookException badPayload() {
        return new InvalidWebhookException(400, "INVALID_WEBHOOK_PAYLOAD", "Webhook payload is invalid");
    }
}
