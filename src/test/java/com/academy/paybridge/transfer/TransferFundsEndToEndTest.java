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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Whole application with balance enforcement ON (the default): accounts, ledger, transfers and
 * webhooks together, against the offline simulator and a real H2 database.
 */
@SpringBootTest(properties = {
        "paybridge.transfer.gateway=simulated",
        "paybridge.paystack.secret-key=sk_test_funds"
})
class TransferFundsEndToEndTest {

    private static final String SECRET = "sk_test_funds";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    /** Opens an account and funds it with the given naira, returns its number. */
    private String fundedAccount(String naira) throws Exception {
        MvcResult opened = mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"holderName\":\"Test Holder\"}")).andExpect(status().isCreated()).andReturn();
        String number = JsonPath.read(opened.getResponse().getContentAsString(), "$.accountNumber");
        mvc.perform(post("/api/accounts/{n}/fund", number).header("Idempotency-Key", "fund-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + naira + "}"))
                .andExpect(status().isCreated());
        return number;
    }

    private double balance(String number) throws Exception {
        MvcResult r = mvc.perform(get("/api/accounts/{n}", number)).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.balance")).doubleValue();
    }

    private MvcResult send(String source, String destination, String amount) throws Exception {
        String body = """
                {"sourceAccount":"%s","destination":{"accountNumber":"%s","bankCode":"058"},
                 "amount":%s,"currency":"NGN","reason":"funds test"}""".formatted(source, destination, amount);
        return mvc.perform(post("/api/transfers").header("Idempotency-Key", "k-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private static String field(MvcResult r, String path) throws Exception {
        return String.valueOf((Object) JsonPath.read(r.getResponse().getContentAsString(), path));
    }

    private int webhook(String event, String reference, long amountKobo) throws Exception {
        String payload = "{\"event\":\"" + event + "\",\"data\":{\"reference\":\"" + reference
                + "\",\"transfer_code\":\"TRF_f\",\"amount\":" + amountKobo + "}}";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        return mvc.perform(post("/api/webhooks/paystack").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Paystack-Signature", PaystackWebhookParser.sign(SECRET, bytes)).content(bytes))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void successfulTransferTakesTheMoneyOutOfTheSourceAccount() throws Exception {
        String source = fundedAccount("10000.00");

        MvcResult result = send(source, "0000000001", "2500.00");

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(field(result, "$.status")).isEqualTo("SUCCESS");
        assertThat(balance(source)).isEqualTo(7500.0);
    }

    @Test
    void notEnoughMoneyFailsTheTransferAndTouchesNothing() throws Exception {
        String source = fundedAccount("100.00");

        MvcResult result = send(source, "0000000001", "500.00");

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(field(result, "$.status")).isEqualTo("FAILED");
        assertThat(field(result, "$.failureReason")).contains("Insufficient funds");
        assertThat(balance(source)).isEqualTo(100.0);
    }

    @Test
    void providerRejectionGivesTheMoneyBack() throws Exception {
        String source = fundedAccount("1000.00");

        MvcResult result = send(source, "0000000003", "400.00"); // simulator: provider rejects

        assertThat(field(result, "$.status")).isEqualTo("FAILED");
        assertThat(balance(source)).isEqualTo(1000.0);
        mvc.perform(get("/api/accounts/{n}/entries", source))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3)); // top-up, hold, refund
    }

    @Test
    void timeoutKeepsTheMoneyHeldUntilTheOutcomeIsKnown() throws Exception {
        String source = fundedAccount("1000.00");

        MvcResult result = send(source, "0000000004", "400.00"); // simulator: provider times out

        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        assertThat(field(result, "$.status")).isEqualTo("PENDING");
        assertThat(balance(source)).isEqualTo(600.0);
    }

    @Test
    void failedWebhookRefundsOnceEvenWhenDeliveredTwice() throws Exception {
        String source = fundedAccount("1000.00");
        MvcResult pending = send(source, "0000000002", "300.00");
        String reference = field(pending, "$.reference");
        assertThat(balance(source)).isEqualTo(700.0);

        assertThat(webhook("transfer.failed", reference, 30_000)).isEqualTo(200);
        assertThat(webhook("transfer.failed", reference, 30_000)).isEqualTo(200);

        assertThat(balance(source)).isEqualTo(1000.0);
    }

    @Test
    void webhookWithTheWrongAmountIsNotApplied() throws Exception {
        String source = fundedAccount("1000.00");
        MvcResult pending = send(source, "0000000002", "300.00");
        String reference = field(pending, "$.reference");

        assertThat(webhook("transfer.success", reference, 99_999)).isEqualTo(200);

        mvc.perform(get("/api/transfers/{id}", field(pending, "$.id")))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/api/webhooks/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("AMOUNT_MISMATCH"));
    }

    @Test
    void unknownSourceAccountIsRejectedBeforeAnythingIsSaved() throws Exception {
        MvcResult result = send("9099999999", "0000000001", "10.00");

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(field(result, "$.code")).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void fundingTwiceWithTheSameKeyAddsMoneyOnce() throws Exception {
        MvcResult opened = mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"holderName\":\"Idem Potent\"}")).andReturn();
        String number = JsonPath.read(opened.getResponse().getContentAsString(), "$.accountNumber");
        String key = "fund-" + UUID.randomUUID();

        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/accounts/{n}/fund", number).header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":50.00}")).andReturn();
        }

        assertThat(balance(number)).isEqualTo(50.0);
    }

    @Test
    void banksAndNameEnquiryAreAvailable() throws Exception {
        mvc.perform(get("/api/banks")).andExpect(status().isOk()).andExpect(jsonPath("$[0].code").exists());
        mvc.perform(get("/api/banks/resolve").param("accountNumber", "0123456789").param("bankCode", "058"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountName").value("SIMULATED ACCOUNT HOLDER"));
        mvc.perform(get("/api/banks/resolve").param("accountNumber", "12").param("bankCode", "058"))
                .andExpect(status().isBadRequest());
    }

    /** Same as send(), but the destination is another PayBridge account (bank code 000000). */
    private MvcResult sendInternal(String source, String destination, String amount, String key) throws Exception {
        String body = """
                {"sourceAccount":"%s","destination":{"accountNumber":"%s","bankCode":"000000"},
                 "amount":%s,"currency":"NGN","reason":"internal test"}""".formatted(source, destination, amount);
        return mvc.perform(post("/api/transfers").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    @Test
    void internalTransferMovesMoneyToTheOtherAccount() throws Exception {
        String from = fundedAccount("10000.00");
        String to = fundedAccount("500.00");

        MvcResult result = sendInternal(from, to, "2500.00", "k-" + UUID.randomUUID());

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(field(result, "$.status")).isEqualTo("SUCCESS");
        assertThat(balance(from)).isEqualTo(7500.0);
        assertThat(balance(to)).isEqualTo(3000.0);
    }

    @Test
    void internalTransferRepeatedWithTheSameKeyMovesMoneyOnce() throws Exception {
        String from = fundedAccount("10000.00");
        String to = fundedAccount("0.01");
        String key = "k-" + UUID.randomUUID();

        MvcResult first = sendInternal(from, to, "1000.00", key);
        MvcResult second = sendInternal(from, to, "1000.00", key);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(balance(from)).isEqualTo(9000.0);
        assertThat(balance(to)).isEqualTo(1000.01);
    }

    @Test
    void internalTransferWithNotEnoughMoneyFailsAndTouchesNothing() throws Exception {
        String from = fundedAccount("100.00");
        String to = fundedAccount("50.00");

        MvcResult result = sendInternal(from, to, "500.00", "k-" + UUID.randomUUID());

        assertThat(field(result, "$.status")).isEqualTo("FAILED");
        assertThat(balance(from)).isEqualTo(100.0);
        assertThat(balance(to)).isEqualTo(50.0);
    }

    @Test
    void internalTransferToAnUnknownOrTheSameAccountIsRejected() throws Exception {
        String from = fundedAccount("1000.00");

        assertThat(sendInternal(from, "9099999999", "10.00", "k-" + UUID.randomUUID())
                .getResponse().getStatus()).isEqualTo(404);
        assertThat(sendInternal(from, from, "10.00", "k-" + UUID.randomUUID())
                .getResponse().getStatus()).isEqualTo(400);
        assertThat(balance(from)).isEqualTo(1000.0);
    }

    @Test
    void payBridgeIsListedAsADestinationAndItsAccountsCanBeLookedUp() throws Exception {
        String account = fundedAccount("10.00");

        mvc.perform(get("/api/banks")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='000000')]").exists());
        mvc.perform(get("/api/banks/resolve").param("accountNumber", account).param("bankCode", "000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountName").value("Test Holder"));
    }

    @Test
    void healthEndpointReportsUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}
