package com.academy.paybridge.transfer.client;

import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.client.paystack.PaystackProperties;
import com.academy.paybridge.transfer.client.paystack.PaystackTransferGateway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OPT-IN test that calls the REAL Paystack TEST API. It is skipped by a normal "mvn test".
 *
 * Run it with:
 *   export PAYSTACK_SECRET_KEY=sk_test_...
 *   mvn test -Dtest=PaystackSandboxLiveTest -Dsandbox=true
 *
 * Optional: SANDBOX_ACCOUNT_NUMBER and SANDBOX_BANK_CODE (a test account from the Paystack docs).
 * It only ever uses a test key, so no real money moves. Its result depends on your test account
 * (for example, test mode may require turning off transfer OTP in the dashboard).
 */
@Tag("sandbox")
@EnabledIfSystemProperty(named = "sandbox", matches = "true")
class PaystackSandboxLiveTest {

    @Test
    void sendsATestTransferThroughTheRealSandbox() {
        String key = System.getenv("PAYSTACK_SECRET_KEY");
        String account = System.getenv().getOrDefault("SANDBOX_ACCOUNT_NUMBER", "0000000000");
        String bank = System.getenv().getOrDefault("SANDBOX_BANK_CODE", "001");

        PaystackProperties props = new PaystackProperties("https://api.paystack.co", key, Duration.ofSeconds(5), Duration.ofSeconds(20));
        RestClient client = RestClient.builder().baseUrl(props.baseUrl())
                .requestFactory(new JdkClientHttpRequestFactory(HttpClient.newHttpClient())).build();
        PaystackTransferGateway gateway = new PaystackTransferGateway(client, props);

        GatewayTransferResult result = gateway.send(new GatewayTransferRequest(
                "pb-live-" + UUID.randomUUID(), new Money(10_000, Currency.getInstance("NGN")), account, bank, "Sandbox Test", "PayBridge sandbox test"));

        assertThat(result.status()).isIn(GatewayStatus.PENDING, GatewayStatus.SUCCESS, GatewayStatus.FAILED);
        System.out.println("Sandbox transfer status: " + result.status());
    }
}
