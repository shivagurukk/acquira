# Acquira — Comprehensive Security Audit

**Date:** 2026-08-04
**Branch audited:** `deploy/kubernetes-aws` @ `1f19d04`
**Scope:** `acquira-core`, `acquira-common`, `acquira-batch`, `acquira-pdf`, `acquira-ai`, `frontend`, `deploy/` (Docker + Kubernetes), SQL migrations, repository hygiene.
**Method:** Static source review (white-box). No running instance was tested — no dynamic/penetration testing was performed. See §7 for what still needs hands-on validation.

---

## 1. Executive Summary

Acquira is a multi-tenant card-acquiring analytics platform: Spring Boot 3.2 / Java 21 backend, React 19 frontend, PostgreSQL, deployed to Kubernetes on AWS. It handles merchant master data, transaction facts (including card numbers), settlement/fee data, and per-tenant financial reporting for banks.

The codebase shows **real and deliberate security engineering** — the AI SQL layer runs inside a `READ ONLY` transaction with tenant-predicate injection, the Data Explorer uses strict column whitelists, the password-reset OTP flow is hashed at rest with generic responses, path traversal is correctly defended in both report download endpoints, and API keys are BCrypt-hashed with prefix lookup. Many findings below sit next to comments describing a *previous* vulnerability that was fixed. That is a good sign.

The problems are concentrated in three places: **deployment/bootstrap configuration**, **trust in client-supplied headers**, and **data protection around cardholder data and repository hygiene**.

One finding is critical and, in my assessment, exploitable against production today with no authentication: every production restart resets the built-in `admin` account to a known plaintext password and re-grants it `ROLE_SUPER_ADMIN`.

| Severity | Count |
|---|---|
| Critical | 2 |
| High | 5 |
| Medium | 7 |
| Low | 4 |

---

## 2. Findings

### CRITICAL

---

#### C-1 — Production startup resets the `admin` account to a known plaintext password and re-elevates it to SUPER_ADMIN

**Severity:** Critical (CVSS ~9.8 — AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H)
**Category:** Authentication / Insecure defaults

**Evidence**

`acquira-core/src/main/resources/application-prod.properties:56` runs the schema scripts on *every* boot, not just the first:

```properties
# ─── SQL init (set to 'always' on first run, then change to 'never') ───
spring.sql.init.mode=always
```

`application-prod.properties:69-70` lists `classpath:schema.sql` as the first script. `schema.sql:1441-1445`:

```sql
INSERT INTO users (username, password_hash, email, role, is_active, must_change_password) VALUES
('admin', '{noop}password', 'admin@acquira.com', 'ROLE_SUPER_ADMIN', true, false)
ON CONFLICT (username) DO UPDATE
SET password_hash = EXCLUDED.password_hash, must_change_password = FALSE, role = 'ROLE_SUPER_ADMIN';
```

Three compounding facts:

1. `ON CONFLICT ... DO UPDATE` — this is not `DO NOTHING`. It **overwrites** whatever password the customer set.
2. `{noop}` is Spring Security's `NoOpPasswordEncoder` prefix. `SecurityConfig.java:139` uses `PasswordEncoderFactories.createDelegatingPasswordEncoder()`, which honours the `{noop}` prefix — so the literal string `password` authenticates successfully.
3. `must_change_password = FALSE` and `role = 'ROLE_SUPER_ADMIN'` are force-reset too, so the forced-rotation guard is disabled and any de-privileging is undone.

The same seed appears three times in `schema.sql` (lines 1442, 3321, 5201 — the file has duplicated blocks) and again in `data.sql:226` (that copy is `DO NOTHING`, which is safe; the `schema.sql` copies are not).

The Kubernetes ConfigMap ships the same posture — `deploy/k8s/02-configmap.yaml` sets `SPRING_PROFILES_ACTIVE: "prod"` *and* `SPRING_SQL_INIT_MODE: "always"`, despite its own comment saying AWS should use `never`.

**Impact**

Anyone who can reach the login page can authenticate as `admin` / `password` with `ROLE_SUPER_ADMIN` after any pod restart, rollout, crash-loop, or node drain — i.e. routinely. `SecurityStartupGuard` does not catch this; it checks the JWT secret and DB password, not seeded credentials. Super-admin in this system means cross-tenant visibility over every bank's merchants, transactions and settlement data (`JwtRequestFilter.java:213-227` grants super-admins *all* tenant IDs), plus admin control of SMTP config, S3 credentials, API keys and data migration. This is a full multi-tenant compromise and, for a payment-adjacent platform, a reportable breach.

**Remediation**

1. Immediately: `UPDATE users SET password_hash = '<bcrypt hash>', must_change_password = TRUE WHERE username = 'admin';` and verify it is not `{noop}`-prefixed. Rotate it after the fix below lands, since it will otherwise be re-clobbered.
2. Change `spring.sql.init.mode` to `never` in `application-prod.properties` and `SPRING_SQL_INIT_MODE: "never"` in `deploy/k8s/02-configmap.yaml`. Schema changes belong in the migration Job (`08-migration-job.example.yaml`), not in application startup.
3. Change all three `schema.sql` admin inserts to `ON CONFLICT (username) DO NOTHING`, and replace `{noop}password` with a BCrypt hash of a value supplied at provision time — or better, remove the seeded account entirely and create the first admin through `TenantProvisionController`.
4. Extend `SecurityStartupGuard` to fail startup in the `prod` profile if any `users.password_hash` begins with `{noop}`.

