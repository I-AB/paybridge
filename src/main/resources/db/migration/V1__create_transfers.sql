-- Transfer table for the transfer module.
-- Written as a Flyway style migration (V1__...) so it can be handed to Flyway later.
-- For now Spring's SQL initialisation runs it at startup (see application.yml).
-- Every statement uses IF NOT EXISTS, so the same scripts are safe on a database that already has them
-- (for example a persistent PostgreSQL) and on the in-memory H2 default.
--
-- funds_released: becomes TRUE once money held for a FAILED transfer has been handed back to the
-- source account (or when nothing was held). The reconciler retries FAILED transfers where it is FALSE.
CREATE TABLE IF NOT EXISTS transfers (
    id                         UUID         PRIMARY KEY,
    idempotency_key            VARCHAR(100) NOT NULL,
    request_fingerprint        VARCHAR(64)  NOT NULL,
    reference                  VARCHAR(60)  NOT NULL,
    source_account             VARCHAR(64)  NOT NULL,
    destination_account_number VARCHAR(10)  NOT NULL,
    destination_bank_code      VARCHAR(10)  NOT NULL,
    destination_account_name   VARCHAR(200),
    amount_minor               BIGINT       NOT NULL,
    currency                   VARCHAR(3)   NOT NULL,
    reason                     VARCHAR(100),
    status                     VARCHAR(16)  NOT NULL,
    provider_transfer_code     VARCHAR(64),
    failure_reason             VARCHAR(255),
    created_at                 TIMESTAMP    NOT NULL,
    updated_at                 TIMESTAMP    NOT NULL,
    funds_released             BOOLEAN      DEFAULT FALSE NOT NULL,
    CONSTRAINT uq_transfers_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT uq_transfers_reference UNIQUE (reference),
    CONSTRAINT ck_transfers_amount_positive CHECK (amount_minor > 0)
);

CREATE INDEX IF NOT EXISTS idx_transfers_status_created ON transfers (status, created_at);
