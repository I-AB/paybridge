package com.academy.paybridge.account;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountNotFoundException;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.api.InsufficientFundsException;
import com.academy.paybridge.account.api.PostingResult;
import com.academy.paybridge.ledger.api.EntryDirection;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.LedgerEntryView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real H2 database, real SQL, real transactions: the money rules must hold here, not just in mocks. */
@SpringBootTest(properties = "paybridge.transfer.gateway=simulated")
class AccountLedgerIntegrationTest {

    @Autowired
    private AccountApi accounts;

    @Autowired
    private LedgerApi ledger;

    private AccountView openFunded(long minor) {
        AccountView account = accounts.open("Test Holder", "NGN");
        if (minor > 0) {
            accounts.credit(account.accountNumber(), minor, "seed:" + UUID.randomUUID(), "seed");
        }
        return accounts.find(account.accountNumber()).orElseThrow();
    }

    private static String ref() {
        return "t:" + UUID.randomUUID();
    }

    @Test
    void newAccountStartsEmptyWithATenDigitNumber() {
        AccountView account = accounts.open("Ada Obi", "NGN");

        assertThat(account.accountNumber()).matches("\\d{10}").startsWith("90");
        assertThat(account.balanceMinor()).isZero();
        assertThat(account.currency()).isEqualTo("NGN");
    }

