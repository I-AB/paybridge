package com.academy.paybridge.transfer.service;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The business logic of a transfer. It depends on the {@link TransferGateway} interface and on
 * {@link TransferRepository}, never on Paystack or on SQL.
 *
 * <p>Flow of {@link #initiate}: validate, check idempotency, (optionally) resolve the account name,
 * save the transfer as PENDING, ask the provider to send it, record the answer.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("NGN");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_.:-]{8,100}");
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("\\d{10}");
    private static final Pattern BANK_CODE = Pattern.compile("\\d{3,6}");
    private static final int MAX_FAILURE_REASON = 255;

    private final TransferRepository repository;
    private final TransferGateway gateway;
    private final TransferProperties properties;
    private final Clock clock;

    public TransferService(TransferRepository repository, TransferGateway gateway,
                           TransferProperties properties, Clock clock) {
        this.repository = repository;
        this.gateway = gateway;
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

        dispatch(transfer);
        return new TransferOutcome(get(id), false);
    }

    public Transfer get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new TransferNotFoundException(id));
    }

    /** Asks the provider for the latest status of a PENDING transfer. Useful when no webhook arrives. */
    public Transfer sync(UUID id) {
        Transfer transfer = get(id);
        if (transfer.status().isFinal()) {
            return transfer;
        }
        GatewayTransferResult result = gateway.fetch(transfer.reference());
        recordProviderCode(id, result.providerTransferCode());
        applyStatus(id, result.status(), result.message());
        return get(id);
    }

    /** Applies a verified provider webhook event. Safe to call many times with the same event. */
    public WebhookResult applyProviderEvent(GatewayTransferEvent event) {
        Optional<Transfer> found = repository.findByReference(event.reference());
        if (found.isEmpty()) {
            log.warn("Webhook received for unknown reference {}", event.reference());
            return WebhookResult.UNKNOWN_REFERENCE;
        }
        Transfer transfer = found.get();
        recordProviderCode(transfer.id(), event.providerTransferCode());
        if (event.status() == GatewayStatus.PENDING) {
            return WebhookResult.IGNORED;
        }
        boolean changed = applyStatus(transfer.id(), event.status(), event.message());
        log.info("Webhook for transfer {} -> {} ({})", transfer.id(), event.status(), changed ? "applied" : "already final");
        return changed ? WebhookResult.APPLIED : WebhookResult.DUPLICATE;
    }

    private void dispatch(Transfer transfer) {
        try {
            GatewayTransferResult result = gateway.send(new GatewayTransferRequest(
                    transfer.reference(), transfer.amount(), transfer.destinationAccountNumber(),
                    transfer.destinationBankCode(), transfer.destinationAccountName(), transfer.reason()));
            recordProviderCode(transfer.id(), result.providerTransferCode());
            applyStatus(transfer.id(), result.status(), result.message());
        } catch (GatewayRejectedException e) {
            // Definite "no" from the provider: nothing was sent, so the transfer is FAILED.
            applyStatus(transfer.id(), GatewayStatus.FAILED, e.getMessage());
        } catch (GatewayUnavailableException e) {
            // Timeout or provider error: we do NOT know if the money moved. Never mark FAILED here,
            // because a retry could pay twice. Stay PENDING and let the webhook or sync decide.
            log.warn("Provider unavailable for transfer {}, outcome unknown, leaving PENDING", transfer.id());
        } catch (GatewayConfigurationException e) {
            applyStatus(transfer.id(), GatewayStatus.FAILED, "Payment provider is not configured");
            throw e;
        }
    }

    /** @return true if this call changed the transfer, false if it was already final or still pending */
    private boolean applyStatus(UUID id, GatewayStatus status, String message) {
        Instant now = clock.instant();
        return switch (status) {
            case SUCCESS -> repository.markIfPending(id, TransferStatus.SUCCESS, null, now);
            case FAILED -> repository.markIfPending(id, TransferStatus.FAILED, abbreviate(message), now);
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
