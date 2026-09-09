# paise-wallet

A production-quality **wallet / P2P transfer service** built on Java 21, Spring Boot 3, Spring JDBC, and PostgreSQL.

Money is held as **integer paise** (never float/double). The service is engineered so that under concurrent transfers and client retries it never loses money, never creates money, never double-moves money, and never lets one user spend another user's money.

---

## Quick start (Docker)

```bash
docker compose build
docker compose up -d
curl http://localhost:8080/healthz   # {"status":"UP"}
curl http://localhost:8080/readyz    # {"status":"UP"}
```

- PostgreSQL is started on `postgres:16-alpine` (health-gated).
- Flyway runs the schema migration on application startup.
- The app listens on port `8080`.

## Demo UI

A minimal single-page UI is served at `/` (no build step, no framework):

```bash
docker compose up -d
open http://localhost:8080        # demo UI
```

It lets you mint a dev token, create/fund wallets, and transfer to another user **or to a wallet id**, which is the fastest way to see all four requirements above in action. The dev users `alice` and `bob` are seeded with **100,000 paise** each (see *Seeding* below).

## Quick start (local, no Docker)

Requires PostgreSQL and Java 21+ (Maven 3.9).

```bash
# create the DB and user (example)
psql -U postgres -c "CREATE USER paise WITH PASSWORD 'paise' CREATEDB;"
psql -U postgres -c "CREATE DATABASE paise_wallet OWNER paise;"

mvn spring-boot:run
```

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/paise_wallet` | JDBC URL. Set `DATABASE_URL` to a `jdbc:` or `postgresql://` URL for a **managed Postgres** (Neon/Supabase/Railway). |
| `SPRING_DATASOURCE_URL` | – | Spring JDBC URL (takes precedence in Spring). |
| `SPRING_DATASOURCE_USERNAME` | – | DB user. |
| `SPRING_DATASOURCE_PASSWORD` | – | DB password. |
| `JWT_SECRET` | (dev default) | HS256 signing secret, at least 32 bytes. **Never commit a real secret.** |
| `PORT` | `8080` | HTTP port. |
| `DEV_TOKENS_ENABLED` | `false` | When `true`, enables `GET /dev/token` and `POST /dev/fund`. |

Copy `.env.example` for a full reference. `.env` (real secrets) is git-ignored.

## Seeding

A Flyway **data migration** seeds the demo users `alice` and `bob` with an initial balance of **100,000 paise** each on a fresh database. It is idempotent (`INSERT … ON CONFLICT (user_id) DO NOTHING`): it never creates duplicate wallet rows and never overwrites an existing balance.

This is a controlled, one-time initialization for demo purposes — **general wallet creation is intentionally not auto-funded** (`POST /accounts` returns a zero balance for a new user). Automatically crediting every brand-new wallet would create money out of nowhere and silently break the conservation gate; funding new users is done explicitly via `/dev/fund` (dev) or a real payment flow in production.

## Obtaining a development token

Only when `DEV_TOKENS_ENABLED=true`:

```bash
curl "http://localhost:8080/dev/token?user=alice"
# {"token":"eyJhbGciOiJIUzI1NiJ9..."}

curl -X POST http://localhost:8080/dev/fund \
  -H "Content-Type: application/json" \
  -d '{"user_id":"alice","amount_paise":100000}'
```

Tokens are HS256 JWTs valid for 1 hour. On a real deployment you would use your own auth system to mint JWTs; `/dev/token` exists only to make the correctness gate easy to reproduce, and is disabled unless explicitly turned on.

## API

All endpoints below (except health/readiness/metrics and dev endpoints) require `Authorization: Bearer <JWT>`. The caller's identity is **always** the verified JWT `sub` — never a header/body field.

| Method | Endpoint | Auth | Behavior |
|---|---|---|---|
| `POST` | `/accounts` | JWT | Idempotent get-or-create of the caller's wallet → `{ balance_paise }` |
| `GET` | `/accounts/me` | JWT | Current caller balance |
| `POST` | `/transfers` | JWT | Transfer funds |
| `GET` | `/transfers/{id}` | JWT | Participant-only transfer details |
| `GET` | `/healthz` | none | Liveness |
| `GET` | `/readyz` | none | Readiness (includes DB check) |
| `GET` | `/metrics` | none | Prometheus text metrics |
| `GET` | `/dev/token?user=<id>` | none* | Mint dev JWT (* dev-only flag) |

