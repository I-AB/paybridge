package com.academy.paybridge.transfer.client;

import com.academy.paybridge.transfer.client.paystack.PaystackProperties;
import com.academy.paybridge.transfer.client.paystack.PaystackWebhookParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaystackWebhookParserTest {

    private static final String SECRET = "sk_test_unit_secret";

    private static PaystackWebhookParser parser(String key) {
        return new PaystackWebhookParser(new PaystackProperties("https://api.paystack.co", key, Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String body(String event, String reference) {
        return "{\"event\":\"" + event + "\",\"data\":{\"reference\":\"" + reference + "\",\"transfer_code\":\"TRF_9\"}}";
    }

    @Test
    void successEventIsDecoded() {
        String body = body("transfer.success", "pb-1");
        Optional<GatewayTransferEvent> event = parser(SECRET).parse(bytes(body), PaystackWebhookParser.sign(SECRET, bytes(body)));

        assertThat(event).contains(new GatewayTransferEvent("pb-1", "TRF_9", GatewayStatus.SUCCESS, null));
    }

    @Test
    void failedAndReversedEventsBothMapToFailed() {
        for (String name : new String[] {"transfer.failed", "transfer.reversed"}) {
            String body = body(name, "pb-2");
            GatewayTransferEvent event = parser(SECRET).parse(bytes(body), PaystackWebhookParser.sign(SECRET, bytes(body))).orElseThrow();
            assertThat(event.status()).as(name).isEqualTo(GatewayStatus.FAILED);
            assertThat(event.message()).isNotBlank();
        }
    }

    @Test
    void signatureComparisonIgnoresCaseOfTheHex() {
        String body = body("transfer.success", "pb-1");
        String upper = PaystackWebhookParser.sign(SECRET, bytes(body)).toUpperCase();

        assertThat(parser(SECRET).parse(bytes(body), upper)).isPresent();
    }

    @Test
    void tamperedBodyFailsVerification() {
        String body = body("transfer.success", "pb-1");
        String signature = PaystackWebhookParser.sign(SECRET, bytes(body));
        String tampered = body.replace("pb-1", "pb-9");

        assertThatThrownBy(() -> parser(SECRET).parse(bytes(tampered), signature))
                .isInstanceOf(InvalidWebhookException.class)
                .extracting("status").isEqualTo(401);
    }

    @Test
    void signatureMadeWithAnotherKeyFailsVerification() {
        String body = body("transfer.success", "pb-1");

        assertThatThrownBy(() -> parser(SECRET).parse(bytes(body), PaystackWebhookParser.sign("sk_test_other", bytes(body))))
                .isInstanceOf(InvalidWebhookException.class);
    }

    @Test
    void missingOrBlankSignatureFailsVerification() {
        String body = body("transfer.success", "pb-1");

        assertThatThrownBy(() -> parser(SECRET).parse(bytes(body), null)).isInstanceOf(InvalidWebhookException.class);
        assertThatThrownBy(() -> parser(SECRET).parse(bytes(body), " ")).isInstanceOf(InvalidWebhookException.class);
    }

    @Test
    void unrelatedEventIsIgnored() {
        String body = body("charge.success", "x");

        assertThat(parser(SECRET).parse(bytes(body), PaystackWebhookParser.sign(SECRET, bytes(body)))).isEmpty();
    }

    @Test
    void transferEventWithoutReferenceIsAnInvalidPayload() {
        String body = "{\"event\":\"transfer.success\",\"data\":{}}";

        assertThatThrownBy(() -> parser(SECRET).parse(bytes(body), PaystackWebhookParser.sign(SECRET, bytes(body))))
                .isInstanceOf(InvalidWebhookException.class)
                .extracting("status").isEqualTo(400);
    }

    @Test
    void refusesToVerifyWhenNoKeyIsConfigured() {
        assertThatThrownBy(() -> parser("").parse(bytes("{}"), "abc")).isInstanceOf(GatewayConfigurationException.class);
    }

    @Test
    void refusesLiveKeys() {
        assertThatThrownBy(() -> parser("sk_live_abc").parse(bytes("{}"), "abc")).isInstanceOf(GatewayConfigurationException.class);
    }
}
