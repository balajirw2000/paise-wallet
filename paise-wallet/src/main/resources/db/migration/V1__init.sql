CREATE TABLE wallets (
    user_id TEXT PRIMARY KEY,
    balance_paise BIGINT NOT NULL DEFAULT 0 CHECK (balance_paise >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE transfers (
    transfer_id UUID PRIMARY KEY,
    from_user TEXT NOT NULL REFERENCES wallets(user_id),
    to_user TEXT NOT NULL REFERENCES wallets(user_id),
    amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
    idempotency_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'APPLIED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_idem UNIQUE (from_user, idempotency_key)
);

CREATE INDEX idx_transfers_to_user ON transfers(to_user);