---

#### C-2 — Row-Level Security is effectively absent on all cardholder and financial tables

**Severity:** Critical (defence-in-depth failure; realised impact depends on C-1 / H-1)
**Category:** Access control / Tenant isolation

**Evidence**

`TenantAspect.java:22-56` sets `app.current_tenant` on the DB session for every service/repository call, and its own comment describes RLS as the backstop: *"app-layer `WHERE tenant_id = ?` still enforces isolation; RLS, if/when forced, will block reads rather than leak them."*

That backstop does not exist in practice:

- `ALL_MIGRATIONS_CONSOLIDATED.sql` contains **10** `ENABLE ROW LEVEL SECURITY` statements and only **3** `FORCE ROW LEVEL SECURITY`.
- Coverage check on the tables that actually hold regulated data — all zero:

| Table | RLS enabled |
|---|---|
| `fact_transaction` (incl. `card_number`, `arn`, `auth_code`) | ✗ |
| `dim_merchant` | ✗ |
| `dim_store` | ✗ |
| `sum_daily_merchant` | ✗ |
| `sum_monthly_card` | ✗ |
| `users` | ✗ |

RLS is enabled only on peripheral tables (`alert_rule`, `alert_history`, `api_key`, `api_request_log`, `merchant_churn_score`, `merchant_segment`, …).

- Even those 10 are inert: the application connects as `postgres` (`application.properties:5`, `02-configmap.yaml`, `03-postgres.yaml`, `docker-compose.yml`). Postgres **exempts the table owner from RLS unless `FORCE` is set, and exempts superusers unconditionally.** `postgres` is both. So 7 of the 10 policies are bypassed by ownership, and all 10 by superuser status.

**Impact**

Tenant isolation rests entirely on ~348 hand-written `WHERE tenant_id = ?` clauses across the codebase. A single omission in any current or future query — in a repository, a `JdbcTemplate` call, a batch job, or an LLM-generated statement — leaks one bank's merchant and transaction data to another with no database-layer containment. In a bank-facing multi-tenant product this is usually a contractual and regulatory obligation, not just a hardening nicety.

**Remediation**

1. Create a dedicated non-superuser, non-owner application role (`acquira_app`) and switch `SPRING_DATASOURCE_USERNAME` to it. Grant only `SELECT/INSERT/UPDATE/DELETE` on application tables. This one change activates the 10 existing policies.
2. Extend `ENABLE` + `FORCE ROW LEVEL SECURITY` and a `tenant_isolation_policy` to every table carrying a `tenant_id`, starting with `fact_transaction`, `dim_merchant`, `dim_store`, `dim_terminal`, and the `sum_*` family.
3. Add a CI test that enumerates `information_schema.columns` for `tenant_id` and fails if any such table lacks a forced policy.
4. Keep the batch/migration jobs on a separate elevated role that explicitly bypasses RLS, rather than weakening the app role.

---

### HIGH

---

#### H-1 — `X-Forwarded-For` is trusted unconditionally: IP allowlists and all rate limits are bypassable

**Severity:** High
**Category:** Access control / Anti-automation

**Evidence**

Three independent controls derive the client IP from a client-controlled header with no trusted-proxy validation:

`RateLimitFilter.java:101-107`
```java
private String getClientIp(HttpServletRequest request) {
    String xff = request.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isEmpty()) {
        return xff.split(",")[0].trim();   // first element = attacker-supplied
    }
    return request.getRemoteAddr();
}
```

`ApiKeyAuthFilter.java:263-270` — identical logic, and its result feeds the **API key IP allowlist**:

```java
String allowedIps = (String) r.get("allowed_ips");
if (!ipAllowed(allowedIps, clientIp)) { ... return AuthResult.fail(403, ...); }
```

`AuthController.java:740-741` — same pattern, feeding login and OTP rate limiting.

The deployment confirms the header is attacker-reachable. `acq-congif.txt` (the production nginx config) uses:
```nginx
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
```
`$proxy_add_x_forwarded_for` **appends** to the client's submitted value rather than replacing it, so `xff.split(",")[0]` is exactly the attacker's string. The Kubernetes ingress (`07-ingress.yaml`) sets no XFF handling at all.

**Impact**

- **API key IP allowlist is decorative.** A stolen or leaked API key restricted to a customer's office IP works from anywhere by sending `X-Forwarded-For: <allowed-ip>`. This is the primary compensating control for external API key theft.
- **All rate limits are bypassable** by rotating a random header value per request: 200/min general, 20/min external, 5/min on `/api/admin/migration/*` (destructive), and the login/OTP limiter. That re-opens credential stuffing and OTP brute force at unbounded rate. The per-user DB lockout (`MAX_USER_ATTEMPTS`) still bounds per-account guessing, but password *spraying* across many accounts becomes unthrottled.
- Audit records (`AuthController.java:267`, `api_request_log.client_ip`) contain attacker-chosen values, degrading forensics.

**Remediation**

1. Set `server.forward-headers-strategy=NATIVE` and configure Tomcat's `RemoteIpValve` with an explicit `internalProxies` regex matching only your ingress/ALB CIDR; then use `request.getRemoteAddr()` everywhere and delete the three hand-rolled `getClientIp` methods.
2. On nginx, use `proxy_set_header X-Forwarded-For $remote_addr;` (replace, not append) — or keep `$proxy_add_x_forwarded_for` and parse from the *right*, skipping N trusted hops.
3. On the AWS ALB, rely on the last XFF element and set `routing.http.xff_header_processing.mode` appropriately.
4. Until fixed, treat the API key `allowed_ips` feature as non-functional and do not represent it to customers as a control.

