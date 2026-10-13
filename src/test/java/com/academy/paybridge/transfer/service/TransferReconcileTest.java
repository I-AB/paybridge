package com.academy.paybridge.transfer.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.client.GatewayRejectedException;
import com.academy.paybridge.transfer.client.GatewayStatus;
import com.academy.paybridge.transfer.client.GatewayTransferResult;
import com.academy.paybridge.transfer.client.GatewayUnavailableException;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.domain.TransferStatus;
import com.academy.paybridge.transfer.repository.WebhookAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The background safety net: stale PENDING transfers and refunds that did not complete. */
class TransferReconcileTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration MIN_AGE = Duration.ofSeconds(30);
    private static final Duration GIVE_UP = Duration.ofMinutes(10);

    private InMemoryTransferRepository repository;
    private TransferGateway gateway;
    private AccountApi accounts;
    private TransferService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTransferRepository();
        gateway = mock(TransferGateway.class);
        accounts = mock(AccountApi.class);
        service = new TransferService(repository, gateway, accounts, mock(WebhookAuditRepository.class),
                new TransferProperties(false, true), CLOCK);
    }

    private Transfer pendingTransferCreated(Duration ago) {
        UUID id = UUID.randomUUID();
        Instant created = NOW.minus(ago);
        Transfer t = new Transfer(id, "key-" + id, "fp", "pb-" + id, "9000000001", "0123456789", "058", null,
                new Money(50_000, Currency.getInstance("NGN")), null, TransferStatus.PENDING, null, null, created, created);
        repository.insert(t);
        return t;
    }

    @Test
    void settlesAStalePendingTransferFromTheProviderAnswer() {
        Transfer t = pendingTransferCreated(Duration.ofMinutes(2));
        when(gateway.fetch(t.reference())).thenReturn(new GatewayTransferResult(GatewayStatus.SUCCESS, "TRF_1", "ok"));

        int touched = service.reconcile(MIN_AGE, GIVE_UP, 10);

        assertThat(touched).isEqualTo(1);
        assertThat(repository.findById(t.id()).orElseThrow().status()).isEqualTo(TransferStatus.SUCCESS);
        verify(accounts, never()).reverse(anyString(), anyString(), anyString());
    }

    @Test
    void failedAnswerReturnsTheHeldMoney() {
        Transfer t = pendingTransferCreated(Duration.ofMinutes(2));
        when(gateway.fetch(t.reference())).thenReturn(new GatewayTransferResult(GatewayStatus.FAILED, "TRF_1", "declined"));
        when(accounts.reverse(any(), any(), any())).thenReturn(true);

        service.reconcile(MIN_AGE, GIVE_UP, 10);

        assertThat(repository.findById(t.id()).orElseThrow().status()).isEqualTo(TransferStatus.FAILED);
        verify(accounts).reverse("hold:" + t.reference(), "refund:" + t.reference(),
                "Refund of failed transfer " + t.reference());
        assertThat(repository.isFundsReleased(t.id())).isTrue();
    }

    @Test
    void youngTransfersAreLeftAlone() {
        Transfer t = pendingTransferCreated(Duration.ofSeconds(5));

        int touched = service.reconcile(MIN_AGE, GIVE_UP, 10);

        assertThat(touched).isZero();
        assertThat(repository.findById(t.id()).orElseThrow().status()).isEqualTo(TransferStatus.PENDING);
        verify(gateway, never()).fetch(anyString());
    }

    @Test
    void providerWithNoRecordIsGivenTimeBeforeWeGiveUp() {
        Transfer recent = pendingTransferCreated(Duration.ofMinutes(2));
        when(gateway.fetch(recent.reference())).thenThrow(new GatewayRejectedException("Transfer not found"));

        service.reconcile(MIN_AGE, GIVE_UP, 10);

        assertThat(repository.findById(recent.id()).orElseThrow().status()).isEqualTo(TransferStatus.PENDING);
    }

    @Test
    void providerWithNoRecordAfterTheGiveUpWindowFailsAndRefunds() {
        Transfer old = pendingTransferCreated(Duration.ofMinutes(30));
        when(gateway.fetch(old.reference())).thenThrow(new GatewayRejectedException("Transfer not found"));

        service.reconcile(MIN_AGE, GIVE_UP, 10);

        Transfer after = repository.findById(old.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(after.failureReason()).contains("no record");
        verify(accounts).reverse("hold:" + old.reference(), "refund:" + old.reference(),
                "Refund of failed transfer " + old.reference());
    }

    @Test
    void providerOutageLeavesThePendingTransferUntouched() {
        Transfer t = pendingTransferCreated(Duration.ofMinutes(30));
        when(gateway.fetch(t.reference())).thenThrow(new GatewayUnavailableException("down"));

        service.reconcile(MIN_AGE, GIVE_UP, 10);

        assertThat(repository.findById(t.id()).orElseThrow().status()).isEqualTo(TransferStatus.PENDING);
    }

    @Test
    void aRefundThatFailedEarlierIsRetried() {
        Transfer t = pendingTransferCreated(Duration.ofMinutes(2));
        repository.markIfPending(t.id(), TransferStatus.FAILED, "declined", NOW);
        when(accounts.reverse(any(), any(), any()))
                .thenThrow(new IllegalStateException("database down"))
                .thenReturn(true);

        service.reconcile(MIN_AGE, GIVE_UP, 10);
        assertThat(repository.isFundsReleased(t.id())).isFalse();

        service.reconcile(MIN_AGE, GIVE_UP, 10);
        assertThat(repository.isFundsReleased(t.id())).isTrue();
    }
}