### Create / read your wallet

```bash
TOKEN=$(curl -s "http://localhost:8080/dev/token?user=alice" | jq -r .token)

curl -X POST http://localhost:8080/accounts -H "Authorization: Bearer $TOKEN"
# {"user_id":"alice","wallet_id":"9f0c…-…","balance_paise":100000}

curl http://localhost:8080/accounts/me -H "Authorization: Bearer $TOKEN"
# {"user_id":"alice","wallet_id":"9f0c…-…","balance_paise":100000}
```

### Wallet identifiers

A recipient can be addressed in two ways, and the service treats them differently:

| Recipient field | Semantics |
|---|---|
| `to_user` (username / email / phone) | A user identifier. The recipient's wallet is **created as part of the transfer** if they don't have one yet (atomic, same transaction). |
| `to_wallet_id` (UUID) | A specific wallet. The wallet **must already exist**; if it does not, the transfer is rejected (`400`) and **no wallet is created**. |

Provide exactly one of the two fields per request. A self-transfer is rejected regardless of which identifier form is used.

### Transfer funds

```bash
curl -X POST http://localhost:8080/transfers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"to_user":"bob","amount_paise":1000,"idempotency_key":"txn-001"}'
# {"transfer_id":"...","new_balance":99000}

# …or to an existing wallet id (bob's)
curl -X POST http://localhost:8080/transfers \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"to_wallet_id":"<bob-wallet-uuid>","amount_paise":1000,"idempotency_key":"txn-002"}'
```