---

#### H-2 — Cardholder PANs are stored and propagated in cleartext, and are exposed unmasked on several paths

**Severity:** High
**Category:** Data protection / PCI DSS

**Evidence**

`card_number` is ingested and stored verbatim — there is no masking, truncation, hashing, or tokenisation anywhere in the ingest path:

- `TransactionJobConfig.java:715, 914, 920` — staged and copied straight into `fact_transaction`.
- `BackfillIngestionService.java:189` — `ps.setString(i++, getString(row, "card_number"));`
- `BulkMigrationService.java:450, 486-490` — mapped from an arbitrary source column.

It is then **copied into further tables**:
- `sum_monthly_card.card_number` (`TransactionJobConfig.java:1630-1634`) — a per-card spend history table.
- `sum_daily_merchant.top_spending_customer_id = r.card_number` (`TransactionJobConfig.java:1673`, `BackfillIngestionService.java:344`, `BulkMigrationService.java:760`) — a raw PAN stored in a column named as an opaque customer id, then surfaced through `SumDailyMerchant.topSpendingCustomerId` (`SumDailyMerchant.java:57-58`).

Masking exists in exactly one place — `TransactionController.java:293-297`:
```java
private String maskCardNumber(String cardNumber) {
    if (cardNumber == null || cardNumber.length() < 4) return "****";
    if (cardNumber.contains("*")) return cardNumber;
    return "****" + cardNumber.substring(cardNumber.length() - 4);
}
```
That is display-layer only and is **not** applied to:
- `AnalyticsExplorerController.java:156` — `AGG_COLUMNS.put("card_number", "t.card_number")`, exposed to users as a groupable dimension labelled "Card".
- `SumMonthlyCardRepository.java:28-31, 50-53` — groups and returns by `card_number`.
- `sum_daily_merchant.top_spending_customer_id` on every dashboard that reads it.

The system's own intent is documented but unenforced: `DataMigration.jsx:51` describes the field as `'Masked card PAN for loyalty tracking'` — a hint to the operator, not a validation. `DataExplorerController.java:44` notes row-level fields were removed from *that* whitelist, but `AnalyticsExplorerController` still carries them.

**Impact**

If any acquirer feed contains full PANs — which the "Masked card PAN" hint implies is expected but not guaranteed, and which nothing in the code prevents — then Acquira stores cleartext PAN at rest with no encryption, no key management, no truncation, and no access logging on those columns, alongside `auth_code`, `arn` and `rrn_number`. That places the entire application, its database, its backups, its S3 report storage and its Kubernetes cluster inside PCI DSS scope (Req. 3.3 render PAN unreadable, 3.5 key management, 7 need-to-know, 10 access logging). Combined with C-1, an unauthenticated attacker reaches this data. Combined with C-2, one tenant may reach another's.

Independently, the repo already contains generated merchant reports and transaction samples (see H-5) — meaning this data class has already left the controlled environment.

**Remediation**

1. Decide the data model deliberately: for loyalty/repeat-visit analytics you need a *stable pseudonym*, not a PAN. Replace `card_number` with `HMAC-SHA256(PAN, tenant_key)` at the ingest boundary, plus a separate `card_last4` column for display. All existing analytics (`COUNT(DISTINCT)`, `GROUP BY`, top-spender) work unchanged on the HMAC.
2. Reject or truncate at ingest: validate incoming `card_number` against a masked pattern and fail the row (or truncate to last 4) if a full PAN is detected via Luhn + length.
3. Backfill: rewrite `fact_transaction`, `sum_monthly_card` and `sum_daily_merchant.top_spending_customer_id` to the new representation, then `VACUUM FULL` and rotate backups.
4. Remove `card_number` from `AnalyticsExplorerController.AGG_COLUMNS`, or return only the pseudonym.
5. Confirm with your acquiring partners and QSA whether full PANs are present in the source feeds. If yes, treat this as a live PCI scope issue, not a backlog item.

---

#### H-3 — Spring Boot 3.2.0 (Nov 2023) with ~21 months of unpatched CVEs

**Severity:** High
**Category:** Vulnerable dependencies

**Evidence**

`pom.xml:8-9`
```xml
<artifactId>spring-boot-starter-parent</artifactId>
<version>3.2.0</version>
```
`pom.xml:19` — `<jjwt.version>0.11.5</jjwt.version>` (superseded by the 0.12.x line; the code still uses the deprecated `parserBuilder()`/`setSigningKey` API in `JwtUtil.java:46`).

Spring Boot 3.2.0 pins Spring Framework 6.1.1, Spring Security 6.2.0 and Tomcat 10.1.16 — all substantially behind. This transitively pulls in known-vulnerable Spring Framework, Spring Security, Tomcat and Jackson releases. The OSS Index / NVD delta over this window includes authentication-bypass and DoS classes of issue in exactly these components.

A `dependency-check-maven` plugin is declared (`pom.xml:130-131`) but there is no evidence it gates the build.

The frontend is in better shape but not clean — `npm audit` reports **8 vulnerabilities (1 critical, 5 high, 2 moderate)**, including a `react-router` advisory chain and `esbuild`/`vite` dev-server request forgery.

