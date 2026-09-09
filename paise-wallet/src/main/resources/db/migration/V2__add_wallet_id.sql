ALTER TABLE wallets ADD COLUMN wallet_id UUID;

UPDATE wallets SET wallet_id = gen_random_uuid() WHERE wallet_id IS NULL;

ALTER TABLE wallets ALTER COLUMN wallet_id SET NOT NULL;
ALTER TABLE wallets ALTER COLUMN wallet_id SET DEFAULT gen_random_uuid();

CREATE UNIQUE INDEX uq_wallets_wallet_id ON wallets(wallet_id);