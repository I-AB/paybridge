package com.academy.paybridge.transfer.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.transfer.client.GatewayConfigurationException;
import com.academy.paybridge.transfer.client.GatewayRejectedException;
import com.academy.paybridge.transfer.client.GatewayStatus;
import com.academy.paybridge.transfer.client.GatewayTransferEvent;
import com.academy.paybridge.transfer.client.GatewayTransferRequest;
import com.academy.paybridge.transfer.client.GatewayTransferResult;
import com.academy.paybridge.transfer.client.GatewayUnavailableException;
import com.academy.paybridge.transfer.client.ResolvedAccount;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.domain.IdempotencyKeyReuseException;
import com.academy.paybridge.transfer.domain.InvalidTransferException;
import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.domain.TransferNotFoundException;
import com.academy.paybridge.transfer.domain.TransferStatus;
import com.academy.paybridge.transfer.repository.WebhookAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Business logic tests. The gateway is mocked, so no network and no Paystack are involved. */
class TransferServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneOffset.UTC);
    private static final String KEY = "demo-key-0001";

    private InMemoryTransferRepository repository;
    private TransferGateway gateway;
    private AccountApi accounts;
    private WebhookAuditRepository audit;
    private TransferService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTransferRepository();
        gateway = mock(TransferGateway.class);
        accounts = mock(AccountApi.class);
        audit = mock(WebhookAuditRepository.class);
        when(gateway.resolveAccount(any(), any())).thenReturn(new ResolvedAccount("0123456789", "ADA OBI"));
        service = new TransferService(repository, gateway, accounts, audit, new TransferProperties(true, false), CLOCK);
    }

    private CreateTransferCommand command(String key, String amount) {
        return new CreateTransferCommand(key, "wallet-001", "0123456789", "058", new BigDecimal(amount), "NGN", "School fees");
    }

    private void providerAnswers(GatewayStatus status) {
        when(gateway.send(any())).thenReturn(new GatewayTransferResult(status, "TRF_abc", "ok"));
    }

    // ---------- successful initiation ----------

    @Test
    void successfulProviderResponseMakesTransferSuccess() {
        providerAnswers(GatewayStatus.SUCCESS);

        TransferOutcome outcome = service.initiate(command(KEY, "5000.00"));

        Transfer t = outcome.transfer();
        assertThat(outcome.replayed()).isFalse();
        assertThat(t.status()).isEqualTo(TransferStatus.SUCCESS);
        assertThat(t.providerTransferCode()).isEqualTo("TRF_abc");
        assertThat(t.destinationAccountName()).isEqualTo("ADA OBI");
        assertThat(t.failureReason()).isNull();
        assertThat(t.reference()).startsWith("pb-");
    }

    @Test
    void sendsExactMinorUnitsAndOurReferenceToTheProvider() {
        providerAnswers(GatewayStatus.SUCCESS);

        Transfer t = service.initiate(command(KEY, "5000.50")).transfer();

        ArgumentCaptor<GatewayTransferRequest> captor = ArgumentCaptor.forClass(GatewayTransferRequest.class);
        verify(gateway).send(captor.capture());
        assertThat(captor.getValue().amount().minorUnits()).isEqualTo(500_050L);
        assertThat(captor.getValue().reference()).isEqualTo(t.reference());
        assertThat(captor.getValue().accountName()).isEqualTo("ADA OBI");
    }

    // ---------- provider outcomes ----------

    @Test
    void pendingProviderResponseKeepsTransferPending() {
        providerAnswers(GatewayStatus.PENDING);

        Transfer t = service.initiate(command(KEY, "100.00")).transfer();

        assertThat(t.status()).isEqualTo(TransferStatus.PENDING);
        assertThat(t.providerTransferCode()).isEqualTo("TRF_abc");
    }

    @Test
    void providerFailureResponseMakesTransferFailed() {
        providerAnswers(GatewayStatus.FAILED);

        assertThat(service.initiate(command(KEY, "100.00")).transfer().status()).isEqualTo(TransferStatus.FAILED);
    }

    @Test
    void providerRejectionMakesTransferFailedWithReason() {
        when(gateway.send(any())).thenThrow(new GatewayRejectedException("Your balance is not enough"));

        Transfer t = service.initiate(command(KEY, "100.00")).transfer();

        assertThat(t.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(t.failureReason()).isEqualTo("Your balance is not enough");
    }

    @Test
    void providerTimeoutLeavesTransferPendingBecauseOutcomeIsUnknown() {
        when(gateway.send(any())).thenThrow(new GatewayUnavailableException("timed out"));

        Transfer t = service.initiate(command(KEY, "100.00")).transfer();

        assertThat(t.status()).isEqualTo(TransferStatus.PENDING);
        assertThat(t.failureReason()).isNull();
    }

    @Test
    void missingProviderConfigurationFailsTheTransferAndReportsTheProblem() {
        when(gateway.send(any())).thenThrow(new GatewayConfigurationException("not configured"));

        assertThatThrownBy(() -> service.initiate(command(KEY, "100.00")))
                .isInstanceOf(GatewayConfigurationException.class);

        Transfer stored = repository.findByIdempotencyKey(KEY).orElseThrow();
        assertThat(stored.status()).isEqualTo(TransferStatus.FAILED);
    }

    // ---------- validation ----------

    @Test
    void rejectsZeroNegativeAndTooPreciseAmounts() {
        for (String bad : new String[] {"0", "0.00", "-5.00", "10.005"}) {
            assertThatThrownBy(() -> service.initiate(command(KEY, bad)))
                    .as("amount %s", bad)
                    .isInstanceOf(InvalidTransferException.class);
        }
        verifyNoInteractions(gateway);
        assertThat(repository.size()).isZero();
    }

    @Test
    void rejectsUnsupportedOrInvalidCurrency() {
        for (String bad : new String[] {"USD", "XYZ", "ngn", null}) {
            CreateTransferCommand c = new CreateTransferCommand(KEY, "wallet-001", "0123456789", "058", new BigDecimal("10"), bad, null);
            assertThatThrownBy(() -> service.initiate(c)).as("currency %s", bad).isInstanceOf(InvalidTransferException.class);
        }
    }

    @Test
    void rejectsInvalidRequestFields() {
        assertThatThrownBy(() -> service.initiate(new CreateTransferCommand(KEY, " ", "0123456789", "058", BigDecimal.TEN, "NGN", null)))
                .isInstanceOf(InvalidTransferException.class);
        assertThatThrownBy(() -> service.initiate(new CreateTransferCommand(KEY, "w", "12345", "058", BigDecimal.TEN, "NGN", null)))
                .isInstanceOf(InvalidTransferException.class);
        assertThatThrownBy(() -> service.initiate(new CreateTransferCommand(KEY, "w", "0123456789", "abc", BigDecimal.TEN, "NGN", null)))
                .isInstanceOf(InvalidTransferException.class);
        assertThatThrownBy(() -> service.initiate(new CreateTransferCommand(KEY, "w", "0123456789", "058", null, "NGN", null)))
                .isInstanceOf(InvalidTransferException.class);
        assertThatThrownBy(() -> service.initiate(command("short", "10.00")))
                .isInstanceOf(InvalidTransferException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void unresolvableAccountStopsTheTransferBeforeAnythingIsSaved() {
        when(gateway.resolveAccount(any(), any())).thenThrow(new GatewayRejectedException("Could not resolve account name"));

        assertThatThrownBy(() -> service.initiate(command(KEY, "100.00"))).isInstanceOf(GatewayRejectedException.class);

        assertThat(repository.size()).isZero();
        verify(gateway, never()).send(any());
    }

    @Test
    void canSkipNameEnquiryWhenDisabled() {
        service = new TransferService(repository, gateway, accounts, audit, new TransferProperties(false, false), CLOCK);
        providerAnswers(GatewayStatus.SUCCESS);

        Transfer t = service.initiate(command(KEY, "100.00")).transfer();

        verify(gateway, never()).resolveAccount(any(), any());
        assertThat(t.destinationAccountName()).isNull();
    }

    // ---------- idempotency ----------

    @Test
    void sameKeyAndSameRequestReturnsTheSameTransferWithoutCallingTheProviderAgain() {
        providerAnswers(GatewayStatus.SUCCESS);

        TransferOutcome first = service.initiate(command(KEY, "5000.00"));
        TransferOutcome second = service.initiate(command(KEY, "5000.00"));

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.transfer().id()).isEqualTo(first.transfer().id());
        assertThat(repository.size()).isEqualTo(1);
        verify(gateway, times(1)).send(any());
    }

    @Test
    void retryWhileFirstRequestIsStillPendingReturnsPendingAndDoesNotResend() {
        providerAnswers(GatewayStatus.PENDING);

        service.initiate(command(KEY, "5000.00"));
        TransferOutcome retry = service.initiate(command(KEY, "5000.00"));

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.transfer().status()).isEqualTo(TransferStatus.PENDING);
        verify(gateway, times(1)).send(any());
    }

    @Test
    void sameKeyWithDifferentRequestIsRejected() {
        providerAnswers(GatewayStatus.SUCCESS);
        service.initiate(command(KEY, "5000.00"));

        assertThatThrownBy(() -> service.initiate(command(KEY, "9000.00")))
                .isInstanceOf(IdempotencyKeyReuseException.class);
        verify(gateway, times(1)).send(any());
    }

    @Test
    void differentKeysCreateDifferentTransfers() {
        providerAnswers(GatewayStatus.SUCCESS);

        service.initiate(command("key-number-001", "5000.00"));
        service.initiate(command("key-number-002", "5000.00"));

        assertThat(repository.size()).isEqualTo(2);
        verify(gateway, times(2)).send(any());
    }

    @Test
    void twoRequestsRacingPastTheFirstCheckStillCreateOnlyOneTransfer() {
        providerAnswers(GatewayStatus.SUCCESS);
        Transfer winner = service.initiate(command(KEY, "5000.00")).transfer();

        // Simulate the race: this caller's first lookup finds nothing, but the insert then hits the unique key.
        InMemoryTransferRepository racing = spy(repository);
        doReturn(Optional.empty()).doCallRealMethod().when(racing).findByIdempotencyKey(KEY);
        TransferService racingService = new TransferService(racing, gateway, accounts, audit, new TransferProperties(true, false), CLOCK);

        TransferOutcome loser = racingService.initiate(command(KEY, "5000.00"));

        assertThat(loser.replayed()).isTrue();
        assertThat(loser.transfer().id()).isEqualTo(winner.id());
        assertThat(repository.size()).isEqualTo(1);
        verify(gateway, times(1)).send(any());
    }

    // ---------- sync ----------

    @Test
    void syncMovesPendingTransferToTheProvidersFinalStatus() {
        providerAnswers(GatewayStatus.PENDING);
        Transfer pending = service.initiate(command(KEY, "100.00")).transfer();
        when(gateway.fetch(pending.reference())).thenReturn(new GatewayTransferResult(GatewayStatus.SUCCESS, "TRF_abc", "ok"));

        assertThat(service.sync(pending.id()).status()).isEqualTo(TransferStatus.SUCCESS);
    }

    @Test
    void syncDoesNotCallTheProviderForAFinalTransfer() {
        providerAnswers(GatewayStatus.SUCCESS);
        Transfer done = service.initiate(command(KEY, "100.00")).transfer();

        service.sync(done.id());

        verify(gateway, never()).fetch(any());
    }

    @Test
    void unknownTransferIsReportedAsNotFound() {
        assertThatThrownBy(() -> service.get(UUID.randomUUID())).isInstanceOf(TransferNotFoundException.class);
    }

    // ---------- webhook events ----------

    private Transfer pendingTransfer() {
        providerAnswers(GatewayStatus.PENDING);
        return service.initiate(command(KEY, "100.00")).transfer();
    }

    @Test
    void successEventCompletesAPendingTransfer() {
        Transfer t = pendingTransfer();

        WebhookResult result = service.applyProviderEvent(new GatewayTransferEvent(t.reference(), "TRF_abc", GatewayStatus.SUCCESS, null));

        assertThat(result).isEqualTo(WebhookResult.APPLIED);
        assertThat(service.get(t.id()).status()).isEqualTo(TransferStatus.SUCCESS);
    }

    @Test
    void failedEventFailsAPendingTransferAndStoresTheReason() {
        Transfer t = pendingTransfer();

        WebhookResult result = service.applyProviderEvent(new GatewayTransferEvent(t.reference(), "TRF_abc", GatewayStatus.FAILED, "Transfer failed at the payment provider"));

        assertThat(result).isEqualTo(WebhookResult.APPLIED);
        Transfer after = service.get(t.id());
        assertThat(after.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(after.failureReason()).isEqualTo("Transfer failed at the payment provider");
    }

    @Test
    void duplicateEventChangesNothing() {
        // a clock that moves forward on every call, so any accidental update would change updatedAt
        AtomicInteger ticks = new AtomicInteger();
        Clock ticking = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return CLOCK.instant().plusSeconds(ticks.incrementAndGet()); }
        };
        service = new TransferService(repository, gateway, accounts, audit, new TransferProperties(true, false), ticking);
        Transfer t = pendingTransfer();
        GatewayTransferEvent event = new GatewayTransferEvent(t.reference(), "TRF_new", GatewayStatus.SUCCESS, null);

        service.applyProviderEvent(event);
        Instant updatedAfterFirst = service.get(t.id()).updatedAt();
        WebhookResult second = service.applyProviderEvent(event);

        assertThat(second).isEqualTo(WebhookResult.DUPLICATE);
        assertThat(service.get(t.id()).status()).isEqualTo(TransferStatus.SUCCESS);
        assertThat(service.get(t.id()).updatedAt()).isEqualTo(updatedAfterFirst);
    }

    @Test
    void lateConflictingEventCannotOverwriteAFinalStatus() {
        Transfer t = pendingTransfer();
        service.applyProviderEvent(new GatewayTransferEvent(t.reference(), null, GatewayStatus.SUCCESS, null));

        WebhookResult result = service.applyProviderEvent(new GatewayTransferEvent(t.reference(), null, GatewayStatus.FAILED, "late"));

        assertThat(result).isEqualTo(WebhookResult.DUPLICATE);
        assertThat(service.get(t.id()).status()).isEqualTo(TransferStatus.SUCCESS);
    }

    @Test
    void eventForUnknownReferenceIsAcknowledgedButIgnored() {
        WebhookResult result = service.applyProviderEvent(new GatewayTransferEvent("pb-does-not-exist", null, GatewayStatus.SUCCESS, null));

        assertThat(result).isEqualTo(WebhookResult.UNKNOWN_REFERENCE);
    }
}
