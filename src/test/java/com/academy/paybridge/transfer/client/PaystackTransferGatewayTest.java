package com.academy.paybridge.transfer.client;

import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.client.paystack.PaystackProperties;
import com.academy.paybridge.transfer.client.paystack.PaystackTransferGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

/** Talks to a MOCK Paystack: no network, no real key. Proves our request shapes and error handling. */
class PaystackTransferGatewayTest {

    private static final String BASE = "https://api.paystack.test";
    private static final String KEY = "sk_test_unit_secret";

    private MockRestServiceServer server;
    private PaystackTransferGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new PaystackTransferGateway(builder.build(),
                new PaystackProperties(BASE, KEY, Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    private static GatewayTransferRequest request() {
        return new GatewayTransferRequest("pb-ref-0001", new Money(500_050, Currency.getInstance("NGN")),
                "0123456789", "058", "ADA OBI", "School fees");
    }

    private static final String RECIPIENT_OK = "{\"status\":true,\"message\":\"Transfer recipient created successfully\",\"data\":{\"recipient_code\":\"RCP_abc\",\"id\":1}}";

    private void expectRecipientCall() {
        server.expect(requestTo(BASE + "/transferrecipient"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer " + KEY))
                .andExpect(jsonPath("$.type").value("nuban"))
                .andExpect(jsonPath("$.account_number").value("0123456789"))
                .andExpect(jsonPath("$.bank_code").value("058"))
                .andExpect(jsonPath("$.currency").value("NGN"))
                .andRespond(withSuccess(RECIPIENT_OK, MediaType.APPLICATION_JSON));
    }

    private void expectTransferCall(String responseJson) {
        server.expect(requestTo(BASE + "/transfer"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer " + KEY))
                .andExpect(jsonPath("$.source").value("balance"))
                .andExpect(jsonPath("$.amount").value(500_050))
                .andExpect(jsonPath("$.recipient").value("RCP_abc"))
                .andExpect(jsonPath("$.reference").value("pb-ref-0001"))
                .andExpect(jsonPath("$.currency").value("NGN"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));
    }

    @Test
    void listBanksReadsNameAndCode() {
        server.expect(requestTo(BASE + "/bank?country=nigeria&perPage=200"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer " + KEY))
                .andRespond(withSuccess("{\"status\":true,\"message\":\"Banks retrieved\",\"data\":[{\"name\":\"Access Bank\",\"code\":\"044\",\"slug\":\"access-bank\"},{\"name\":\"GTBank\",\"code\":\"058\"}]}", MediaType.APPLICATION_JSON));

        assertThat(gateway.listBanks()).containsExactly(new BankInfo("Access Bank", "044"), new BankInfo("GTBank", "058"));
        server.verify();
    }

    @Test
    void resolveAccountReturnsTheAccountName() {
        server.expect(requestTo(BASE + "/bank/resolve?account_number=0123456789&bank_code=058"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer " + KEY))
                .andRespond(withSuccess("{\"status\":true,\"message\":\"Account number resolved\",\"data\":{\"account_number\":\"0123456789\",\"account_name\":\"ADA OBI\"}}", MediaType.APPLICATION_JSON));

        assertThat(gateway.resolveAccount("0123456789", "058").accountName()).isEqualTo("ADA OBI");
        server.verify();
    }

    @Test
    void sendCreatesRecipientThenTransferAndMapsSuccess() {
        expectRecipientCall();
        expectTransferCall("{\"status\":true,\"message\":\"Transfer has been queued\",\"data\":{\"reference\":\"pb-ref-0001\",\"transfer_code\":\"TRF_xyz\",\"status\":\"success\",\"amount\":500050}}");

        GatewayTransferResult result = gateway.send(request());

        assertThat(result.status()).isEqualTo(GatewayStatus.SUCCESS);
        assertThat(result.providerTransferCode()).isEqualTo("TRF_xyz");
        server.verify();
    }

    @Test
    void sendMapsPendingOtpAndUnknownStatusesToPending() {
        for (String providerStatus : new String[] {"pending", "otp", "received"}) {
            setUp();
            expectRecipientCall();
            expectTransferCall("{\"status\":true,\"message\":\"queued\",\"data\":{\"reference\":\"pb-ref-0001\",\"transfer_code\":\"TRF_xyz\",\"status\":\"" + providerStatus + "\"}}");

            assertThat(gateway.send(request()).status()).as(providerStatus).isEqualTo(GatewayStatus.PENDING);
        }
    }

    @Test
    void sendMapsFailedAndReversedToFailed() {
        for (String providerStatus : new String[] {"failed", "reversed"}) {
            setUp();
            expectRecipientCall();
            expectTransferCall("{\"status\":true,\"message\":\"x\",\"data\":{\"reference\":\"pb-ref-0001\",\"transfer_code\":\"TRF_xyz\",\"status\":\"" + providerStatus + "\"}}");

            assertThat(gateway.send(request()).status()).as(providerStatus).isEqualTo(GatewayStatus.FAILED);
        }
    }

    @Test
    void clientErrorFromProviderBecomesRejectedWithProviderMessage() {
        expectRecipientCall();
        server.expect(requestTo(BASE + "/transfer"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":false,\"message\":\"Your balance is not enough to fulfil this request\"}"));

        assertThatThrownBy(() -> gateway.send(request()))
                .isInstanceOf(GatewayRejectedException.class)
                .hasMessage("Your balance is not enough to fulfil this request");
    }

    @Test
    void serverErrorBecomesUnavailableBecauseTheOutcomeIsUnknown() {
        expectRecipientCall();
        server.expect(requestTo(BASE + "/transfer")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> gateway.send(request())).isInstanceOf(GatewayUnavailableException.class);
    }

    @Test
    void rateLimitingBecomesUnavailable() {
        server.expect(requestTo(BASE + "/transferrecipient")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> gateway.send(request())).isInstanceOf(GatewayUnavailableException.class);
    }

    @Test
    void unauthorizedFromProviderBecomesConfigurationErrorWithoutLeakingTheKey() {
        server.expect(requestTo(BASE + "/transferrecipient")).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"status\":false,\"message\":\"Invalid key\"}"));

        assertThatThrownBy(() -> gateway.send(request()))
                .isInstanceOf(GatewayConfigurationException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(KEY));
    }

    @Test
    void garbageResponseBecomesUnavailable() {
        expectRecipientCall();
        server.expect(requestTo(BASE + "/transfer"))
                .andRespond(withSuccess("<html>oops</html>", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.send(request())).isInstanceOf(GatewayUnavailableException.class);
    }

    @Test
    void fetchReadsTheStatusByReference() {
        server.expect(requestTo(BASE + "/transfer/verify/pb-ref-0001"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"status\":true,\"message\":\"Transfer retrieved\",\"data\":{\"reference\":\"pb-ref-0001\",\"transfer_code\":\"TRF_xyz\",\"status\":\"success\"}}", MediaType.APPLICATION_JSON));

        GatewayTransferResult result = gateway.fetch("pb-ref-0001");

        assertThat(result.status()).isEqualTo(GatewayStatus.SUCCESS);
        server.verify();
    }

    @Test
    void neverCallsTheProviderWithoutATestKey() {
        PaystackTransferGateway noKey = new PaystackTransferGateway(RestClient.builder().baseUrl(BASE).build(),
                new PaystackProperties(BASE, "", Duration.ofSeconds(1), Duration.ofSeconds(1)));
        PaystackTransferGateway liveKey = new PaystackTransferGateway(RestClient.builder().baseUrl(BASE).build(),
                new PaystackProperties(BASE, "sk_live_nope", Duration.ofSeconds(1), Duration.ofSeconds(1)));

        assertThatThrownBy(() -> noKey.send(request())).isInstanceOf(GatewayConfigurationException.class);
        assertThatThrownBy(() -> liveKey.send(request()))
                .isInstanceOf(GatewayConfigurationException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("sk_live_nope"));
    }

    @Test
    void propertiesToStringHidesTheKey() {
        assertThat(new PaystackProperties(BASE, KEY, Duration.ofSeconds(1), Duration.ofSeconds(1)).toString()).doesNotContain(KEY);
    }
}