    @Test
    void creditAndDebitChangeTheBalanceAndWriteLedgerLines() {
        AccountView account = openFunded(10_000);
        String number = account.accountNumber();

        accounts.debit(number, 2_500, ref(), "coffee");

        assertThat(accounts.find(number).orElseThrow().balanceMinor()).isEqualTo(7_500);
        List<LedgerEntryView> entries = ledger.entriesFor(number, 10);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).direction()).isEqualTo(EntryDirection.DEBIT);
        assertThat(entries.get(0).balanceAfterMinor()).isEqualTo(7_500);
        assertThat(entries.get(1).direction()).isEqualTo(EntryDirection.CREDIT);
    }

    @Test
    void postingTheSameReferenceTwiceMovesMoneyOnlyOnce() {
        AccountView account = openFunded(10_000);
        String reference = ref();

        PostingResult first = accounts.debit(account.accountNumber(), 1_000, reference, "x");
        PostingResult second = accounts.debit(account.accountNumber(), 1_000, reference, "x");

        assertThat(first.applied()).isTrue();
        assertThat(second.applied()).isFalse();
        assertThat(accounts.find(account.accountNumber()).orElseThrow().balanceMinor()).isEqualTo(9_000);
    }

    @Test
    void cannotSpendMoreThanTheBalance() {
        AccountView account = openFunded(1_000);

        assertThatThrownBy(() -> accounts.debit(account.accountNumber(), 1_001, ref(), "too much"))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(accounts.find(account.accountNumber()).orElseThrow().balanceMinor()).isEqualTo(1_000);
    }

    @Test
    void unknownAccountIsReported() {
        assertThatThrownBy(() -> accounts.debit("9000000000", 100, ref(), "x"))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void transferMovesMoneyFromOneAccountToTheOther() {
        AccountView from = openFunded(10_000);
        AccountView to = openFunded(500);

        accounts.transfer(from.accountNumber(), to.accountNumber(), 4_000, ref(), ref(), "sent", "received");

        assertThat(accounts.find(from.accountNumber()).orElseThrow().balanceMinor()).isEqualTo(6_000);
        assertThat(accounts.find(to.accountNumber()).orElseThrow().balanceMinor()).isEqualTo(4_500);
    }

    @Test
    void transferWithTooLittleMoneyChangesNothing() {
        AccountView from = openFunded(1_000);
        AccountView to = openFunded(0);

        assertThatThrownBy(() -> accounts.transfer(from.accountNumber(), to.accountNumber(), 1_001, ref(), ref(), "a", "b"))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(accounts.find(from.accountNumber()).orElseThrow().balanceMinor()).isEqualTo(1_000);
        assertThat(accounts.find(to.accountNumber()).orElseThrow().balanceMinor()).isZero();
    }

    @Test
    void transferToAnUnknownAccountKeepsTheSendersMoney() {
        AccountView from = openFunded(1_000);

        assertThatThrownBy(() -> accounts.transfer(from.accountNumber(), "9099999999", 100, ref(), ref(), "a", "b"))
                .isInstanceOf(AccountNotFoundException.class);

        assertThat(accounts.find(from.accountNumber()).orElseThrow().balanceMinor()).isEqualTo(1_000);
    }

    @Test
    void repeatingATransferWithTheSameReferencesMovesMoneyOnce() {
        AccountView from = openFunded(10_000);
        AccountView to = openFunded(0);
        String debitRef = ref();
        String creditRef = ref();

        accounts.transfer(from.accountNumber(), to.accountNumber(), 3_000, debitRef, creditRef, "a", "b");
        accounts.transfer(from.accountNumber(), to.accountNumber(), 3_000, debitRef, creditRef, "a", "b");

        assertThat(accounts.find(from.accountNumber()).orElseThrow().balanceMinor()).isEqualTo(7_000);
        assertThat(accounts.find(to.accountNumber()).orElseThrow().balanceMinor()).isEqualTo(3_000);
    }

    @Test
    void transfersInOppositeDirectionsAtTheSameTimeDoNotDeadlock() throws Exception {
        AccountView a = openFunded(100_000);
        AccountView b = openFunded(100_000);
        int each = 20;

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> jobs = new java.util.ArrayList<>();
            for (int i = 0; i < each; i++) {
                jobs.add(() -> {
                    accounts.transfer(a.accountNumber(), b.accountNumber(), 100, ref(), ref(), "ab", "ab");
                    return true;
                });
                jobs.add(() -> {
                    accounts.transfer(b.accountNumber(), a.accountNumber(), 100, ref(), ref(), "ba", "ba");
                    return true;
                });
            }
            for (Future<Boolean> f : pool.invokeAll(jobs)) {
                assertThat(f.get(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }

        long total = accounts.find(a.accountNumber()).orElseThrow().balanceMinor()
                + accounts.find(b.accountNumber()).orElseThrow().balanceMinor();
        assertThat(total).isEqualTo(200_000);
        assertThat(accounts.find(a.accountNumber()).orElseThrow().balanceMinor()).isEqualTo(100_000);
    }

    @Test
    void concurrentTransfersNeverOverdrawTheSender() throws Exception {
        AccountView from = openFunded(10_000); // enough for exactly 10 transfers of 1,000
        AccountView to = openFunded(0);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        long succeeded = 0;
        try {
            List<Callable<Boolean>> jobs = java.util.stream.IntStream.range(0, 25)
                    .<Callable<Boolean>>mapToObj(i -> () -> {
                        try {
                            accounts.transfer(from.accountNumber(), to.accountNumber(), 1_000, ref(), ref(), "race", "race");
                            return true;
                        } catch (InsufficientFundsException e) {
                            return false;
                        }
                    }).toList();
            for (Future<Boolean> f : pool.invokeAll(jobs)) {
                if (f.get()) {
                    succeeded++;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(succeeded).isEqualTo(10);
        assertThat(accounts.find(from.accountNumber()).orElseThrow().balanceMinor()).isZero();
        assertThat(accounts.find(to.accountNumber()).orElseThrow().balanceMinor()).isEqualTo(10_000);
    }

    @Test
    void reverseReturnsADebitExactlyOnce() {
        AccountView account = openFunded(5_000);
        String hold = ref();
        accounts.debit(account.accountNumber(), 3_000, hold, "hold");

        boolean first = accounts.reverse(hold, "refund:" + hold, "refund");
        boolean second = accounts.reverse(hold, "refund:" + hold, "refund");

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(accounts.find(account.accountNumber()).orElseThrow().balanceMinor()).isEqualTo(5_000);
    }

    @Test
    void reversingSomethingThatNeverHappenedDoesNothing() {
        assertThat(accounts.reverse("no-such-debit", "refund:none", "x")).isFalse();
    }

    @Test
    void concurrentDebitsNeverOverdrawTheAccount() throws Exception {
        AccountView account = openFunded(10_000); // enough for exactly 10 debits of 1,000
        String number = account.accountNumber();
        int attempts = 25;

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> jobs = java.util.stream.IntStream.range(0, attempts)
                    .<Callable<Boolean>>mapToObj(i -> () -> {
                        try {
                            accounts.debit(number, 1_000, ref(), "race");
                            return true;
                        } catch (InsufficientFundsException e) {
                            return false;
                        }
                    }).toList();
            long succeeded = 0;
            for (Future<Boolean> f : pool.invokeAll(jobs)) {
                if (f.get()) {
                    succeeded++;
                }
            }
            assertThat(succeeded).isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
        assertThat(accounts.find(number).orElseThrow().balanceMinor()).isZero();
    }
}
