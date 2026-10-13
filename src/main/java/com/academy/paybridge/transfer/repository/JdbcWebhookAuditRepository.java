package com.academy.paybridge.transfer.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Plain SQL. The table is created by db/migration/V3__create_webhook_events.sql. */
@Repository
public class JdbcWebhookAuditRepository implements WebhookAuditRepository {

    private final JdbcClient jdbc;

    public JdbcWebhookAuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(String reference, String eventStatus, String providerTransferCode,
                       Long amountMinor, String outcome, Instant receivedAt) {
        jdbc.sql("""
                        INSERT INTO webhook_events (id, reference, event_status, provider_transfer_code,
                            amount_minor, outcome, received_at)
                        VALUES (:id, :reference, :eventStatus, :code, :amount, :outcome, :receivedAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("reference", abbreviate(reference, 60))
                .param("eventStatus", abbreviate(eventStatus, 16))
                .param("code", abbreviate(providerTransferCode, 64))
                .param("amount", amountMinor)
                .param("outcome", abbreviate(outcome, 24))
                .param("receivedAt", Timestamp.from(receivedAt))
                .update();
    }

    @Override
    public List<WebhookAuditRecord> findRecent(int limit) {
        return jdbc.sql("""
                        SELECT reference, event_status, provider_transfer_code, amount_minor, outcome, received_at
                        FROM webhook_events ORDER BY received_at DESC, id DESC LIMIT :limit
                        """)
                .param("limit", limit)
                .query(this::map).list();
    }

    private WebhookAuditRecord map(ResultSet rs, int rowNum) throws SQLException {
        long amount = rs.getLong("amount_minor");
        Long amountOrNull = rs.wasNull() ? null : amount;
        return new WebhookAuditRecord(
                rs.getString("reference"), rs.getString("event_status"), rs.getString("provider_transfer_code"),
                amountOrNull, rs.getString("outcome"), rs.getTimestamp("received_at").toInstant());
    }

    private static String abbreviate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
