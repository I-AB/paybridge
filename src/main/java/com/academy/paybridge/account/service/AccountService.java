package com.academy.paybridge.account.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountNotFoundException;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.api.InsufficientFundsException;
import com.academy.paybridge.account.api.PostingResult;
import com.academy.paybridge.account.domain.Account;
import com.academy.paybridge.account.domain.InvalidAccountRequestException;
import com.academy.paybridge.account.repository.AccountRepository;
import com.academy.paybridge.account.repository.DuplicateAccountNumberException;
import com.academy.paybridge.ledger.api.EntryDirection;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.LedgerEntryView;
import com.academy.paybridge.ledger.api.NewLedgerEntry;
import com.academy.paybridge.shared.exception.PayBridgeException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns balances. The rule for every movement: lock the account row, check the ledger for the
 * reference (so repeats do nothing), change the balance, append the ledger line. All of that
 * happens in one database transaction, so a balance can never disagree with its ledger.
 */
@Service
public class AccountService implements AccountApi {

    /** PayBridge account numbers start with 90, so they never collide with the simulator's test numbers. */
    private static final String NUMBER_PREFIX = "90";
    private static final int OPEN_ATTEMPTS = 5;
    private static final int MAX_LIST = 100;
    /** Largest single sandbox top-up: 10,000,000.00 in kobo. */
    static final long MAX_FUNDING_MINOR = 1_000_000_000L;