**Impact**

Unpatched framework CVEs in the authentication and HTTP-handling layers of an internet-facing financial application. Exploitation typically requires no application-specific knowledge, and these versions are trivially fingerprinted from error pages and headers.

**Remediation**

1. Upgrade to the current Spring Boot 3.x maintenance release. From 3.2.0 this is largely a version bump; budget for Spring Security 6.2→6.5 `authorizeHttpRequests` and method-security deltas.
2. Upgrade `jjwt` to 0.12.x and migrate `JwtUtil` off the deprecated parser API.
3. Run `npm audit fix`; the `vite` fix is a major bump — verify the build.
4. Wire `dependency-check-maven` and `npm audit --audit-level=high` into CI as **failing** gates, and enable Dependabot/Renovate.

---

#### H-4 — JWT access *and* refresh tokens are stored in `localStorage`

**Severity:** High
**Category:** Session management / XSS impact amplification

**Evidence**

`frontend/src/api/axios.js:15,19,92,95`
```js
let _memRefreshToken = localStorage.getItem('refreshToken') || null;
const token = localStorage.getItem('token');
...
localStorage.setItem('token', jwt);
localStorage.setItem('refreshToken', newRefresh);
```

The backend already does the right thing — `AuthController` issues the refresh token as a `ResponseCookie` (`HttpHeaders.SET_COOKIE`), and `axios.js:12-14` acknowledges the `localStorage` copy is a *"backward-compat fallback"*. The fallback was never removed, so both tokens sit in JavaScript-readable storage. Roughly 25+ components read `localStorage.getItem('token')` directly (`MerchantHierarchy.jsx`, `MerchantSummary.jsx`, and others), so this is not a single-file change.

Amplifying factors:
- No `Content-Security-Policy` header anywhere — `SecurityConfig.java:50-57` sets frame-options, nosniff, HSTS and cache-control, but no CSP. The nginx config (`acq-congif.txt`) sets only `X-Frame-Options` and `X-Content-Type-Options`.
- `EmailCampaignHub.jsx:281` renders a stored, user-authored template body with `dangerouslySetInnerHTML={{ __html: previewOpen.body }}` — a concrete stored-XSS sink.
- Refresh token TTL is 7 days (`JwtUtil.java:26`), so a stolen refresh token gives a week of persistent access that survives password changes unless sessions are explicitly revoked.

**Impact**

Any XSS — including the email-template sink above — yields both tokens. The refresh token in particular converts a transient script execution into 7 days of authenticated API access from the attacker's own machine, outside the browser, invisible to session-timeout logic.

**Remediation**

1. Delete the `localStorage` refresh-token path entirely; rely on the existing `HttpOnly; Secure; SameSite=Strict` cookie.
2. Keep the *access* token in memory only (a module-scoped variable in `axios.js`), rehydrating via the refresh cookie on page load. Refactor the ~25 direct `localStorage.getItem('token')` call sites onto the shared axios instance.
3. Add a CSP: `default-src 'self'; script-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'`. Vite's build output is CSP-friendly; verify no inline handlers remain.
4. Sanitise the email-template preview with DOMPurify, or render it inside a sandboxed iframe.

---

#### H-5 — Production merchant reports and transaction data are committed to the Git repository

**Severity:** High
**Category:** Data protection / Repository hygiene

**Evidence**

`git ls-files` returns **37** tracked data files, including:

- `reports/Merchant_Insight_1..15_2026-01.pdf` — 15 generated per-merchant insight reports, plus a duplicate set in `reports/New folder/`.
- `data/merchant_master.xlsx`, `data/Trnx sample.xlsx`, `merchant_master_sample - Copy.xlsx`
- `transactions_10k_ssample.xlsx` (2.2 MB — ~10k transaction rows)
- `bulk_merchants.csv`, `bulk_transactions.csv`, `test_report.pdf`, `temp_log.txt` (144 KB)

`.gitignore` *does* list `reports/`, `data/`, `*.xlsx`, `*.csv`, `temp_log.txt` — but `.gitignore` has no effect on already-tracked files. These were committed before the rules were added and remain fully present in the working tree and in history.

Also tracked: `acq-congif.txt`, the production nginx configuration, and `all-migrations.sql` / `prod-missing-2026-08-03.sql` describing the production schema. Roughly 40 `*.ps1` operational scripts and ~15 `compile*.log` build logs are ignored but present locally.

**Impact**

Merchant identities, transaction volumes, fee structures and settlement figures — commercially sensitive per-bank data — are readable by anyone with repository access, including contractors, CI systems, any fork, and anyone who obtains a clone. This survives deletion from `HEAD`; the data lives in every clone's history. If the repository is or ever becomes public, or is mirrored to a SaaS CI provider, this is a disclosure event. It also directly contradicts the tenant-isolation model the application enforces at runtime.

**Remediation**

1. Confirm whether these files contain real or synthetic data. The `reports/Merchant_Insight_*.pdf` set appears to be genuine generated output — verify first.
2. If real: treat as a disclosure. Purge with `git filter-repo --path reports/ --path data/ --invert-paths` (plus the loose `.xlsx`/`.csv`/`.pdf`), force-push, rotate any credential visible in `acq-congif.txt` or the SQL dumps, and have every clone re-cloned.
3. `git rm --cached` the files even before the history rewrite, so they stop being redistributed.
4. Add a pre-commit hook or CI check that rejects `*.xlsx`, `*.csv`, `*.pdf` and `*.log` outside a designated fixtures directory, and add `gitleaks` to CI.
5. Replace the samples with small, synthetic fixtures.

