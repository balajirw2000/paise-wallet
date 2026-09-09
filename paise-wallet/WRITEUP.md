# paise-wallet — Engineering Write-up

A wallet / P2P transfer service. This document explains the data model, the concurrency mechanism and why it is the simplest correct one, idempotency design, authorization, the consistency-vs-availability decision, NFR priorities, edge cases, containerization/deployment/observability, AI usage, and cost.

## 1. Data model

Money is **integer paise**, stored as `BIGINT` (Java `long`). No floats anywhere in the money path. The schema is created via Flyway (`V1__init.sql`):

```sql
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
```

Key properties:
- `balance_paise >= 0` is a **DB constraint**, so the database itself refuses a negative balance even if application logic has a bug. This is a second line of defense, not the primary one.
- `amount_paise > 0` is likewise DB-enforced.
- `UNIQUE (from_user, idempotency_key)` is the idempotency anchor — it is what makes "apply exactly once" hold under races.
- `user_id TEXT PRIMARY KEY` guarantees at most one wallet per user.
- Every wallet also has a stable, unique `wallet_id UUID` (random, DB-generated). This is the identifier used to address a recipient **by wallet** — a mode where the wallet must already exist and is never auto-created. The immutable `wallet_id → user_id` mapping is resolved without taking a row lock (a wallet never changes owner), and the transfer then proceeds under the same deterministic `user_id` lock order, so no new deadlock/ordering hazard is introduced.

`V3__seed_demo_wallets.sql` seeds `alice` and `bob` with 100,000 paise each via `INSERT … ON CONFLICT DO NOTHING`. This is an explicit, documented trade-off for demo convenience: it is a one-time, idempotent initialization and is **not** wired into general wallet creation. `POST /accounts` still returns `0` for a brand-new user — auto-funding every new wallet would create money from nothing and break the conservation gate. New users are funded deliberately via `/dev/fund` (dev) or a real payment flow in production.

## 2. The transfer transaction (the crux)

A transfer runs inside **one PostgreSQL transaction at READ COMMITTED**. Steps, in order:

1. Request validation (self-transfer, `amount <= 0`, blank idempotency key) — done **before** opening a transaction.
2. Compute `request_hash = SHA-256(to_user + ":" + amount_paise)`.
3. `INSERT INTO wallets(user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING` for **both** users. This is the get-or-create: it never SELECT-then-INSERTs, so two concurrent first-time transfers cannot both "see nothing and each insert a row".
4. Lock both wallets with `SELECT ... WHERE user_id IN (?, ?) ORDER BY user_id FOR UPDATE`, always in lexicographic user-id order. Ordering the lock acquisition prevents deadlocks between two users transferring to each other.
5. Idempotency check: look up `(from_user, idempotency_key)`. Same hash → replay (return original result, no mutation). Different hash → `409`.
6. Balance check: if `balance < amount`, reject `422` and **do not mutate**.
7. Insert the transfer row (its unique constraint is the final arbiter under concurrent same-key races).
8. Debit sender, credit recipient.
9. Commit.

Everything is inside the transaction, so a failure aborts the whole change: no partial or torn transfer, no lost or created paise.

### Why get-or-create + transfer is race-free

The classic failure is *find-or-create*:

```
T1: SELECT wallet WHERE user='bob'      -- nothing
T2: SELECT wallet WHERE user='bob'      -- nothing
T1: INSERT wallet('bob')                -- ok
T2: INSERT wallet('bob')                -- unique violation → 500
```

We never do that. `INSERT ... ON CONFLICT (user_id) DO NOTHING` means:

- If the row doesn't exist, this transaction inserts it.
- If a concurrent transaction already inserted it (possibly not yet committed), the `INSERT` takes the `FOR UPDATE` row lock of the **other** transaction's uncommitted row and blocks until that transaction commits **or** aborts:
  - If T1 commits, T2's `ON CONFLICT DO NOTHING` finds the row and does nothing, and the subsequent `SELECT ... FOR UPDATE` sees a committed wallet. T2 proceeds to move money safely.
  - If T1 aborts, T2's blocked `INSERT` unblocks and inserts the row itself.
- Either way, exactly one wallet row exists, no concurrency-induced `500`, and no "Wallet not found" race.

The idempotency `UNIQUE` constraint plays the same role for the transfer row: if two concurrent requests share an idempotency key, one inserts and the other gets a `DuplicateKeyException`, at which point the losing transaction re-reads the row, compares hashes, and returns the stored outcome without moving money a second time.