    private final AccountRepository accounts;
    private final LedgerApi ledger;
    private final AccountProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AccountService(AccountRepository accounts, LedgerApi ledger, AccountProperties properties, Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Not transactional on purpose: if a random number is already taken, the failed INSERT would
     * poison a PostgreSQL transaction, so each attempt runs on its own.
     */
    @Override
    public AccountView open(String holderName, String currency) {
        String holder = holderName == null ? "" : holderName.trim();
        if (holder.length() < 2 || holder.length() > 100) {
            throw new InvalidAccountRequestException("holderName must be between 2 and 100 characters");
        }
        if (!"NGN".equals(currency)) {
            throw new InvalidAccountRequestException("Only NGN accounts are supported");
        }
        for (int attempt = 0; attempt < OPEN_ATTEMPTS; attempt++) {
            Account account = new Account(UUID.randomUUID(), newAccountNumber(), holder, currency, 0L, clock.instant());
            try {
                accounts.insert(account);
                return view(account);
            } catch (DuplicateAccountNumberException e) {
                // extremely unlikely: pick another number
            }
        }
        throw new PayBridgeException(500, "INTERNAL_ERROR", "Could not allocate an account number");
    }

    @Override
    public Optional<AccountView> find(String accountNumber) {
        return accounts.findByNumber(accountNumber).map(AccountService::view);
    }

    @Override
    public List<AccountView> list(int limit) {
        return accounts.findNewest(Math.max(1, Math.min(limit, MAX_LIST))).stream().map(AccountService::view).toList();
    }

    @Override
    @Transactional
    public PostingResult credit(String accountNumber, long amountMinor, String reference, String description) {
        requirePositive(amountMinor);
        Account account = accounts.lockByNumber(accountNumber).orElseThrow(() -> new AccountNotFoundException(accountNumber));
        if (ledger.findByReference(reference).isPresent()) {
            return new PostingResult(view(account), false);
        }
        return new PostingResult(post(account, EntryDirection.CREDIT, amountMinor, reference, description), true);
    }

    @Override
    @Transactional
    public PostingResult debit(String accountNumber, long amountMinor, String reference, String description) {
        requirePositive(amountMinor);
        Account account = accounts.lockByNumber(accountNumber).orElseThrow(() -> new AccountNotFoundException(accountNumber));
        if (ledger.findByReference(reference).isPresent()) {
            return new PostingResult(view(account), false);
        }
        if (account.balanceMinor() < amountMinor) {
            throw new InsufficientFundsException();
        }
        return new PostingResult(post(account, EntryDirection.DEBIT, amountMinor, reference, description), true);
    }

    @Override
    @Transactional
    public PostingResult transfer(String fromAccount, String toAccount, long amountMinor,
                                  String debitReference, String creditReference,
                                  String debitDescription, String creditDescription) {
        requirePositive(amountMinor);
        if (fromAccount.equals(toAccount)) {
            throw new InvalidAccountRequestException("You cannot send money to the same account");
        }
        // Lock both rows in a fixed order (lowest number first) so that two transfers going in
        // opposite directions at the same time cannot wait for each other forever.
        String first = fromAccount.compareTo(toAccount) < 0 ? fromAccount : toAccount;
        String second = first.equals(fromAccount) ? toAccount : fromAccount;
        accounts.lockByNumber(first).orElseThrow(() -> new AccountNotFoundException(first));
        accounts.lockByNumber(second).orElseThrow(() -> new AccountNotFoundException(second));
        // Same transaction as this method, so a failure in the credit undoes the debit.
        PostingResult debit = debit(fromAccount, amountMinor, debitReference, debitDescription);
        credit(toAccount, amountMinor, creditReference, creditDescription);
        return debit;
    }

    @Override
    @Transactional
    public boolean reverse(String originalReference, String reversalReference, String description) {
        Optional<LedgerEntryView> original = ledger.findByReference(originalReference);
        if (original.isEmpty() || original.get().direction() != EntryDirection.DEBIT) {
            return false;
        }
        LedgerEntryView debit = original.get();
        Account account = accounts.lockByNumber(debit.accountNumber())
                .orElseThrow(() -> new AccountNotFoundException(debit.accountNumber()));
        // checked after taking the lock, so two racing reversals cannot both pass
        if (ledger.findByReference(reversalReference).isPresent()) {
            return false;
        }
        post(account, EntryDirection.CREDIT, debit.amountMinor(), reversalReference, description);
        return true;
    }

    /** Sandbox top-up used by the demo page and Swagger. Real money would arrive through a payment provider instead. */
    @Transactional
    public PostingResult fund(String accountNumber, long amountMinor, String idempotencyKey) {
        if (!properties.sandboxFundingEnabled()) {
            throw new PayBridgeException(403, "FUNDING_DISABLED", "Sandbox funding is switched off");
        }
        if (amountMinor > MAX_FUNDING_MINOR) {
            throw new InvalidAccountRequestException("A single top-up can be at most 10,000,000.00");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64) {
            throw new InvalidAccountRequestException("Idempotency-Key must be 1 to 64 characters");
        }
        return credit(accountNumber, amountMinor, "fund:" + accountNumber + ":" + idempotencyKey.trim(),
                "Sandbox top-up");
    }

    public List<LedgerEntryView> statement(String accountNumber, int limit) {
        if (accounts.findByNumber(accountNumber).isEmpty()) {
            throw new AccountNotFoundException(accountNumber);
        }
        return ledger.entriesFor(accountNumber, limit);
    }

    private AccountView post(Account account, EntryDirection direction, long amountMinor, String reference, String description) {
        long newBalance = direction == EntryDirection.CREDIT
                ? Math.addExact(account.balanceMinor(), amountMinor)
                : account.balanceMinor() - amountMinor;
        ledger.append(new NewLedgerEntry(reference, account.id(), account.accountNumber(), direction,
                amountMinor, account.currency(), newBalance, description));
        accounts.updateBalance(account.id(), newBalance);
        return new AccountView(account.accountNumber(), account.holderName(), account.currency(), newBalance, account.createdAt());
    }

    private static void requirePositive(long amountMinor) {
        if (amountMinor <= 0) {
            throw new InvalidAccountRequestException("Amount must be greater than zero");
        }
    }

    private String newAccountNumber() {
        return NUMBER_PREFIX + String.format("%08d", random.nextInt(100_000_000));
    }

    private static AccountView view(Account a) {
        return new AccountView(a.accountNumber(), a.holderName(), a.currency(), a.balanceMinor(), a.createdAt());
    }
}
