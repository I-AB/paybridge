package com.academy.paybridge.shared.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    @Bean
    public OpenAPI payBridgeOpenApi() {
        return new OpenAPI().info(new Info()
                .title("PayBridge API")
                .version("1.0")
                .description("""
                        Move naira from a PayBridge account to any Nigerian bank account through a payment provider.

                        Quick tour: 1) open an account, 2) fund it with sandbox money, 3) send a transfer with an \
                        Idempotency-Key, 4) read the ledger. With the simulated gateway, destination account numbers \
                        0000000002..0000000005 trigger pending, rejected, timeout and name-enquiry-failure cases."""));
    }
}
