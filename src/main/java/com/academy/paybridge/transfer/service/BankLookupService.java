package com.academy.paybridge.transfer.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountNotFoundException;
import com.academy.paybridge.transfer.client.BankInfo;
import com.academy.paybridge.transfer.client.ResolvedAccount;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.domain.InvalidTransferException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Bank list and name enquiry for the UI. The bank list rarely changes, so it is cached for an hour. */
@Service
public class BankLookupService {

    private static final Duration CACHE_FOR = Duration.ofHours(1);
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("\\d{10}");
    private static final Pattern BANK_CODE = Pattern.compile("\\d{3,6}");

    /** Listed after the real banks. Choosing it sends money to another PayBridge account, not to a bank. */
    private static final BankInfo PAYBRIDGE = new BankInfo("PayBridge wallet (another PayBridge account)",
            TransferService.INTERNAL_BANK_CODE);

    private final TransferGateway gateway;
    private final AccountApi accounts;
    private final Clock clock;

    private volatile List<BankInfo> cached = List.of();
    private volatile Instant cachedAt = Instant.EPOCH;

    public BankLookupService(TransferGateway gateway, AccountApi accounts, Clock clock) {
        this.gateway = gateway;
        this.accounts = accounts;
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
        List<BankInfo> all = new ArrayList<>(snapshot);
        all.add(PAYBRIDGE);
        return all;
    }

    public ResolvedAccount resolve(String accountNumber, String bankCode) {
        if (accountNumber == null || !ACCOUNT_NUMBER.matcher(accountNumber).matches()) {
            throw new InvalidTransferException("accountNumber must be exactly 10 digits");
        }
        if (bankCode == null || !BANK_CODE.matcher(bankCode).matches()) {
            throw new InvalidTransferException("bankCode must be 3 to 6 digits");
        }
        if (TransferService.INTERNAL_BANK_CODE.equals(bankCode)) {
            return accounts.find(accountNumber)
                    .map(a -> new ResolvedAccount(a.accountNumber(), a.holderName()))
                    .orElseThrow(() -> new AccountNotFoundException(accountNumber));
        }
        return gateway.resolveAccount(accountNumber, bankCode);
    }
}