### Alternatives rejected

- **SELECT-then-INSERT**: the classic find-or-create race described above — rejected.
- **PostgreSQL advisory locks (`pg_advisory_xact_lock`)**: correct, but duplicates what row locking already gives us, adds a separate lock namespace to manage, and is less obviously tied to the actual rows being modified.
- **Redis / external locks**: adds a whole external system and a new failure domain for no gain — rejected (the spec explicitly says no Redis unless forced).
- **`SERIALIZABLE` isolation**: correct but much less concurrency, and needs retry-on-serialization-failure logic for every transaction. READ COMMITTED plus `FOR UPDATE` on ordered rows is the textbook, simpler answer for this workload.

## 3. Idempotency

**Storage.** The `transfers` table stores `idempotency_key`, `request_hash`, `transfer_id`, `status`, `created_at`, and is unique per `(from_user, idempotency_key)`. A transfer is only ever recorded once per key per sender.

**Matching.** On a request we re-derive the hash from the current body and look up the existing row for `(caller, key)`:

- No row → proceed.
- Row exists and hash matches → **replay**: return the stored `transfer_id` and the caller's current balance, no mutation.
- Row exists and hash differs → **409**, no mutation.

**Same-key-different-body detection.** The request hash is a pure function of the money-shaping fields (`to_user`, `amount_paise`), so any change in them changes the hash deterministically. That is how `409` is decided.

**The balance mutation sits after the idempotency check and the transfer insert**, inside the same transaction. Because the row is unique per key, even a concurrent retry that passes the in-application check cannot double-apply: the second transaction's `INSERT` hits the unique constraint, it re-reads the committed row, sees the matching hash, and replays. Money is moved at most once per key, under all interleavings.

**Retention / expiry.** We deliberately **do not expire** idempotency keys. A wallet/take-home money ledger must be able to re-serve an old response indefinitely; expiring keys would make a long-delayed client retry a *new* transfer and double-spend. Storage cost is negligible (one small row per transfer). The trade-off (unbounded growth of the transfers table) is the right one for a money workload; a background archival/partitioning strategy can be layered on the `created_at` column later.

## 4. Authorization

Identity flows **only** from the verified JWT:

```
Authorization header → JwtFilter (OncePerRequestFilter)
   → verify HS256 signature with app.jwt.secret
   → extract sub
   → CallerContext.set(sub)  (ThreadLocal, request-scoped)
   → controllers read CallerContext.get()
```

- `POST /transfers` debits **CallerContext.get()**, so a caller can only ever spend their own wallet. The request body carries `to_user`, not `from_user`; a client-supplied `from_user` is not even a field.
- `GET /transfers/{id}` verifies the caller is `from_user` or `to_user`; otherwise `403`.
- `/dev/token` mints test JWTs **only** when `DEV_TOKENS_ENABLED=true`.
- Public endpoints: `/healthz`, `/readyz`, `/metrics`, `/actuator/**`, and `/dev/token` while enabled.
- Missing/invalid JWT → `401`.

## 5. Consistency vs. availability — NFR priorities

For a money service the priorities are, in order (per the spec):

1. **Money correctness** — never lose/create/double-move money.
2. **Concurrency correctness** — correct under races.
3. **Idempotency** — retries are safe.
4. **Authorization** — no cross-user spending.
5. **Database consistency**.
6. Testability, observability, deployability, code quality, convenience.

The transfer path is **strongly consistent and always writes through the primary store**. When the database is slow or unavailable, the transfer path **fails closed** (720 / 503 / connection error) rather than degrading: in a money workload a double-spend or an applied-but-unserved transfer caused by a degraded fallback is far worse than a rejection. We never queue transfers in memory, never serve stale balances for a debit decision, and never offload the balance mutation to a less-consistent store.

The read path (`GET /accounts/me`) reads the same primary store. Under load we rely on PostgreSQL connection pooling and one round-trip per balance read; we do not cache balances, because caching balances for a money read can show wrong money. Load-shedding happens through the pool and connection timeouts: if the DB is slow, reads slow down and ultimately reject rather than return incorrect data.

**Why reject rather than degrade:** availability that risks an incorrect balance or a double-spend is not availability for a wallet — it is a liability. That is an explicit, stated trade for a money workload. If a degraded mode were ever wanted (e.g., read-only balance from a replica), it would be strictly read-only and never influence a transfer decision.

