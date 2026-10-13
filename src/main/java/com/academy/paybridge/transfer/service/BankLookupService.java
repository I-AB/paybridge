package com.academy.paybridge.transfer.service;

import com.academy.paybridge.transfer.client.BankInfo;
import com.academy.paybridge.transfer.client.ResolvedAccount;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.domain.InvalidTransferException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/** Bank list and name enquiry for the UI. The bank list rarely changes, so it is cached for an hour. */
@Service
public class BankLookupService {

    private static final Duration CACHE_FOR = Duration.ofHours(1);
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("\\d{10}");
    private static final Pattern BANK_CODE = Pattern.compile("\\d{3,6}");

    private final TransferGateway gateway;
    private final Clock clock;

    private volatile List<BankInfo> cached = List.of();
    private volatile Instant cachedAt = Instant.EPOCH;

    public BankLookupService(TransferGateway gateway, Clock clock) {
        this.gateway = gateway;
        this.clock = clock;
    }

    public List<BankInfo> banks() {
        Instant now = clock.instant();
        List<BankInfo> snapshot = cached;
        if (snapshot.isEmpty() || cachedAt.plus(CACHE_FOR).isBefore(now)) {
            snapshot = gateway.listBanks();
            if (!snapshot.isEmpty()) {
                cached = snapshot;
                cachedAt = now;
            }
        }
        return snapshot;
    }

    public ResolvedAccount resolve(String accountNumber, String bankCode) {
        if (accountNumber == null || !ACCOUNT_NUMBER.matcher(accountNumber).matches()) {
            throw new InvalidTransferException("accountNumber must be exactly 10 digits");
        }
        if (bankCode == null || !BANK_CODE.matcher(bankCode).matches()) {
            throw new InvalidTransferException("bankCode must be 3 to 6 digits");
        }
        return gateway.resolveAccount(accountNumber, bankCode);
    }
}
