-- Audit trail of every verified provider webhook and what we did with it.
-- The raw request body is deliberately NOT stored.
CREATE TABLE IF NOT EXISTS webhook_events (
    id                     UUID        PRIMARY KEY,
    reference              VARCHAR(60) NOT NULL,
    event_status           VARCHAR(16) NOT NULL,
    provider_transfer_code VARCHAR(64),
    amount_minor           BIGINT,
    outcome                VARCHAR(24) NOT NULL,
    received_at            TIMESTAMP   NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_webhook_events_reference ON webhook_events (reference);