**Edge cases handled:**
- Insufficient funds → `422`, no mutation.
- Self-transfer → `400`, **regardless of identifier form** (`to_user` matches the caller, or the recipient `to_wallet_id` resolves to the caller's own wallet).
- Zero / negative amount → `400`.
- Blank idempotency key → `400`.
- Recipient by username/email/phone → wallet is auto-created by `ON CONFLICT DO NOTHING` inside the same transaction and the funds move; no separate "recipient must exist" step that could race.
- Recipient by wallet id → the wallet must already exist; an unknown wallet id is rejected `400` and is **never** auto-created.
- Replay vs. conflict (same key, differing body) → `200` vs `409`. The request hash is computed over the **resolved recipient user id + amount**, so `to_user` and that user's `to_wallet_id` with the same key/amount are correctly treated as one transfer (idempotent replay), while a genuinely different amount is a conflict.
- Simultaneous first-transfers to the same brand-new pair → single wallets, single applies, no 500.
- Concurrent same-key retries → exactly one apply.

The minimal demo UI (`src/main/resources/static/index.html`, served at `/`) drives all of the above: mint a dev token, create/fund a wallet, and transfer either by user or by wallet id.

## 6. Containerization, deployment, observability

- **Multi-stage Dockerfile**: `maven:3.9-eclipse-temurin-21` builds, `eclipse-temurin:21-jre-alpine` runs as a **non-root** user; `HEALTHCHECK` hits `/healthz`; `EXPOSE 8080`.
- **docker-compose.yml**: `app` + `postgres:16-alpine`; app waits on the Postgres healthcheck; Flyway runs at startup; 12-factor env config (`DATABASE_URL`, `JWT_SECRET`, `PORT`, `DEV_TOKENS_ENABLED`). `.env.example` is committed; `.env` is git-ignored.
- **Structured JSON logs** via Logstash encoder, with `request_id` in MDC (CorrelationIdFilter: read/generate `X-Request-Id`, echo it back, keep it for every log on that request).
- **Events logged**: `transfer.applied`, `transfer.rejected`, `insufficient_funds`, `transfer.idempotent_replay`, `transfer.conflict`, `wallet.getorcreate.race_lost`, `auth.failure`.
- **Metrics** (`/metrics`, Prometheus text): `transfers_applied_total`, `transfers_rejected_total{reason=...}`, HTTP latency histograms (percentile histogram → p50/p95/p99), plus Spring/JVM metrics from Actuator.

## 7. AI usage (directed vs. decided)

- **Directed by AI (mechanical scaffolding):** project bootstrap (pom.xml, Spring Boot wiring), Flyway migration file, repository boilerplate, Dockerfile/compose, test file skeletons, and the burst script mechanics. The AI was told exactly what the schema, endpoints, status codes, and invariants must be by the build prompt.
- **Decided by the engineer (correctness-critical reasoning):**
  - The **get-or-create mechanism** (`INSERT ON CONFLICT DO NOTHING` + ordered `FOR UPDATE`) and the rejection of advisory locks, Redis, and SERIALIZABLE — this is the crux of the exercise and was a deliberate design decision.
  - **Where the idempotency check sits** relative to the balance mutation (before the mutation, in the same transaction, with the UNIQUE constraint as the race arbiter).
  - **No expiry of idempotency keys** and why.
  - **Consistency-over-availability** for both transfer and read paths, with the explicit NFR ranking.
  - The **dev-only `/dev/fund`** endpoint (a narrow testing convenience, clearly gated by `DEV_TOKENS_ENABLED`) so the correctness gate can be reproduced with only curl.
  - Keeping the API surface small (no extra feature endpoints).

## 8. Cost note (free tier / ₹0)

Everything in this project runs on free tiers — **₹0 spent**:

- Local development: brew-installed PostgreSQL and Maven, both free (this machine).
- CI/CD: free tiers (GitHub Actions minutes) not required for the build but available at no cost.
- Hosting: Render / Fly.io / Railway / Koyeb free tier web services.
- Managed Postgres: **Neon**, **Supabase**, or **Railway** free tiers — see README "Managed Postgres on a free tier" for exact connection setup.
- No paid third-party services, no credits used, no cloud spend.

*Note: on this dev machine Docker Desktop installation requires sudo (not available in this session), so `docker compose build/up` was authored and is expected to pass but was not executed here; the service was fully verified running directly on the JVM against real PostgreSQL, plus the full test suite and the burst gate.*