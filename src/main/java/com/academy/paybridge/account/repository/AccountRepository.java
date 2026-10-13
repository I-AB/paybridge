package com.academy.paybridge.account.repository;

import com.academy.paybridge.account.domain.Account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository {

    /** @throws DuplicateAccountNumberException if the number is taken */
    void insert(Account account);

    Optional<Account> findByNumber(String accountNumber);

    /**
     * Reads the account and locks its row until the surrounding transaction ends, so two
     * concurrent debits cannot both read the same old balance.
     */
    Optional<Account> lockByNumber(String accountNumber);

    void updateBalance(UUID id, long newBalanceMinor);

    List<Account> findNewest(int limit);
}