**Idempotency keys** make retries safe: re-sending the exact same body with the same key returns the original result and moves no money again. Sending the **same key with a different body** returns `409`. Identifiers that resolve to the same recipient user (e.g. `to_user` vs the same user's `to_wallet_id`) are treated as the same transfer for idempotency purposes.

### Read a transfer (participant only)

```bash
curl http://localhost:8080/transfers/<transfer_id> -H "Authorization: Bearer $TOKEN"
```

Only `from_user` / `to_user` may read a transfer; anyone else gets `403`.

### HTTP status codes

| Situation | Status |
|---|---|
| Successful transfer / replay | `200` |
| Self-transfer / zero or negative amount / blank key | `400` |
| Missing / invalid JWT | `401` |
| Non-participant transfer read | `403` |
| Same idempotency key + different body | `409` |
| Insufficient funds | `422` |
| Database unavailable (readyz) | `503` |

Error bodies use `{ "error_code", "message", "request_id" }`.

## Tests

```bash
mvn clean test
```

The suite uses a **real PostgreSQL** (the schema comes from the Flyway migration). With Docker available it runs against Testcontainers Postgres; with a local Postgres it uses the configured `paise_wallet` database.

Covered:
- `TransferServiceTest` — happy path, insufficient funds, self-transfer, zero/negative amounts, auto-created recipient wallet.
- `IdempotencyTest` — replay returns original result, same-key/different-body → 409, money moves exactly once.
- `AuthTest` — missing/invalid token → 401, non-participant read → 403, caller can only spend from own wallet.
- `ConcurrencyTest` — 50 concurrent first-transfers (single wallet per user, no dupes, no negatives); 50 concurrent retries with the same idempotency key (applied exactly once); no-over-spend under a burst; money conservation.

## Burst script

`burst.sh` reproduces the correctness gate against a running instance (locally or deployed):

```bash
./burst.sh http://localhost:8080
# BURST: PASS
```

It verifies:
1. 50 concurrent **first-transfers** (distinct keys) between two brand-new users — all `200`, no duplicates.
2. 50 concurrent **retries** of the same transfer with the same idempotency key — all `200`, single apply.
3. Same key + different body → `409`.
4. Conservation — total balance unchanged; recipient got exactly the right amount.
5. No negative balances; health/readiness/auth checks.

Requires only `bash`, `curl`, `python3` (for JSON parsing) and `xargs`/parallelism via shell background jobs.

## Containerized deployment

```bash
docker compose up -d            # build + start app + postgres
docker compose logs -f app      # structured JSON logs
curl http://localhost:8080/healthz
curl http://localhost:8080/metrics | grep transfers_applied_total
```

Hosted (Render/Fly/Railway/Koyeb) with a managed Postgres (Neon/Supabase/Railway):
- Point the app instead at the managed Postgres URL.
- Set `JWT_SECRET`, `DEV_TOKENS_ENABLED=false`, `PORT` (platform-injected), `DATABASE_URL` (and `SPRING_DATASOURCE_USERNAME/PASSWORD` if not embedded).
- Flyway runs migrations on boot against the managed DB.

### Managed Postgres on a free tier (Neon / Supabase / Railway)

The app runs unchanged against any PostgreSQL 14+ service. The only difference is the connection string. `DATABASE_URL` accepts either a JDBC URL (`jdbc:postgresql://…`) or a libpq-style URL (`postgresql://…`) — the PostgreSQL JDBC driver accepts both.

**Neon (free plan, serverless Postgres)**
1. Create a project at https://neon.tech → the dashboard shows a connection string like:
   `postgresql://user:password@ep-…-pooler.us-east-2.aws.neon.tech/neondb?sslmode=require`
2. Set env vars:
   ```bash
   export DATABASE_URL="jdbc:postgresql://user:password@ep-…-pooler.us-east-2.aws.neon.tech/neondb?sslmode=require"
   ```
   Or with the pooled endpoint: use the `-pooler` host (one of the `DATABASE_URL` variants Neon provides).
3. Run the app; Flyway creates `wallets`/`transfers` on first boot.
4. `./burst.sh http://localhost:8080` to run the gate against the managed DB.

**Supabase (free plan, Postgres + pooler)**
1. Create a project at https://supabase.com → Database → "Connect" → "URI" / "Connection string (JDBC)".
2. Set:
   ```bash
   export DATABASE_URL="jdbc:postgresql://db.XXXX.supabase.co:5432/postgres?user=postgres&password=YOURPASSWORD&sslmode=require"
   ```
   Use the transaction/port-5432 pooler endpoint if the direct endpoint is throttled.
3. Same Flyway + `./burst.sh` flow as above.

**Railway**
1. Railway's Postgres plugin injects a connection string into `DATABASE_URL` automatically; otherwise grab it from the service's "Connect" tab.
2. Set `DATABASE_URL` to the railway `postgresql://…` URL and run.

Notes that apply to serverless providers (Neon/Supabase):
- Free plans may **suspend idle projects**; the first request after idle may wait on a cold start — entirely fine for the correctness gate, just slow.
- Always use `?sslmode=require` for the serverless providers.
- Don't use the *direct* (non-pooled) endpoint for high concurrency; burst.sh fires 50 parallel requests, so prefer the `-pooler` (Neon) or pooled (Supabase) endpoint.
- Prisma/ownership differences don't apply here — Flyway uses the DB user directly. Make sure the Flyway user has `CREATE` on the schema.

## Observability

- **Structured JSON logs** (Logstash encoder) at stdout, including `request_id`.
- `X-Request-Id` is read from the request or generated, placed in MDC, and echoed in the response header.
- Business events logged: `transfer.applied`, `transfer.rejected`, `insufficient_funds`, `transfer.idempotent_replay`, `transfer.conflict`, `wallet.getorcreate.race_lost`, `auth.failure`.
- **Metrics** (`/metrics`, Prometheus text): `transfers_applied_total`, `transfers_rejected_total{reason=...}`, HTTP request latency histograms (percentile histogram enabled → p50/p95/p99 derivable), plus all standard Spring/Actuator/JVM metrics.

Health/readiness: `/healthz` (liveness), `/readyz` (readiness, runs `SELECT 1` against the DB).

---

See `WRITEUP.md` for the full engineering rationale: data model, concurrency mechanism and the alternatives that were rejected, idempotency design, authorization flow, the consistency-vs-availability decision, NFR priorities, edge cases, deployment/observability choices, AI usage, and the free-tier cost note.