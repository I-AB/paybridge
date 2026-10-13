package com.academy.paybridge.transfer.client.paystack;

import com.academy.paybridge.transfer.client.GatewayStatus;
import com.academy.paybridge.transfer.client.GatewayTransferEvent;
import com.academy.paybridge.transfer.client.InvalidWebhookException;
import com.academy.paybridge.transfer.client.TransferWebhookParser;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * Verifies and decodes Paystack webhooks.
 *
 * <p>Paystack signs every webhook with HMAC SHA512 of the raw request body, using your secret
 * key, and sends the hex result in the x-paystack-signature header. We recompute it and compare.
 * Anyone who does not know the secret key cannot forge a valid signature.
 */
@Component
public class PaystackWebhookParser implements TransferWebhookParser {

    private final PaystackProperties properties;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public PaystackWebhookParser(PaystackProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<GatewayTransferEvent> parse(byte[] rawBody, String signature) {
        verify(rawBody, signature);

        JsonNode root;
        try {
            root = jsonMapper.readTree(rawBody);
        } catch (RuntimeException e) {
            throw InvalidWebhookException.badPayload();
        }

        String event = text(root.path("event"));
        GatewayStatus status = switch (event == null ? "" : event) {
            case "transfer.success" -> GatewayStatus.SUCCESS;
            case "transfer.failed", "transfer.reversed" -> GatewayStatus.FAILED;
            default -> null;
        };
        if (status == null) {
            return Optional.empty();
        }

        JsonNode data = root.path("data");
        String reference = text(data.path("reference"));
        if (reference == null || reference.isBlank()) {
            throw InvalidWebhookException.badPayload();
        }
        String message = status == GatewayStatus.SUCCESS ? null
                : "transfer.reversed".equals(event) ? "Transfer was reversed by the payment provider"
                : "Transfer failed at the payment provider";
        JsonNode amountNode = data.path("amount");
        Long amountMinor = amountNode.isNumber() ? amountNode.longValue() : null; // Paystack sends kobo
        return Optional.of(new GatewayTransferEvent(
                reference, text(data.path("transfer_code")), status, message, amountMinor));
    }

    /** Computes the signature Paystack would send for this body. Used by verification, tests and the demo. */
    public static String sign(String secretKey, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC SHA512 is not available", e);
        }
    }

    private void verify(byte[] rawBody, String signature) {
        String secret = properties.requireTestKey();
        if (signature == null || signature.isBlank()) {
            throw InvalidWebhookException.badSignature();
        }
        byte[] expected = sign(secret, rawBody).getBytes(StandardCharsets.US_ASCII);
        byte[] provided = signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        // constant time comparison, so response timing reveals nothing about the correct signature
        if (!MessageDigest.isEqual(expected, provided)) {
            throw InvalidWebhookException.badSignature();
        }
    }

    private static String text(JsonNode node) {
        return node.isString() ? node.stringValue() : null;
    }
}
