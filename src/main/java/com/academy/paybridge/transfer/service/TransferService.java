package com.academy.paybridge.transfer.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountNotFoundException;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.api.InsufficientFundsException;
import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.client.GatewayConfigurationException;
import com.academy.paybridge.transfer.client.GatewayRejectedException;
import com.academy.paybridge.transfer.client.GatewayStatus;
import com.academy.paybridge.transfer.client.GatewayTransferEvent;
import com.academy.paybridge.transfer.client.GatewayTransferRequest;
import com.academy.paybridge.transfer.client.GatewayTransferResult;
import com.academy.paybridge.transfer.client.GatewayUnavailableException;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.domain.IdempotencyKeyReuseException;
import com.academy.paybridge.transfer.domain.InvalidTransferException;
import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.domain.TransferNotFoundException;
import com.academy.paybridge.transfer.domain.TransferStatus;
import com.academy.paybridge.transfer.repository.DuplicateIdempotencyKeyException;
import com.academy.paybridge.transfer.repository.TransferRepository;
import com.academy.paybridge.transfer.repository.WebhookAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The business logic of a transfer. It depends on the {@link TransferGateway} interface and on
 * {@link TransferRepository}, never on Paystack or on SQL.
 *
 * <p>Flow of {@link #initiate}: validate, check idempotency, check the source account, (optionally)
 * resolve the destination name, save the transfer as PENDING, hold the money on the source account,
 * ask the provider to send it, record the answer. If the transfer ends FAILED, the held money is
 * returned exactly once (the ledger reference makes the refund idempotent).
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("NGN");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_.:-]{8,100}");
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("\\d{10}");
    private static final Pattern BANK_CODE = Pattern.compile("\\d{3,6}");
    private static final int MAX_FAILURE_REASON = 255;

    /**
     * The "bank code" that means "another PayBridge account". A transfer with this code never goes to the
     * payment provider: the money moves between two PayBridge accounts in one database transaction.
     */
    public static final String INTERNAL_BANK_CODE = "000000";

    private final TransferRepository repository;
    private final TransferGateway gateway;
    private final AccountApi accounts;
    private final WebhookAuditRepository audit;
    private final TransferProperties properties;
    private final Clock clock;

    public TransferService(TransferRepository repository, TransferGateway gateway, AccountApi accounts,
                           WebhookAuditRepository audit, TransferProperties properties, Clock clock) {
        this.repository = repository;
        this.gateway = gateway;
        this.accounts = accounts;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    public TransferOutcome initiate(CreateTransferCommand command) {
        Money amount = validate(command);
        String fingerprint = fingerprint(command, amount);

        // Idempotency step 1: have we already seen this key?
        Optional<Transfer> existing = repository.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint);
        }

        if (INTERNAL_BANK_CODE.equals(command.destinationBankCode())) {
            return initiateInternal(command, amount, fingerprint);
        }

        if (properties.enforceSourceBalance()) {
            String source = command.sourceAccount().trim();
            AccountView account = accounts.find(source).orElseThrow(() -> new AccountNotFoundException(source));
            if (!account.currency().equals(command.currency())) {
                throw new InvalidTransferException("The source account holds " + account.currency()
                        + ", so the transfer must be in " + account.currency());
            }
        }

        String accountName = properties.resolveAccountBeforeSend()
                ? gateway.resolveAccount(command.destinationAccountNumber(), command.destinationBankCode()).accountName()
                : null;

        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        Transfer transfer = new Transfer(
                id, command.idempotencyKey(), fingerprint, "pb-" + id,
                command.sourceAccount().trim(), command.destinationAccountNumber(), command.destinationBankCode(),
                accountName, amount, blankToNull(command.reason()),
                TransferStatus.PENDING, null, null, now, now);

        // Idempotency step 2: the database has a UNIQUE constraint on the key, so if two identical
        // requests race past step 1, exactly one insert wins and the other is treated as a replay.
        try {
            repository.insert(transfer);
        } catch (DuplicateIdempotencyKeyException e) {
            Transfer winner = repository.findByIdempotencyKey(command.idempotencyKey()).orElseThrow(() -> e);
            return replay(winner, fingerprint);
        }

        if (properties.enforceSourceBalance() && !holdFunds(transfer)) {
            return new TransferOutcome(get(id), false);
        }

        dispatch(transfer);
        return new TransferOutcome(get(id), false);
    }

    /**
     * A transfer between two PayBridge accounts. There is no provider, so there is nothing to wait for:
     * the debit and the credit happen together in {@link AccountApi#transfer}, and the transfer is final
     * (SUCCESS, or FAILED if the balance is too low) before this method returns.
     */
    private TransferOutcome initiateInternal(CreateTransferCommand command, Money amount, String fingerprint) {
        String source = command.sourceAccount().trim();
        String destination = command.destinationAccountNumber();
        AccountView from = accounts.find(source).orElseThrow(() -> new AccountNotFoundException(source));
        AccountView to = accounts.find(destination).orElseThrow(() -> new AccountNotFoundException(destination));
        if (source.equals(destination)) {
            throw new InvalidTransferException("You cannot send money to the same account");
        }
        String currency = command.currency();
        if (!from.currency().equals(currency) || !to.currency().equals(currency)) {
            throw new InvalidTransferException("Both accounts must hold " + currency);
        }

        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        Transfer transfer = new Transfer(
                id, command.idempotencyKey(), fingerprint, "pb-" + id,
                source, destination, INTERNAL_BANK_CODE,
                to.holderName(), amount, blankToNull(command.reason()),
                TransferStatus.PENDING, null, null, now, now);
        try {
            repository.insert(transfer);
        } catch (DuplicateIdempotencyKeyException e) {
            Transfer winner = repository.findByIdempotencyKey(command.idempotencyKey()).orElseThrow(() -> e);
            return replay(winner, fingerprint);
        }

        boolean moved = false;
        try {
            accounts.transfer(source, destination, amount.minorUnits(),
                    holdReference(transfer), "credit:" + transfer.reference(),
                    "Transfer " + transfer.reference(), "Received from " + from.holderName());
            moved = true;
        } catch (InsufficientFundsException e) {
            repository.markIfPending(id, TransferStatus.FAILED, InsufficientFundsException.MESSAGE, clock.instant());
            repository.markFundsReleased(id, clock.instant()); // nothing was taken, so nothing to return
        } catch (RuntimeException e) {
            // AccountApi.transfer is one database transaction, so after any error nothing was moved.
            // Mark it FAILED instead of leaving it PENDING forever (the reconciler skips internal transfers).
            log.error("Internal transfer {} failed", transfer.reference(), e);
            repository.markIfPending(id, TransferStatus.FAILED, "Transfer could not be completed", clock.instant());
            repository.markFundsReleased(id, clock.instant());
        }
        if (moved) {
            repository.markIfPending(id, TransferStatus.SUCCESS, null, clock.instant());
        }
        return new TransferOutcome(get(id), false);
    }

    public Transfer get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new TransferNotFoundException(id));
    }

    /** Asks the provider for the latest status of a PENDING transfer. Useful when no webhook arrives. */
    public Transfer sync(UUID id) {
        Transfer transfer = get(id);
        if (transfer.status().isFinal() || INTERNAL_BANK_CODE.equals(transfer.destinationBankCode())) {
            return transfer; // internal transfers never went to a provider, so there is nobody to ask
        }
        GatewayTransferResult result = gateway.fetch(transfer.reference());
        recordProviderCode(id, result.providerTransferCode());
        applyStatus(transfer, result.status(), result.message());
        return get(id);
    }

    /** Newest transfers first, for dashboards. */
    public List<Transfer> recent(int limit) {
        return repository.findRecent(Math.max(1, Math.min(limit, 100)));
    }

    /** Applies a verified provider webhook event. Safe to call many times with the same event. */
    public WebhookResult applyProviderEvent(GatewayTransferEvent event) {
        WebhookResult result = applyEvent(event);
        try {
            audit.record(event.reference(), event.status().name(), event.providerTransferCode(),
                    event.amountMinorUnits(), result.name(), clock.instant());
        } catch (RuntimeException e) {
            // the audit trail must never make the provider retry an event we already handled
            log.warn("Could not write webhook audit row for {}", event.reference(), e);
        }
        return result;
    }

    private WebhookResult applyEvent(GatewayTransferEvent event) {
        Optional<Transfer> found = repository.findByReference(event.reference());
        if (found.isEmpty()) {
            log.warn("Webhook received for unknown reference {}", event.reference());
            return WebhookResult.UNKNOWN_REFERENCE;
        }
        Transfer transfer = found.get();
        if (event.amountMinorUnits() != null && event.amountMinorUnits() != transfer.amount().minorUnits()) {
            // Never trust an event that talks about a different amount. The reconciler will ask the provider directly.
            log.warn("Webhook amount {} does not match transfer {} amount {}",
                    event.amountMinorUnits(), transfer.id(), transfer.amount().minorUnits());
            return WebhookResult.AMOUNT_MISMATCH;
        }
        recordProviderCode(transfer.id(), event.providerTransferCode());
        if (event.status() == GatewayStatus.PENDING) {
            return WebhookResult.IGNORED;
        }
        boolean changed = applyStatus(transfer, event.status(), event.message());
        log.info("Webhook for transfer {} -> {} ({})", transfer.id(), event.status(), changed ? "applied" : "already final");
        return changed ? WebhookResult.APPLIED : WebhookResult.DUPLICATE;
    }

    /**
     * Called on a schedule. (1) Asks the provider about PENDING transfers older than {@code minAge}; if the
     * provider has no record of one older than {@code giveUpAfter}, it is marked FAILED. (2) Returns held
     * money for FAILED transfers whose refund has not happened yet (for example after a crash).
     *
     * @return how many transfers were changed or refunded
     */
    public int reconcile(Duration minAge, Duration giveUpAfter, int batchSize) {
        Instant now = clock.instant();
        int touched = 0;
        for (Transfer transfer : repository.findPendingCreatedBefore(now.minus(minAge), batchSize)) {
            if (INTERNAL_BANK_CODE.equals(transfer.destinationBankCode())) {
                continue; // never sent to a provider, so there is nobody to ask
            }
            try {
                GatewayTransferResult result = gateway.fetch(transfer.reference());
                recordProviderCode(transfer.id(), result.providerTransferCode());
                if (applyStatus(transfer, result.status(), result.message())) {
                    touched++;
                }
            } catch (GatewayRejectedException e) {
                // The provider answered "I have no such transfer". Wait a while before concluding it never arrived.
                if (transfer.createdAt().isBefore(now.minus(giveUpAfter))
                        && applyStatus(transfer, GatewayStatus.FAILED, "Provider has no record of this transfer")) {
                    touched++;
                }
            } catch (RuntimeException e) {
                log.warn("Reconcile skipped transfer {}: {}", transfer.id(), e.getMessage());
            }
        }
        for (Transfer transfer : repository.findFailedAwaitingRelease(batchSize)) {
            if (releaseFunds(transfer)) {
                touched++;
            }
        }
        return touched;
    }

    /**
     * Takes the amount out of the source account and keeps it until the transfer settles.
     *
     * @return true if the transfer may now be sent; false if it was failed because the balance is too low
     */
    private boolean holdFunds(Transfer transfer) {
        try {
            accounts.debit(transfer.sourceAccount(), transfer.amount().minorUnits(), holdReference(transfer),
                    "Transfer " + transfer.reference());
            return true;
        } catch (InsufficientFundsException e) {
            repository.markIfPending(transfer.id(), TransferStatus.FAILED, InsufficientFundsException.MESSAGE, clock.instant());
            repository.markFundsReleased(transfer.id(), clock.instant()); // nothing was taken, so nothing to return
            return false;
        }
    }

    /**
     * Returns the money held for a FAILED transfer. Safe to call repeatedly: the refund has its own
     * unique ledger reference, so it can only ever happen once.
     *
     * @return true if the transfer is now marked as released
     */
    private boolean releaseFunds(Transfer transfer) {
        try {
            boolean refunded = accounts.reverse(holdReference(transfer), refundReference(transfer),
                    "Refund of failed transfer " + transfer.reference());
            repository.markFundsReleased(transfer.id(), clock.instant());
            if (refunded) {
                log.info("Returned {} minor units for failed transfer {}", transfer.amount().minorUnits(), transfer.id());
            }
            return true;
        } catch (RuntimeException e) {
            // Not fatal: the transfer stays "awaiting release" and the reconciler tries again.
            log.error("Could not return held money for transfer {}", transfer.id(), e);
            return false;
        }
    }

    private static String holdReference(Transfer t) {
        return "hold:" + t.reference();
    }

    private static String refundReference(Transfer t) {
        return "refund:" + t.reference();
    }

    private void dispatch(Transfer transfer) {
        try {
            GatewayTransferResult result = gateway.send(new GatewayTransferRequest(
                    transfer.reference(), transfer.amount(), transfer.destinationAccountNumber(),
                    transfer.destinationBankCode(), transfer.destinationAccountName(), transfer.reason()));
            recordProviderCode(transfer.id(), result.providerTransferCode());
            applyStatus(transfer, result.status(), result.message());
        } catch (GatewayRejectedException e) {
            // Definite "no" from the provider: nothing was sent, so the transfer is FAILED.
            applyStatus(transfer, GatewayStatus.FAILED, e.getMessage());
        } catch (GatewayUnavailableException e) {
            // Timeout or provider error: we do NOT know if the money moved. Never mark FAILED here,
            // because a retry could pay twice. Stay PENDING and let the webhook or sync decide.
            log.warn("Provider unavailable for transfer {}, outcome unknown, leaving PENDING", transfer.id());
        } catch (GatewayConfigurationException e) {
            applyStatus(transfer, GatewayStatus.FAILED, "Payment provider is not configured");
            throw e;
        }
    }

    /** @return true if this call changed the transfer, false if it was already final or still pending */
    private boolean applyStatus(Transfer transfer, GatewayStatus status, String message) {
        Instant now = clock.instant();
        return switch (status) {
            case SUCCESS -> repository.markIfPending(transfer.id(), TransferStatus.SUCCESS, null, now);
            case FAILED -> {
                boolean changed = repository.markIfPending(transfer.id(), TransferStatus.FAILED, abbreviate(message), now);
                if (changed) {
                    releaseFunds(transfer);
                }
                yield changed;
            }
            case PENDING -> false;
        };
    }

    private void recordProviderCode(UUID id, String providerTransferCode) {
        if (providerTransferCode != null && !providerTransferCode.isBlank()) {
            repository.setProviderTransferCode(id, providerTransferCode, clock.instant());
        }
    }

    private TransferOutcome replay(Transfer existing, String fingerprint) {
        if (!existing.requestFingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyReuseException();
        }
        // Same key, same request: return what we already have, even if it is still PENDING.
        // The provider is NOT called again, so no second transfer can be created.
        return new TransferOutcome(existing, true);
    }

    private Money validate(CreateTransferCommand c) {
        if (c.idempotencyKey() == null || !IDEMPOTENCY_KEY.matcher(c.idempotencyKey()).matches()) {
            throw new InvalidTransferException(
                    "Idempotency-Key must be 8 to 100 characters: letters, digits, '-', '_', '.' or ':' (a UUID is ideal)");
        }
        if (c.sourceAccount() == null || c.sourceAccount().isBlank() || c.sourceAccount().length() > 64) {
            throw new InvalidTransferException("sourceAccount is required (maximum 64 characters)");
        }
        if (c.destinationAccountNumber() == null || !ACCOUNT_NUMBER.matcher(c.destinationAccountNumber()).matches()) {
            throw new InvalidTransferException("destination account number must be exactly 10 digits");
        }
        if (c.destinationBankCode() == null || !BANK_CODE.matcher(c.destinationBankCode()).matches()) {
            throw new InvalidTransferException("destination bank code must be 3 to 6 digits");
        }
        if (c.reason() != null && c.reason().length() > 100) {
            throw new InvalidTransferException("reason must be at most 100 characters");
        }
        if (c.currency() == null || !SUPPORTED_CURRENCIES.contains(c.currency())) {
            throw new InvalidTransferException("Unsupported currency. Supported currencies: " + SUPPORTED_CURRENCIES);
        }
        if (c.amount() == null) {
            throw new InvalidTransferException("amount is required");
        }
        Money money;
        try {
            money = Money.ofMajor(c.amount(), Currency.getInstance(c.currency()));
        } catch (IllegalArgumentException e) {
            throw new InvalidTransferException("amount must have at most 2 decimal places and be a reasonable size");
        }
        if (!money.isPositive()) {
            throw new InvalidTransferException("amount must be greater than zero");
        }
        return money;
    }

    /** A short hash of the request body, used to detect the same key being reused for a different request. */
    private static String fingerprint(CreateTransferCommand c, Money amount) {
        String canonical = String.join("\u001f",
                c.sourceAccount().trim(), c.destinationAccountNumber(), c.destinationBankCode(),
                Long.toString(amount.minorUnits()), amount.currency().getCurrencyCode(),
                c.reason() == null ? "" : c.reason().trim());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MAX_FAILURE_REASON ? message : message.substring(0, MAX_FAILURE_REASON);
    }
}
