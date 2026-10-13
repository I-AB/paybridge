-- Accounts and their ledger. Same rules as V1: Flyway style names, IF NOT EXISTS everywhere,
-- plain SQL that runs unchanged on H2 (default) and PostgreSQL.

CREATE TABLE IF NOT EXISTS accounts (
    id             UUID         PRIMARY KEY,
    account_number VARCHAR(10)  NOT NULL,
    holder_name    VARCHAR(100) NOT NULL,
    currency       VARCHAR(3)   NOT NULL,
    balance_minor  BIGINT       NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    CONSTRAINT uq_accounts_number UNIQUE (account_number),
    -- last line of defence: even a coding mistake cannot overdraw an account
    CONSTRAINT ck_accounts_balance_not_negative CHECK (balance_minor >= 0)
);

-- The ledger is append-only: the application only ever INSERTs into it, it never updates or deletes.
-- "reference" is unique, so posting the same movement twice is impossible (this is what makes
-- retries and refunds safe).
CREATE TABLE IF NOT EXISTS ledger_entries (
    id                  UUID         PRIMARY KEY,
    reference           VARCHAR(120) NOT NULL,
    account_id          UUID         NOT NULL,
    account_number      VARCHAR(10)  NOT NULL,
    direction           VARCHAR(6)   NOT NULL,
    amount_minor        BIGINT       NOT NULL,
    currency            VARCHAR(3)   NOT NULL,
    balance_after_minor BIGINT       NOT NULL,
    description         VARCHAR(255),
    created_at          TIMESTAMP    NOT NULL,
    CONSTRAINT uq_ledger_reference UNIQUE (reference),
    CONSTRAINT ck_ledger_direction CHECK (direction IN ('CREDIT', 'DEBIT')),
    CONSTRAINT ck_ledger_amount_positive CHECK (amount_minor > 0)
);

CREATE INDEX IF NOT EXISTS idx_ledger_account_created ON ledger_entries (account_number, created_at);
