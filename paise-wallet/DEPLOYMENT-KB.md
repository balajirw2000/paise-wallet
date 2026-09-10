# Paise Wallet — Deployment Knowledge Base

Session notes: pushing the repo to GitHub and deploying `paise-wallet` (Spring Boot 3.4 / Java 21 / PostgreSQL + Flyway) to Railway (Railway Postgres + Docker deploy). Captures the exact steps, the failures, and the root causes.

---

## 1. Pushing the project to GitHub

**Steps**

1. Repo was not a git directory → `git init`.
2. Created a root `.gitignore` (`.DS_Store`, `target/`, `*.class`, `.env`, …). The project already had a `paise-wallet/.gitignore`.
3. Set local git identity (`git config user.name/email`) — no global git config existed.
4. `gh` (GitHub CLI) was not installed → `brew install gh`.
5. Authenticated with the device flow:
   - `gh auth login -h github.com -w -p https`
   - User opens `https://github.com/login/device`, enters the one-time code, approves.
   - Verify with `gh auth status` (token scopes `repo` needed for creating repos + push).
6. Created the public repo and pushed in one command:
   ```bash
   gh repo create balajirw2000/paise-wallet --public --source . --remote origin --push
   ```
7. Result: `main` branch, 47 files → https://github.com/balajirw2000/paise-wallet

## 2. Making the app Railway-ready (code changes)

The app already read `DATABASE_URL` / `PORT` / `JWT_SECRET`, and Flyway already ran migrations on boot. The one gap: the PostgreSQL JDBC driver only accepts `jdbc:postgresql://` URLs, while Railway injects `postgres://…` (libpq format).

**Changes**

- `src/main/java/com/paise/wallet/config/LibpqUrlEnvironmentPostProcessor.java`
  - `EnvironmentPostProcessor` that reads `DATABASE_URL` (or `SPRING_DATASOURCE_URL`), and if the scheme is `postgres`/`postgresql`, rewrites it to `jdbc:postgresql://…`, preserving host/port/path/query, and extracts `username`/`password` from the URL userinfo into `spring.datasource.username/password`.
  - Registered via `META-INF/spring.factories` (see Issue 5).
- `railway.json` — pins Dockerfile builder + `/healthz` healthcheck. NOTE: Railway now warns this config-as-code file is deprecated in favor of `.railway/railway.ts` IaC (still works until Dec 2026).
- `README.md` — env-var table + full Railway deploy section.
- `.env.example` — documents both JDBC and libpq URL forms.
- `LibpqUrlEnvironmentPostProcessorTest.java` — 4 unit tests.

## 3. Deploying to Railway (CLI flow)

```bash
# 1. install CLI
curl -fsSL https://railway.com/install.sh | sh
export PATH="$HOME/.railway/bin:$PATH"

# 2. login (browser OAuth)
railway login

# 3. create + link project (run from paise-wallet/)
railway init --name paise-wallet --json

# 4. add managed Postgres
railway add --database postgres --json

# 5. create an empty app service
railway add --service paise-wallet-app

# 6. set env vars (verify with `railway variables`)
railway variables --set "DATABASE_URL=\${{ Postgres.DATABASE_URL }}"
railway variables --set "JWT_SECRET=$(openssl rand -hex 32)"
railway variables --set DEV_TOKENS_ENABLED=true

# 7. deploy from local dir
railway up -y -s paise-wallet-app

# 8. health + public URL
railway status
railway domain
./burst.sh https://<your-app>.up.railway.app
```

## 4. Deployment state at end of session

- Project: `paise-wallet` (id `2e2a6535-0267-4206-89ca-4ad2f5c55b57`, production env).
- Services: `paise-wallet-app` (Dockerfile build, online once DB reachable) + `Postgres` (railwayapp-templates/postgres-ssl:18, `postgres-volume`).
- Env vars set at the app service: `DATABASE_URL=${{ Postgres.DATABASE_URL }}`, `JWT_SECRET` (random), `DEV_TOKENS_ENABLED=true`.
- Pending at end of session: verify the freshly triggered redeploy goes Online (the URL fix from Issue 5 was still building), add a domain, run `burst.sh`.

---

## 5. Issues and resolutions

### Issue 1 — `gh` CLI not installed / no GitHub auth
- **Symptom:** `gh: command not found`; no SSH keys; no token.
- **Fix:** `brew install gh`, then interactive device login. Use `-w -p https`. Completion can be detected via `gh auth status`.

