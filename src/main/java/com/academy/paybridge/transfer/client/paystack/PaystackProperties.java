package com.academy.paybridge.transfer.client.paystack;

import com.academy.paybridge.transfer.client.GatewayConfigurationException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Paystack settings, bound from {@code paybridge.paystack.*} in application.yml.
 * The secret key comes from the PAYSTACK_SECRET_KEY environment variable, never from source code.
 */
@ConfigurationProperties(prefix = "paybridge.paystack")
public record PaystackProperties(
        @DefaultValue("https://api.paystack.co") String baseUrl,
        String secretKey,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout) {

    private static final String TEST_KEY_PREFIX = "sk_test_";

    /**
     * Returns the secret key if it is configured AND is a test key.
     * This project is sandbox only, so a live key (sk_live_...) is refused on purpose.
     * The error messages never contain the key.
     */
    public String requireTestKey() {
        if (secretKey == null || secretKey.isBlank()) {
            throw new GatewayConfigurationException(
                    "Payment provider is not configured. Set the PAYSTACK_SECRET_KEY environment variable to a Paystack TEST secret key.");
        }
        if (!secretKey.startsWith(TEST_KEY_PREFIX)) {
            throw new GatewayConfigurationException(
                    "Only Paystack test secret keys (sk_test_...) are allowed in this project.");
        }
        return secretKey;
    }

    /** Masks the key so it can never leak through logging or debugging output. */
    @Override
    public String toString() {
        return "PaystackProperties[baseUrl=" + baseUrl + ", secretKey=" + (secretKey == null || secretKey.isBlank() ? "<not set>" : "<hidden>")
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }
}