---

### MEDIUM

---

#### M-1 — No TLS in the shipped Kubernetes and Docker deployment path

**Category:** Transport security

`deploy/k8s/07-ingress.yaml` declares no `spec.tls` block and no cert-manager or ACM annotations — only plain HTTP on `acquira.localtest.me`. `acq-congif.txt` (production nginx) listens on port 80 only, with no `listen 443`, no `ssl_certificate`, and no HTTP→HTTPS redirect. TLS appears only as a manual afterthought in `RHEL_DEPLOYMENT_GUIDE.md:598` ("PHASE 8: SSL/TLS").

Meanwhile `SecurityConfig.java:53-55` emits HSTS with a one-year max-age and `includeSubDomains`. HSTS over plain HTTP is ignored by browsers on first contact and, once TLS *is* enabled, a misconfiguration will hard-fail the domain for a year.

**Impact:** Bearer JWTs, `X-API-Key` values, and merchant/transaction payloads traverse the network in cleartext. Anyone on-path (corporate proxy, cloud network, compromised node) can capture credentials and data. The comment in `07-ingress.yaml` says to swap to an ALB on AWS, but nothing in the repo terminates TLS.

**Remediation:** Add `spec.tls` with cert-manager (or `alb.ingress.kubernetes.io/certificate-arn` + `ssl-redirect: '443'`), enforce redirect at the ingress, and set `server.servlet.session.cookie.secure=true`. Verify the refresh cookie is issued with `Secure`.

---

#### M-2 — Default `postgres`/`postgres` credentials committed across every deployment artefact

**Category:** Configuration / Secrets management

`application.properties:5-6` (`DB_USERNAME:postgres` / `DB_PASSWORD:postgres`), `02-configmap.yaml` (`SPRING_DATASOURCE_USERNAME: "postgres"`), `03-postgres.yaml` (`POSTGRES_PASSWORD: postgres` as a literal env value, not a Secret), `docker-compose.yml` (same), and `01-secret.example.yaml` (`SPRING_DATASOURCE_PASSWORD: "postgres"`).

`SecurityStartupGuard.java:79-86` *does* fail startup on `postgres`/`CHANGE_ME` in the prod profile — good. But the guard is registered in `acquira-core` only; `acquira-batch`, `acquira-pdf` and `acquira-ai` have no equivalent. And `03-postgres.yaml` hardcodes the password into the Deployment spec, where it is visible to anyone with namespace read access.

**Remediation:** Move the Postgres password into the existing `acquira-secrets` Secret and reference it via `secretKeyRef`. Migrate to AWS Secrets Manager via External Secrets (the resolver already exists — `AwsSecretsManagerResolver.java`, `acquira.secrets.provider=AWS`). Replicate `SecurityStartupGuard` into `acquira-common` so all four modules enforce it. Change the example Secret's placeholders so they cannot be copied verbatim into a working config.

---

#### M-3 — Unauthenticated account-existence oracle on `/api/sso/request-access`

**Category:** Information disclosure

`SecurityConfig.java:62` permits `/api/sso/**` without authentication. `SsoController.java:412-417`:

```java
if (userRepository.existsByEmail(email.toLowerCase().trim())) {
    return ResponseEntity.badRequest().body(Map.of("error", "An account with this email already exists"));
}
if (accessRequestRepository.existsByEmailAndStatus(email.toLowerCase().trim(), "PENDING")) {
    return ResponseEntity.badRequest().body(Map.of("error", "A pending request already exists for this email"));
}
```

This is a direct contradiction of the enumeration hardening applied elsewhere — `AuthController.java:66-69` deliberately collapses all login failures into one `GENERIC_AUTH_FAILURE` string, and the OTP flow uses `OTP_GENERIC_SENT` for the same reason. The SSO endpoint undoes it.

There is also no dedicated rate limit; it falls under the general 200/min bucket, which H-1 makes bypassable.

**Impact:** An attacker enumerates valid corporate email addresses at scale, producing a target list for the credential-stuffing that C-1 and H-1 make viable. Unbounded `AccessRequest` rows are also insertable, a storage/queue DoS and a phishing vector into the admin approval workflow.

**Remediation:** Return the same generic `"request_submitted"` response in all three branches, deduplicating server-side. Apply the `isRateLimited` helper keyed on `(ip|email)`. Add a CAPTCHA or a signed pre-token from the Microsoft SSO callback so only users who actually completed an SSO handshake can submit.

---

#### M-4 — Static break-glass API key is all-tenant and unrate-limited

**Category:** Access control

