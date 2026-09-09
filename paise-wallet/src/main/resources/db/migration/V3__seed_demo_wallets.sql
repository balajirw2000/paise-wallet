-- Seed demo wallets so the UI / demo can transfer money immediately.
-- On a fresh database this funds the two demo users once.
-- ON CONFLICT (user_id) DO NOTHING keeps this safe on any existing data:
-- it never creates duplicate wallet rows and never overwrites a balance.
INSERT INTO wallets(user_id, balance_paise)
VALUES ('alice', 100000), ('bob', 100000)
ON CONFLICT (user_id) DO NOTHING;