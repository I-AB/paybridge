package com.academy.paybridge.account.repository;

import com.academy.paybridge.account.domain.Account;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Plain SQL. The table is created by db/migration/V2__create_accounts_and_ledger.sql. */
@Repository
public class JdbcAccountRepository implements AccountRepository {

    private static final String SELECT =
            "SELECT id, account_number, holder_name, currency, balance_minor, created_at FROM accounts ";

    private final JdbcClient jdbc;

    public JdbcAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Account a) {
        try {
            jdbc.sql("""
                            INSERT INTO accounts (id, account_number, holder_name, currency, balance_minor, created_at)
                            VALUES (:id, :number, :holder, :currency, :balance, :createdAt)
                            """)
                    .param("id", a.id())
                    .param("number", a.accountNumber())
                    .param("holder", a.holderName())
                    .param("currency", a.currency())
                    .param("balance", a.balanceMinor())
                    .param("createdAt", Timestamp.from(a.createdAt()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateAccountNumberException(a.accountNumber(), e);
        }
    }

    @Override
    public Optional<Account> findByNumber(String accountNumber) {
        return jdbc.sql(SELECT + "WHERE account_number = :number").param("number", accountNumber)
                .query(this::map).optional();
    }

    @Override
    public Optional<Account> lockByNumber(String accountNumber) {
        return jdbc.sql(SELECT + "WHERE account_number = :number FOR UPDATE").param("number", accountNumber)
                .query(this::map).optional();
    }

    @Override
    public void updateBalance(UUID id, long newBalanceMinor) {
        jdbc.sql("UPDATE accounts SET balance_minor = :balance WHERE id = :id")
                .param("balance", newBalanceMinor)
                .param("id", id)
                .update();
    }

    @Override
    public List<Account> findNewest(int limit) {
        return jdbc.sql(SELECT + "ORDER BY created_at DESC, id DESC LIMIT :limit").param("limit", limit)
                .query(this::map).list();
    }

    private Account map(ResultSet rs, int rowNum) throws SQLException {
        return new Account(
                rs.getObject("id", UUID.class),
                rs.getString("account_number"),
                rs.getString("holder_name"),
                rs.getString("currency"),
                rs.getLong("balance_minor"),
                rs.getTimestamp("created_at").toInstant());
    }
}