`ApiKeyAuthFilter.java:198-211`: when `external.api.allow-static-key=true`, a single shared secret authenticates against **any** tenant (selected by the caller's own `tenantCode` parameter), is granted `Set.of()` scopes, and receives `Integer.MAX_VALUE` as its rate limit — `doFilterInternal:102` skips the limiter entirely for static keys.

The empty scope set interacts badly with `ApiScopes.require(...)`: verify whether an empty set is interpreted as "no scopes" (deny) or falls through. If it denies, the break-glass key cannot actually do anything and should be deleted; if it falls through, it is an unlimited all-tenant credential.

It is correctly **off by default** (`allow-static-key:false`) and logs a warning on use, which is why this is Medium rather than High.

**Remediation:** Remove the static key path. If break-glass is genuinely required, issue a normal DB-backed key with a short expiry, an IP allowlist (once H-1 is fixed), explicit scopes, and a real rate limit. Add a `SecurityStartupGuard` check that fails the prod profile when `allow-static-key=true`.

---

#### M-5 — 2 GB upload limit with no file-type validation is a denial-of-service vector

**Category:** Availability / Input validation

`application.properties:177-178` sets `spring.servlet.multipart.max-file-size=2048MB` and `max-request-size=2048MB`; `07-ingress.yaml` matches with `proxy-body-size: "2g"`. `PdfController.java:892-893` accepts a `MultipartFile` and branches on `getOriginalFilename()` alone — no content-type check, no magic-byte check, no row-count cap before parsing.

`05-core.yaml` pins `replicas: 1` with a 4 GiB memory limit and a `Recreate` strategy. Excel parsing is memory-resident.

**Impact:** A single authenticated user uploading a 2 GB file — or a zip-bomb-style XLSX (a small file that expands enormously in POI's object model) — can OOM the only core pod. Because there is exactly one replica and the rollout strategy is `Recreate`, that is a total platform outage, not a degraded one. H-1 means the 5/min migration rate limit does not bound the attempt rate.

**Remediation:** Lower the limit to what real merchant files require (50–100 MB is typical). Validate content type and magic bytes before parsing. Stream with POI's `SXSSF`/event API rather than the DOM model. Cap parsed row counts. Longer term, address the single-replica constraint documented in `05-core.yaml` — it is also a single point of failure.

---

#### M-6 — Tenant-context failures fail open

**Category:** Tenant isolation

`JwtRequestFilter.java:230-232`:
```java
} catch (Exception e) {
    logger.warn("Could not set tenant context for " + username + ": " + e.getMessage());
}
```
The request proceeds **authenticated but with no tenant context**. Similarly `TenantAspect.java:57-60` swallows failures to set `app.current_tenant`, explicitly choosing not to block.

Downstream behaviour then depends entirely on each controller. `DataExplorerController.getTenantId()` and `MerchantInsightController:44` correctly throw or return 403 on a null tenant — but that is per-controller discipline across 53 controllers, not a systemic guarantee. Any query that treats a null tenant as "no filter" returns cross-tenant data.

The X-Tenant-Id validation itself is solid (`JwtRequestFilter.java:148-160` rejects rather than silently falling back, with a comment describing the IDOR this fixed) — the gap is only the exception path.

**Remediation:** Fail closed: on tenant-resolution failure, clear the `SecurityContext` and return 500/403 rather than continuing. Add a filter assertion after `JwtRequestFilter` that rejects any authenticated request reaching a `/api/**` handler with a null `TenantContext`, excluding an explicit allowlist of tenant-agnostic endpoints.

---

#### M-7 — Method-level authorization is absent on 40 of 53 controllers

**Category:** Access control

39 controllers in `acquira-core/.../controller/` carry no `@PreAuthorize` annotation. Most are adequately covered by URL rules — `SecurityConfig.java:67` gates `/api/admin/**` to `ADMIN`/`SUPER_ADMIN`, which correctly protects `S3SettingsController` (`/api/admin/s3-settings`) and `AuditLogController` (`/api/admin/audit-logs`) — so this is defence-in-depth, not an open door.

The gap is that several sensitive controllers sit **outside** the `/api/admin/**` prefix and are therefore available to *every authenticated user*, including the lowest-privileged `ROLE_USER`:

- `ReportBuilderController` — `/api/reports`: create, update, **delete** report templates and scheduled report jobs.
- `DataExplorerController` — `/api/explorer/query`: arbitrary pivot/aggregate over all merchant and transaction data for the tenant.
- `AnalyticsExplorerController` — includes the `card_number` dimension (H-2).
- `ExecutiveDashboardController`, `FinanceController`, `RevenueKpiController` — bank-level financial aggregates.

`@EnableMethodSecurity` is on (`SecurityConfig.java:29`), so the mechanism is available and used by the other 13 controllers — the coverage is simply incomplete.

**Remediation:** Define the intended role matrix per controller, then annotate. Verify template/schedule mutations are tenant-scoped *and* ownership-checked, not just authenticated. Consider a default-deny convention: annotate at class level and relax per method.

---

### LOW

---

**L-1 — Default JWT secret is a compile-time constant in three places.**
`JwtUtil.java:28`, `SecurityStartupGuard.java:24,26` — `AcquiraDefaultDevKeyAtLeast32Chars!!`. `docker-compose.yml` and `01-secret.example.yaml` ship similarly guessable placeholders. `SecurityStartupGuard` correctly refuses to start the prod profile with the default (`:47-62`) — but only in `acquira-core`, and only when the `prod` profile is actually active. A misconfigured `SPRING_PROFILES_ACTIVE` disables the guard silently. *Fix:* remove the default entirely so the bean fails to construct without an explicit value; log the active profile at startup at `WARN` when it is not `prod` in a container.

**L-2 — Encryption master key derivation truncates rather than deriving.**
`SecretCrypto.java:49-61` takes the first 32 UTF-8 bytes of `APP_ENCRYPTION_KEY` as the AES-256 key. AES-256-GCM usage itself is correct (random 12-byte IV, 128-bit tag, `enc:v1:` versioned envelope) — this is good work. But a human-typed passphrase has far less than 256 bits of entropy, and truncation preserves none of the rest. *Fix:* derive with HKDF-SHA256 or PBKDF2 (or require a base64-encoded 32-byte random key). Note the migration constraint documented in `01-secret.example.yaml` — changing the key makes stored SMTP/S3 secrets undecryptable, so this needs a re-encryption path.

**L-3 — Usernames and internal state are logged at WARN on the auth path.**
`JwtRequestFilter.java:116,122,157` log usernames and attempted tenant IDs. `AuthController` logs usernames and (spoofable) IPs to the audit trail. `PdfController.java:1069` logs resolved filesystem paths. With `logging.level.com.acquira=INFO` in prod, these reach whatever aggregator receives stdout. Not a leak by itself, but it puts identity data into a log pipeline that may have a different retention and access model than the database. *Fix:* review log-sink access controls and retention; consider hashing usernames in high-volume filter logs.

**L-4 — Frontend `sessionStorage` API cache is not cleared on tenant switch or logout.**
`apiCache.js:40` keys entries by `localStorage.getItem('defaultTenantId')`, which is the right instinct, but the clearing logic (`:107-125`) should be verified to run on both logout and `switch-context`. On a shared workstation, cached responses from a prior session or a prior tenant could surface. *Fix:* clear all `SS_PREFIX` keys on logout and on tenant switch; add a test.

---

## 3. What the codebase does well

Worth recording, both because it is genuinely good and because it should not be regressed during remediation:

- **AI-generated SQL execution** (`AiQueryService.java:498-590`) is properly defended: execution inside `SET TRANSACTION READ ONLY` (Postgres refuses writes at SQLSTATE 25006 regardless of what the keyword blocklist misses), a hard row cap the model cannot omit, per-statement timeout, mandatory tenant-predicate injection with correct alias qualification, `UNION` blocked, single-statement enforcement, and table-reference whitelisting. The layered "Java validation is the early exit, Postgres is the enforcement boundary" design is the right architecture.
- **Data Explorer** (`DataExplorerController.java:79-132`) uses strict column/measure/aggregation whitelists with parameterised values — no injection surface despite being a dynamic query builder.
- **Path traversal** is correctly handled in both download endpoints (`ExternalReportApiController.java:157-172`, `PdfController.java:1063`): filename-only extraction, regex validation, `normalize()`, and a `startsWith` containment check.
- **Password reset OTP** (`AuthController.java:547-700`): BCrypt-hashed OTP at rest, `SecureRandom`, 10-minute TTL, 5-attempt burn, generic responses throughout, single-use ticket separated from the OTP, and session revocation on completion.
- **API key authentication** (`ApiKeyAuthFilter.java`): BCrypt verification with indexed prefix lookup, expiry, per-key rate limiting, scope model, usage logging, and a tenant boundary derived from the key row rather than from caller input.
- **Auth error semantics** (`SecurityConfig.java:91-104`): 401 vs 403 correctly distinguished, with a comment explaining exactly why it matters.
- Many findings sit beside comments describing a *previously fixed* vulnerability (P1-2, P1-5, P2-1, P2-4, P2-7, GAP-13, GAP-19). There is an existing security-review habit here; this audit builds on it rather than starting from zero.

---

## 4. Prioritised Action Plan

### Immediate — within 24 hours

| # | Action | Finding | Effort |
|---|---|---|---|
| 1 | Reset the production `admin` password to a BCrypt hash; confirm no `{noop}` hashes remain | C-1 | 15 min |
| 2 | Set `spring.sql.init.mode=never` in `application-prod.properties` and `SPRING_SQL_INIT_MODE: "never"` in the ConfigMap | C-1 | 30 min |
| 3 | Change all three `schema.sql` admin seeds to `ON CONFLICT DO NOTHING` and drop `{noop}password` | C-1 | 1 hr |
| 4 | Review auth logs for `admin` logins not attributable to a known operator | C-1 | 1 hr |
| 5 | `git rm --cached` all tracked `reports/`, `data/`, `*.xlsx`, `*.csv` files; assess whether the data is real | H-5 | 1 hr |

### Week 1

| # | Action | Finding | Effort |
|---|---|---|---|
| 6 | Configure `RemoteIpValve` with trusted proxies; delete the three hand-rolled `getClientIp` methods | H-1 | 0.5 day |
| 7 | Fix nginx / ALB XFF handling to replace rather than append | H-1 | 2 hrs |
| 8 | Enable TLS at the ingress with cert-manager or ACM; enforce HTTPS redirect | M-1 | 0.5 day |
| 9 | Move the Postgres password into a Secret; remove it from `03-postgres.yaml` and the ConfigMap | M-2 | 2 hrs |
| 10 | Make `/api/sso/request-access` responses generic and rate-limited | M-3 | 2 hrs |
| 11 | Extend `SecurityStartupGuard` into `acquira-common`; add `{noop}` and `allow-static-key` checks | L-1, M-4 | 0.5 day |

### Weeks 2–4

| # | Action | Finding | Effort |
|---|---|---|---|
| 12 | Determine whether source feeds carry full PANs; design and implement HMAC pseudonymisation + `card_last4` | H-2 | 1 week |
| 13 | Create the non-superuser `acquira_app` role; extend forced RLS to all `tenant_id` tables | C-2 | 1 week |
| 14 | Upgrade Spring Boot, `jjwt`, and the npm tree; add failing CI dependency gates | H-3 | 3 days |
| 15 | Remove the `localStorage` refresh token; move the access token to memory; add CSP | H-4 | 3 days |
| 16 | Purge the data files from Git history; rotate anything exposed | H-5 | 1 day |
| 17 | Lower upload limits; add content-type and magic-byte validation; stream Excel parsing | M-5 | 2 days |

### Quarter

| # | Action | Finding |
|---|---|---|
| 18 | Fail-closed tenant context + a global assertion filter | M-6 |
| 19 | Define and apply the full `@PreAuthorize` role matrix across all 53 controllers | M-7 |
| 20 | HKDF key derivation for `APP_ENCRYPTION_KEY`, with a re-encryption migration | L-2 |
| 21 | Address the single-replica constraint (web/worker split) — availability and blast radius | M-5 |
| 22 | Commission an external penetration test once items 1–17 are closed | — |

---

## 5. Compliance Gap Summary

**PCI DSS** — Applicability hinges on H-2. If full PANs are present in `fact_transaction`:

| Req | Gap |
|---|---|
| 3.3 | PAN not rendered unreadable at rest (H-2) |
| 3.5 | No key management for cardholder data (H-2) |
| 4.1 | Cardholder data transmitted over plain HTTP (M-1) |
| 6.2 | Unpatched framework components (H-3) |
| 7.1 | Need-to-know not enforced at the DB layer (C-2, M-7) |
| 8.2 | Default vendor credential in production (C-1) |
| 10.2 | Audit trail attributable to a spoofable IP (H-1) |

**GDPR / general data protection** — Cardholder and merchant contact data is personal data. Gaps: Art. 32 (encryption at rest, transport security — H-2, M-1), Art. 5(1)(f) (integrity/confidentiality — C-1, H-5), Art. 33 (H-5 may itself be a notifiable breach depending on whether the committed reports contain real merchant data).

**SOC 2 / ISO 27001** — Change management (`sql.init.mode=always` mutating production data on every boot), access control (C-1, M-7), vulnerability management (H-3, no failing CI gates), and logical separation of customer data (C-2).

---

## 6. Assessment Limitations

This was a static source review. I did not:
- run the application or issue any request against it;
- inspect the production database, its actual `card_number` contents, or its live grants;
- review AWS account configuration (IAM, security groups, RDS encryption/backups, S3 bucket policies, KMS);
- inspect CI/CD pipeline configuration or secrets;
- review the `acquira-ai` model provider integrations for prompt-injection or data-egress concerns beyond the SQL execution layer;
- assess `frontend/dist` build output or any deployed bundle.

Severity ratings assume the application is internet-reachable and multi-tenant with mutually untrusted tenants. Adjust downward for a single-tenant or network-isolated deployment.

---

## 7. Areas Requiring Manual / Dynamic Testing

Ordered by expected value:

1. **Confirm C-1 end-to-end.** In a staging environment matching production config, restart the pod and attempt `admin` / `password`. This is the single most important verification in this report.
2. **Confirm H-1.** Send a request with a forged `X-Forwarded-For` matching an API key's `allowed_ips` and verify it is accepted. Then rotate the header across many requests and confirm the rate limiter never triggers.
3. **Inspect real `card_number` values.** `SELECT card_number FROM fact_transaction LIMIT 20` — determine whether these are full PANs, masked, or already truncated. This single query determines whether H-2 is a Critical PCI incident or a Medium hardening item.
4. **Verify actual DB grants.** `\du` and `SELECT relrowsecurity, relforcerowsecurity FROM pg_class` on the live database — confirm the app role and whether RLS is genuinely inert (C-2).
5. **Cross-tenant IDOR sweep.** With two tenants and a low-privilege user in each, walk every `/api/**` endpoint substituting the other tenant's `X-Tenant-Id`, merchant IDs, store IDs, template IDs and saved-filter IDs. `JwtRequestFilter` looks correct, but M-6's fail-open path and M-7's missing method security mean per-endpoint verification is warranted.
6. **Privilege escalation sweep.** As `ROLE_USER`, exercise `/api/reports/**`, `/api/explorer/**`, `/api/business/**` and the executive/finance controllers. Confirm what a low-privileged user can actually read and mutate (M-7).
7. **Stored XSS in email templates.** Inject a payload into an email campaign body and confirm whether it executes in the `dangerouslySetInnerHTML` preview and in delivered mail (H-4).
8. **AI SQL red-teaming.** Attempt prompt injection against `/api/ai/ask` to reach another tenant's data or defeat the read-only transaction. The design looks sound; adversarial testing is how you find out.
9. **Upload DoS.** Test a large XLSX and a highly-compressed one against the 2 GB limit on a pod with the production 4 GiB memory cap (M-5).
10. **Token lifecycle.** Verify refresh-token revocation actually invalidates: after logout, `logout-all`, password reset, and user deactivation, confirm the old refresh token is rejected.
11. **AWS posture review.** RDS encryption at rest and backup encryption, S3 report bucket policy and public-access block, KMS key rotation, IAM roles attached to the pod, security-group ingress, and whether `01-secret.yaml` was ever committed to a private branch.
12. **Git history secret scan.** Run `gitleaks detect --log-opts="--all"` across full history — this audit only scanned the working tree.
