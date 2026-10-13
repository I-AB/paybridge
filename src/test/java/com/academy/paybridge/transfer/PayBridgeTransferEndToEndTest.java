package com.academy.paybridge.transfer;

import com.academy.paybridge.transfer.client.paystack.PaystackWebhookParser;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Whole application: real controllers, real service, real H2 database and real SQL repository.
 * Only the payment provider is replaced by the offline simulator, so nothing leaves the machine.
 *
 * Simulator account numbers: 0000000001 success, 0000000002 pending, 0000000003 rejected,
 * 0000000004 provider timeout, 0000000005 name enquiry fails.
 */
@SpringBootTest(properties = {
        "paybridge.transfer.gateway=simulated",
        "paybridge.transfer.enforce-source-balance=false",
        "paybridge.paystack.secret-key=sk_test_end_to_end"
})
class PayBridgeTransferEndToEndTest {

    private static final String SECRET = "sk_test_end_to_end";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private static String body(String accountNumber, String amount) {
        return """
                {"sourceAccount":"wallet-001",
                 "destination":{"accountNumber":"%s","bankCode":"058"},
                 "amount":%s,"currency":"NGN","reason":"demo"}""".formatted(accountNumber, amount);
    }

    private MvcResult create(String key, String accountNumber, String amount) throws Exception {
        return mvc.perform(post("/api/transfers").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body(accountNumber, amount))).andReturn();
    }

    private static String newKey() {
        return "e2e-" + UUID.randomUUID();
    }

    private static String idOf(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private void sendWebhook(String event, String reference) throws Exception {
        String payload = "{\"event\":\"" + event + "\",\"data\":{\"reference\":\"" + reference + "\",\"transfer_code\":\"TRF_e2e\"}}";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/api/webhooks/paystack").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Paystack-Signature", PaystackWebhookParser.sign(SECRET, bytes)).content(bytes))
                .andExpect(status().isOk());
    }

    @Test
    void successfulTransferIsStoredAndCanBeReadBack() throws Exception {
        MvcResult created = create(newKey(), "0000000001", "5000.00");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);

        mvc.perform(get("/api/transfers/{id}", idOf(created)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.destination.accountName").value("SIMULATED ACCOUNT HOLDER"))
                .andExpect(jsonPath("$.amount").value(5000.0));
    }

    @Test
    void sameKeyTwiceReturnsTheSameTransferAndCreatesNoSecondOne() throws Exception {
        String key = newKey();

        MvcResult first = create(key, "0000000001", "250.00");
        MvcResult second = create(key, "0000000001", "250.00");

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(idOf(second)).isEqualTo(idOf(first));
    }

    @Test
    void sameKeyWithDifferentAmountIsRefused() throws Exception {
        String key = newKey();
        create(key, "0000000001", "250.00");

        assertThat(create(key, "0000000001", "999.00").getResponse().getStatus()).isEqualTo(422);
    }

    @Test
    void providerRejectionIsRecordedAsFailed() throws Exception {
        MvcResult created = create(newKey(), "0000000003", "100.00");

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        mvc.perform(get("/api/transfers/{id}", idOf(created)))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("Insufficient balance at the provider (simulated)"));
    }

    @Test
    void providerTimeoutLeavesTransferPendingAndRetryDoesNotDuplicateIt() throws Exception {
        String key = newKey();

        MvcResult first = create(key, "0000000004", "100.00");
        MvcResult retry = create(key, "0000000004", "100.00");

        assertThat(first.getResponse().getStatus()).isEqualTo(202);
        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
        assertThat(idOf(retry)).isEqualTo(idOf(first));
        mvc.perform(get("/api/transfers/{id}", idOf(first))).andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void pendingTransferCanBeCompletedWithSync() throws Exception {
        MvcResult created = create(newKey(), "0000000002", "100.00");
        assertThat(created.getResponse().getStatus()).isEqualTo(202);

        mvc.perform(post("/api/transfers/{id}/sync", idOf(created)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void pendingTransferIsCompletedByASignedWebhookAndDuplicatesChangeNothing() throws Exception {
        MvcResult created = create(newKey(), "0000000002", "100.00");
        String id = idOf(created);
        String reference = JsonPath.read(created.getResponse().getContentAsString(), "$.reference");

        sendWebhook("transfer.success", reference);
        mvc.perform(get("/api/transfers/{id}", id)).andExpect(jsonPath("$.status").value("SUCCESS"));
        String updatedAt = JsonPath.read(mvc.perform(get("/api/transfers/{id}", id)).andReturn().getResponse().getContentAsString(), "$.updatedAt");

        sendWebhook("transfer.success", reference);   // duplicate delivery
        sendWebhook("transfer.failed", reference);    // late conflicting event

        String after = mvc.perform(get("/api/transfers/{id}", id)).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(after, "$.status")).isEqualTo("SUCCESS");
        assertThat((String) JsonPath.read(after, "$.updatedAt")).isEqualTo(updatedAt);
    }

    @Test
    void failedWebhookFailsAPendingTransfer() throws Exception {
        MvcResult created = create(newKey(), "0000000002", "100.00");
        String reference = JsonPath.read(created.getResponse().getContentAsString(), "$.reference");

        sendWebhook("transfer.failed", reference);

        mvc.perform(get("/api/transfers/{id}", idOf(created))).andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void unsignedWebhookIsRejected() throws Exception {
        mvc.perform(post("/api/webhooks/paystack").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"transfer.success\",\"data\":{\"reference\":\"pb-x\"}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unresolvableAccountIsRefusedAndNothingIsStored() throws Exception {
        String key = newKey();

        assertThat(create(key, "0000000005", "100.00").getResponse().getStatus()).isEqualTo(422);
        // the key was not burned, so the same key can be used again with a good account
        assertThat(create(key, "0000000001", "100.00").getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void invalidRequestsReturn400() throws Exception {
        mvc.perform(post("/api/transfers").header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON).content(body("0000000001", "-5")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(body("0000000001", "5")))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
    }

    @Test
    void unknownTransferIs404() throws Exception {
        mvc.perform(get("/api/transfers/{id}", UUID.randomUUID())).andExpect(status().isNotFound());
    }
}