### Issue 2 — First `railway up` destroyed the Postgres service
- **Symptom:** after `railway add --database postgres`, Postgres showed `Crashed` and its logs showed our **Spring Boot app** trying to boot (`URL must start with 'jdbc'`).
- **Root cause:** `railway add --database postgres` **links the current directory to the Postgres service**. Running `railway up` with no `-s` deployed our app into the Postgres service, overwriting the template image. Then, because that service was no longer a real database, `DATABASE_URL` was no longer injected anywhere.
- **Fix:**
  1. Railway CLI has no per-service delete (only `railway delete -p` which deletes the whole project) → deleted project `99a23cc6…` and recreated everything (`railway init` → re-add Postgres → new app service).
  2. Always deploy with an explicit target: `railway up -y -s paise-wallet-app`. Never bare `railway up` when the link can point at a database.

### Issue 3 — `DATABASE_URL` never injected into the app service
- **Symptom:** app fell back to `jdbc:postgresql://localhost:5432/…` (log: `Connection to localhost:5432 refused`), even though Postgres existed in the same environment. `railway variables` showed only `RAILWAY_*` built-ins.
- **Root cause:** a bare `railway add --service` does not get auto-injection of DB vars.
- **Fix:** set it explicitly with a service-variable reference so Railway resolves it at deploy time:
  ```bash
  railway variables --set "DATABASE_URL=\${{ Postgres.DATABASE_URL }}"
  ```
  (Verified the app began receiving a `postgres://…` value — which then surfaced Issue 5.)

### Issue 4 — `URL must start with 'jdbc'` even with the converter — Spring Boot 3.4 does NOT load `.imports`-registered EnvironmentPostProcessors
- **Symptom:** app crashed with `IllegalArgumentException: URL must start with 'jdbc'` in `DataSourceProperties.determineDriverClassName`. Locally reproducible with `DATABASE_URL='postgres://…'`.
- **What was tried (dead ends):**
  - `META-INF/spring/org.springframework.boot.autoconfigure.EnvironmentPostProcessorImports` — **this marker class does not exist in Spring Boot 3.4**; the file is never read.
  - `META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports` — also not read by the Boot 3.4 loader.
- **Diagnosis method (fast, offline):**
  1. Reproduced locally against the built jar: `DATABASE_URL='postgres://u:p@127.0.0.1:59999/wallet' java -jar target/paise-wallet-1.0.0.jar` → still `URL must start with 'jdbc'`.
  2. Confirmed the class + registration file WERE inside the jar (`unzip -l`).
  3. Decompiled Boot internals (`javap` on `spring-boot-3.4.1.jar`):
     - `SpringApplication` uses `EnvironmentPostProcessorApplicationListener` + `EnvironmentPostProcessorsFactory.fromSpringFactories(cl)`.
     - That factory wraps `SpringFactoriesLoader.forDefaultResourceLocation(cl)`.
  4. Built a plain-classpath harness (`EnvironmentPostProcessorsFactory.fromSpringFactories(URLClassLoader)` over the Maven deps + `target/classes`) and printed discovered EPPs → **ours was absent**, while Boot's own (`IntegrationPropertiesEnvironmentPostProcessor`, etc.) were present.
  5. Inspected `spring-boot-autoconfigure-3.4.1.jar!/META-INF/spring.factories` → confirms EPPs are registered there, under key `org.springframework.boot.env.EnvironmentPostProcessor`.
- **Fix:** register via classic `META-INF/spring.factories`:
  ```
  org.springframework.boot.env.EnvironmentPostProcessor=com.paise.wallet.config.LibpqUrlEnvironmentPostProcessor
  ```
- **Verification:** re-ran the local repro → app now binds `jdbc:postgresql://127.0.0.1:59999/wallet` (log shows `Connection to 127.0.0.1:59999 refused`, not a URL error). Full `mvn test` suite passes (27 tests).

### Issue 5 — Railway deprecation notices
- **Symptom:** every CLI call prints `warning: Config as Code (railway.json / railway.toml) is deprecated…`.
- **Resolution:** `railway.json` still works until 2026-12-01. Prefer migrating to `.railway/railway.ts` (IaC) via `railway config migrate` for future-proofing, but not required now.

---

## 6. Handy gotchas

