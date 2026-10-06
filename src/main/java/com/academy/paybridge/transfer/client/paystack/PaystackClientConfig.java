package com.academy.paybridge.transfer.client.paystack;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/** Builds the HTTP client used to talk to Paystack, with timeouts so a slow provider cannot hang us. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "paybridge.transfer.gateway", havingValue = "paystack", matchIfMissing = true)
class PaystackClientConfig {

    @Bean
    RestClient paystackRestClient(PaystackProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
