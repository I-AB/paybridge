package com.academy.paybridge.ledger.repository;

import com.academy.paybridge.ledger.api.DuplicateLedgerReferenceException;
import com.academy.paybridge.ledger.api.EntryDirection;
import com.academy.paybridge.ledger.api.LedgerEntryView;
import com.academy.paybridge.ledger.api.NewLedgerEntry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Plain SQL. The table is created by db/migration/V2__create_accounts_and_ledger.sql. */
@Repository
public class JdbcLedgerRepository implements LedgerRepository {

    private static final String SELECT = """
            SELECT reference, account_number, direction, amount_minor, currency, balance_after_minor,
                   description, created_at
            FROM ledger_entries
            """;

    private final JdbcClient jdbc;

    public JdbcLedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(NewLedgerEntry e, Instant now) {
        try {
            jdbc.sql("""
                            INSERT INTO ledger_entries (id, reference, account_id, account_number, direction,
                                amount_minor, currency, balance_after_minor, description, created_at)
                            VALUES (:id, :reference, :accountId, :accountNumber, :direction,
                                :amount, :currency, :balanceAfter, :description, :createdAt)
                            """)
                    .param("id", UUID.randomUUID())
                    .param("reference", e.reference())
                    .param("accountId", e.accountId())
                    .param("accountNumber", e.accountNumber())
                    .param("direction", e.direction().name())
                    .param("amount", e.amountMinor())
                    .param("currency", e.currency())
                    .param("balanceAfter", e.balanceAfterMinor())
                    .param("description", e.description())
                    .param("createdAt", Timestamp.from(now))
                    .update();
        } catch (DuplicateKeyException ex) {
            throw new DuplicateLedgerReferenceException(e.reference(), ex);
        }
    }

    @Override
    public Optional<LedgerEntryView> findByReference(String reference) {
        return jdbc.sql(SELECT + "WHERE reference = :reference").param("reference", reference)
                .query(this::map).optional();
    }

    @Override
    public List<LedgerEntryView> findByAccountNumber(String accountNumber, int limit) {
        return jdbc.sql(SELECT + "WHERE account_number = :number ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("number", accountNumber)
                .param("limit", limit)
                .query(this::map).list();
    }

    private LedgerEntryView map(ResultSet rs, int rowNum) throws SQLException {
        return new LedgerEntryView(
                rs.getString("reference"),
                rs.getString("account_number"),
                EntryDirection.valueOf(rs.getString("direction")),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                rs.getLong("balance_after_minor"),
                rs.getString("description"),
                rs.getTimestamp("created_at").toInstant());
    }
}