- EnvironmentPostProcessor precedence: inserted with `configuration.addFirst(...)` so it wins over YAML for `spring.datasource.*`. Extraction of creds from `userinfo` happens at the highest precedence so it can't be shadowed by `application.yml` placeholders. Unit-test the converter directly with a `StandardEnvironment` + a `MapPropertySource` for `DATABASE_URL`.
- `mvn clean package` + `java -jar target/*.jar` with a fake-but-valid `postgres://…` URL is the fastest end-to-end check of DB URL handling before pushing to a platform.
- `railway up` uses the Dockerfile (`maven:3.9-eclipse-temurin-21` for the offline build, temurin JRE alpine runtime, non-root user, port 8080 → `PORT` env from Railway, healthcheck `/healthz`).
- `burst.sh <base-url>` is the correctness gate (50 concurrent first-transfers, 50 same-key retries, 409 on key replay with different body, conservation, no negatives, auth checks). It needs `DEV_TOKENS_ENABLED=true`.
## 7. OpenAPI-spec-driven API refactor (`openapi.yaml` → generated controllers → delegates)

- Contract: `src/main/resources/openapi.yaml` is the single source of truth. `openapi-generator-maven-plugin` 7.12.0 (spring generator, `delegatePattern=true`, `useSpringBoot3=true`, `useTags=true`, `useJakartaEe=true`, `openApiNullable=false`, `documentationProvider=none`, `typeMapping uuid=java.util.UUID`) runs in every build → `target/generated-sources/openapi`.
- Output per tag: `AccountsApi`, `TransfersApi`, `HealthApi`, `DevApi`, `MetricsApi` (interface w/ `@RequestMapping`), each `XxxApiController` (`@Controller` autowiring the delegate, defaulting to a 501 stub), each `XxxApiDelegate` interface. Models (snake_case JSON via `@JsonProperty`) land in `com.paise.wallet.web.model`. Supporting classes (`ApiUtil`, `org.openapitools.configuration.HomeController`, `OpenApiGeneratorApplication`, `RFC3339DateFormat`) are inert — `HomeController` is an empty `@Controller`, and the `org.openapitools` package isn't component-scanned (app scans `com.paise.wallet`), so `/` still serves the demo `index.html`.
- Business logic lives in hand-written `@Service` beans implementing the generated delegate interfaces:
  - `AccountsApiDelegateImpl`, `TransfersApiDelegateImpl`, `HealthApiDelegateImpl`, `DevApiDelegateImpl`, `MetricsApiDelegateImpl` (all in `com.paise.wallet.web`), plus a package-private `ApiModelMapper` that converts domain records ↔ generated web models.
- `WalletController.java` was deleted; `GlobalExceptionHandler`, `JwtFilter`, `CorrelationIdFilter`, `CallerContext` are untouched.
- Service layering (SRP): `WalletService` (new, `com.paise.wallet.service`) owns all single-wallet ops — `getOrCreate`, `getBalance`, `findByWalletId`, `lockForUpdate`, `credit`, `debit` — via `WalletRepository`. `TransferService` keeps only the transfer-coordinator role (validation, idempotency, deterministic lock ordering, transaction boundary). `AccountsApiDelegateImpl`/`DevApiDelegateImpl` depend on `WalletService`; `/dev/fund` now calls `walletService.credit(...)` instead of raw JDBC. WalletService methods are deliberately non-transactional — they join the caller's tx (REQUIRED) or run single-statement standalone.
- Security isn't enforced by generated code: `JwtFilter` still whitelists `/healthz`, `/readyz`, `/metrics`, and `/dev/*`-when-enabled by path — matches the spec's `security` blocks.
- Gotchas seen:
  - Controllers extend the generated interface, so validation (`@Valid @RequestBody`, `@Min`, `@Size` on models) and produces/consumes come from the interface — keep the spec's responses and `operationId`s stable or regenerated handlers will change HTTP behavior.
  - `@RequestMapping("${openapi.paiseWallet.base-path:}")` resolves to `""` (property absent) — fine.
  - `/dev/fund` now returns a typed `AccountResponse` instead of a raw `Map`; JSON shape (`user_id`, `wallet_id`, `balance_paise`) is identical. Took the updated wallet from `TransferService.getBalance()` after `UPDATE`.
  - `TransferDetails.created_at` maps from `Transfer.createdAt()` (`Instant.toString()`), preserving the old ISO-8601 JSON.
- Verification: `mvn clean test` → 27/27 pass, including the HTTP-layer tests (`AuthTest`, `IdempotencyTest`, `ConcurrencyTest`) which still hit the real paths through MockMvc.
