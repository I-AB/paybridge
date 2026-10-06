package com.academy.paybridge.transfer.web;

import com.academy.paybridge.shared.exception.GlobalExceptionHandler;
import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.client.GatewayUnavailableException;
import com.academy.paybridge.transfer.domain.IdempotencyKeyReuseException;
import com.academy.paybridge.transfer.domain.InvalidTransferException;
import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.domain.TransferNotFoundException;
import com.academy.paybridge.transfer.domain.TransferStatus;
import com.academy.paybridge.transfer.service.CreateTransferCommand;
import com.academy.paybridge.transfer.service.TransferOutcome;
import com.academy.paybridge.transfer.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP behaviour only. The service is mocked, so these tests prove status codes, JSON and validation. */
class TransferControllerTest {

    private static final String VALID_BODY = """
            {"sourceAccount":"wallet-001",
             "destination":{"accountNumber":"0123456789","bankCode":"058"},
             "amount":5000.00,"currency":"NGN","reason":"School fees"}""";

    private TransferService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(TransferService.class);
        mvc = MockMvcBuilders.standaloneSetup(new TransferController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static Transfer transfer(TransferStatus status, String failure) {
        Instant now = Instant.parse("2026-10-06T10:00:00Z");
        return new Transfer(UUID.fromString("11111111-1111-1111-1111-111111111111"), "demo-key-0001", "fp", "pb-ref",
                "wallet-001", "0123456789", "058", "ADA OBI", new Money(500_000, Currency.getInstance("NGN")),
                "School fees", status, "TRF_abc", failure, now, now);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder createRequest(String body) {
        return post("/api/transfers").header("Idempotency-Key", "demo-key-0001")
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void successfulTransferReturns201WithBodyAndLocation() throws Exception {
        when(service.initiate(any())).thenReturn(new TransferOutcome(transfer(TransferStatus.SUCCESS, null), false));

        mvc.perform(createRequest(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/transfers/11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.amount").value(5000.0))
                .andExpect(jsonPath("$.currency").value("NGN"))
                .andExpect(jsonPath("$.destination.accountName").value("ADA OBI"));
    }

    @Test
    void passesRequestFieldsAndIdempotencyKeyToTheService() throws Exception {
        when(service.initiate(any())).thenReturn(new TransferOutcome(transfer(TransferStatus.SUCCESS, null), false));

        mvc.perform(createRequest(VALID_BODY)).andExpect(status().isCreated());

        org.mockito.ArgumentCaptor<CreateTransferCommand> captor = org.mockito.ArgumentCaptor.forClass(CreateTransferCommand.class);
        verify(service).initiate(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().idempotencyKey()).isEqualTo("demo-key-0001");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().destinationBankCode()).isEqualTo("058");
    }

    @Test
    void pendingTransferReturns202() throws Exception {
        when(service.initiate(any())).thenReturn(new TransferOutcome(transfer(TransferStatus.PENDING, null), false));

        mvc.perform(createRequest(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void providerFailureIsReportedInTheBodyOfARecordedTransfer() throws Exception {
        when(service.initiate(any())).thenReturn(new TransferOutcome(transfer(TransferStatus.FAILED, "Your balance is not enough"), false));

        mvc.perform(createRequest(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("Your balance is not enough"));
    }

    @Test
    void idempotentReplayReturns200WithReplayHeader() throws Exception {
        when(service.initiate(any())).thenReturn(new TransferOutcome(transfer(TransferStatus.SUCCESS, null), true));

        mvc.perform(createRequest(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void missingIdempotencyKeyReturns400() throws Exception {
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
        verify(service, never()).initiate(any());
    }

    @Test
    void invalidBodyReturns400WithFieldErrorsAndNeverReachesTheService() throws Exception {
        String bad = """
                {"sourceAccount":"",
                 "destination":{"accountNumber":"123","bankCode":"058"},
                 "amount":-5,"currency":"ngn"}""";

        mvc.perform(createRequest(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.sourceAccount").exists())
                .andExpect(jsonPath("$.fieldErrors['destination.accountNumber']").exists())
                .andExpect(jsonPath("$.fieldErrors.amount").exists())
                .andExpect(jsonPath("$.fieldErrors.currency").exists());
        verify(service, never()).initiate(any());
    }

    @Test
    void missingDestinationReturns400() throws Exception {
        mvc.perform(createRequest("{\"sourceAccount\":\"w\",\"amount\":10,\"currency\":\"NGN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.destination").exists());
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mvc.perform(createRequest("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void businessRuleViolationReturns400() throws Exception {
        when(service.initiate(any())).thenThrow(new InvalidTransferException("Unsupported currency"));

        mvc.perform(createRequest(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSFER"));
    }

    @Test
    void reusedKeyWithDifferentBodyReturns422() throws Exception {
        when(service.initiate(any())).thenThrow(new IdempotencyKeyReuseException());

        mvc.perform(createRequest(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void unexpectedErrorsReturn500WithoutLeakingDetails() throws Exception {
        when(service.initiate(any())).thenThrow(new IllegalStateException("jdbc:h2:mem password=secret"));

        mvc.perform(createRequest(VALID_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }

    @Test
    void getReturnsTheTransfer() throws Exception {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(service.get(id)).thenReturn(transfer(TransferStatus.SUCCESS, null));

        mvc.perform(get("/api/transfers/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void getUnknownTransferReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenThrow(new TransferNotFoundException(id));

        mvc.perform(get("/api/transfers/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void getWithInvalidIdReturns400() throws Exception {
        mvc.perform(get("/api/transfers/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void syncReturnsUpdatedTransfer() throws Exception {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        when(service.sync(id)).thenReturn(transfer(TransferStatus.SUCCESS, null));

        mvc.perform(post("/api/transfers/{id}/sync", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void syncReturns503WhenProviderIsDown() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.sync(id)).thenThrow(new GatewayUnavailableException("Payment provider is unavailable or timed out"));

        mvc.perform(post("/api/transfers/{id}/sync", id))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PROVIDER_UNAVAILABLE"));
    }
}
