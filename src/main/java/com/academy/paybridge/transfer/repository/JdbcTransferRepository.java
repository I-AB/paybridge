package com.academy.paybridge.transfer.repository;

import com.academy.paybridge.shared.money.Money;
import com.academy.paybridge.transfer.domain.Transfer;
import com.academy.paybridge.transfer.domain.TransferStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

/** Plain SQL through Spring's JdbcClient. The table is created by db/migration/V1__create_transfers.sql. */
@Repository
public class JdbcTransferRepository implements TransferRepository {

    private static final String SELECT = "SELECT * FROM transfers ";

    private final JdbcClient jdbc;

    public JdbcTransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Transfer t) {
        try {
            jdbc.sql("""
                    INSERT INTO transfers (id, idempotency_key, request_fingerprint, reference, source_account,
                        destination_account_number, destination_bank_code, destination_account_name,
                        amount_minor, currency, reason, status, provider_transfer_code, failure_reason,
                        created_at, updated_at)
                    VALUES (:id, :key, :fingerprint, :reference, :source, :destNumber, :destBank, :destName,
                        :amount, :currency, :reason, :status, :providerCode, :failure, :created, :updated)
                    """)
                    .param("id", t.id())
                    .param("key", t.idempotencyKey())
                    .param("fingerprint", t.requestFingerprint())
                    .param("reference", t.reference())
                    .param("source", t.sourceAccount())
                    .param("destNumber", t.destinationAccountNumber())
                    .param("destBank", t.destinationBankCode())
                    .param("destName", t.destinationAccountName())
                    .param("amount", t.amount().minorUnits())
                    .param("currency", t.amount().currency().getCurrencyCode())
                    .param("reason", t.reason())
                    .param("status", t.status().name())
                    .param("providerCode", t.providerTransferCode())
                    .param("failure", t.failureReason())
                    .param("created", Timestamp.from(t.createdAt()))
                    .param("updated", Timestamp.from(t.updatedAt()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateIdempotencyKeyException(t.idempotencyKey(), e);
        }
    }

    @Override
    public Optional<Transfer> findById(UUID id) {
        return jdbc.sql(SELECT + "WHERE id = :id").param("id", id).query(this::map).optional();
    }

    @Override
    public Optional<Transfer> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql(SELECT + "WHERE idempotency_key = :key").param("key", idempotencyKey)
                .query(this::map).optional();
    }

    @Override
    public Optional<Transfer> findByReference(String reference) {
        return jdbc.sql(SELECT + "WHERE reference = :reference").param("reference", reference)
                .query(this::map).optional();
    }

    @Override
    public void setProviderTransferCode(UUID id, String providerTransferCode, Instant now) {
        jdbc.sql("UPDATE transfers SET provider_transfer_code = :code, updated_at = :now WHERE id = :id AND provider_transfer_code IS NULL")
                .param("code", providerTransferCode)
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    @Override
    public boolean markIfPending(UUID id, TransferStatus newStatus, String failureReason, Instant now) {
        int rows = jdbc.sql("""
                        UPDATE transfers SET status = :status, failure_reason = :failure, updated_at = :now
                        WHERE id = :id AND status = 'PENDING'
                        """)
                .param("status", newStatus.name())
                .param("failure", failureReason)
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
        return rows == 1;
    }

    private Transfer map(ResultSet rs, int rowNum) throws SQLException {
        Money amount = new Money(rs.getLong("amount_minor"), Currency.getInstance(rs.getString("currency")));
        return new Transfer(
                rs.getObject("id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"),
                rs.getString("reference"),
                rs.getString("source_account"),
                rs.getString("destination_account_number"),
                rs.getString("destination_bank_code"),
                rs.getString("destination_account_name"),
                amount,
                rs.getString("reason"),
                TransferStatus.valueOf(rs.getString("status")),
                rs.getString("provider_transfer_code"),
                rs.getString("failure_reason"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
