package com.academy.paybridge.transfer.web;

import com.academy.paybridge.shared.exception.GlobalExceptionHandler;
import com.academy.paybridge.transfer.client.GatewayStatus;
import com.academy.paybridge.transfer.client.GatewayTransferEvent;
import com.academy.paybridge.transfer.client.paystack.PaystackProperties;
import com.academy.paybridge.transfer.client.paystack.PaystackWebhookParser;
import com.academy.paybridge.transfer.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebhookControllerTest {

    private static final String SECRET = "sk_test_unit_secret";
    private static final String SUCCESS_BODY =
            "{\"event\":\"transfer.success\",\"data\":{\"reference\":\"pb-abc\",\"transfer_code\":\"TRF_1\",\"status\":\"success\"}}";

    private TransferService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(TransferService.class);
        PaystackProperties props = new PaystackProperties("https://api.paystack.co", SECRET, Duration.ofSeconds(1), Duration.ofSeconds(1));
        mvc = MockMvcBuilders.standaloneSetup(new WebhookController(new PaystackWebhookParser(props), service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static String sign(String body) {
        return PaystackWebhookParser.sign(SECRET, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void validSignedEventIsAppliedAndAnswered200() throws Exception {
        mvc.perform(post("/api/webhooks/paystack").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Paystack-Signature", sign(SUCCESS_BODY)).content(SUCCESS_BODY))
                .andExpect(status().isOk());

        verify(service).applyProviderEvent(new GatewayTransferEvent("pb-abc", "TRF_1", GatewayStatus.SUCCESS, null));
    }

    @Test
    void wrongSignatureIsRejectedWith401AndNothingIsApplied() throws Exception {
        mvc.perform(post("/api/webhooks/paystack").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Paystack-Signature", "deadbeef").content(SUCCESS_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_SIGNATURE"));

        verify(service, never()).applyProviderEvent(any());
    }

    @Test
    void missingSignatureIsRejectedWith401() throws Exception {
        mvc.perform(post("/api/webhooks/paystack").contentType(MediaType.APPLICATION_JSON).content(SUCCESS_BODY))
                .andExpect(status().isUnauthorized());
        verify(service, never()).applyProviderEvent(any());
    }

    @Test
    void validEventWeDoNotCareAboutIsAcknowledgedWith200() throws Exception {
        String other = "{\"event\":\"charge.success\",\"data\":{\"reference\":\"x\"}}";

        mvc.perform(post("/api/webhooks/paystack").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Paystack-Signature", sign(other)).content(other))
                .andExpect(status().isOk());

        verify(service, never()).applyProviderEvent(any());
    }

    @Test
    void signedButMalformedPayloadReturns400() throws Exception {
        String broken = "{not json";

        mvc.perform(post("/api/webhooks/paystack").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Paystack-Signature", sign(broken)).content(broken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_WEBHOOK_PAYLOAD"));
    }
}
