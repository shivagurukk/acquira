# Acquira — API Management & Bank-Connectivity Audit

**Date:** 2026-08-08
**Scope:** outbound API surface (`/api/v1`, `/api/external`), API key lifecycle, inbound bank connectivity (Integration Hub), and the identity/authorization model that both depend on.
**Status:** audit + architecture proposal. **No code changes made.**

---

## 0. Executive summary — read this first

Acquira already has a **real, non-trivial API-key subsystem**: BCrypt-hashed keys, indexed prefix lookup, scopes, expiry, per-key rate limiting, IP allowlist, usage logging with retention, and an admin UI. This is further along than most platforms at this stage. The audit below is not "you have nothing" — it is "what you have is built for the wrong principal."

**The single most important finding:**

> **The API key is bound to a `tenant`, and in Acquira a `tenant` is a BANK, not a merchant.**
> `ApiKeyAuthFilter` resolves `tenant_id` from the key row and pushes it into `TenantContext`; every `/api/v1` query then filters by `tenant_id` **only**. `ExternalDataApiController.listMerchants()` returns *every merchant of that bank*.
>
> **Consequence: if you issue an API key to a merchant today, that merchant can read the entire bank's merchant portfolio, transaction rows, MSF, interchange and net revenue.** This is a cross-customer data breach, not a misconfiguration. There is no flag to turn on to prevent it — the concept of "merchant" as an API caller does not exist in the schema.

**The second most important finding:** the request as phrased — *"merchants fetch transaction data from one or multiple banks"* — is **not what the platform does today, and the two directions must not be confused**:

| Direction | Who initiates | Mechanism today | Code |
|---|---|---|---|
| **Inbound** (bank → Acquira) | Acquira, on a schedule | **JDBC pull** from the bank's Oracle/Postgres/MSSQL, read-only account, AES-GCM-encrypted password | `IntegrationConnection`, `IntegrationPullService` |
| **Outbound** (Acquira → consumer) | The consumer | **REST + `X-API-Key`**, tenant-scoped | `ApiKeyAuthFilter`, `ExternalDataApiController` |

There is **no** bank-side REST/OAuth integration anywhere in the codebase. A merchant never talks to a bank through Acquira; a merchant talks to *Acquira*, which serves data it already ingested from each bank. That is the correct model (see §3, Option B) — but it means "multi-bank" is a **data-model problem**, not a credential problem, and today the data model makes it impossible: `dim_merchant` is `UNIQUE(tenant_id, internal_id)` with no cross-tenant identity. The same merchant at two banks is two unrelated rows.

**Third:** `CryptoService` falls back to a **hardcoded key literal in the source** (`AcquiraDefaultEncryptKey32Chars!!`) when `app.encryption.key` is unset. That key encrypts every bank's database password in `integration_connection.encrypted_password`. If production has not set the property, bank DB credentials are protected by a value published in the repository. **Verify this before anything else in this document.**

---

# 1. Current-state audit checklist

Legend: ✅ exists and is sound · ⚠️ exists but incomplete/flawed · ❌ absent

## 1.1 Key issuance & storage

| # | Item | State | Evidence / note |
|---|---|---|---|
| 1.1.1 | Keys hashed at rest | ✅ | BCrypt via `PasswordEncoder`; plaintext returned once on create. `ApiKeyController.java:82,128` |
| 1.1.2 | Key entropy | ✅ | `UUID.randomUUID()` = 122 bits, `aqr_` prefix. `ApiKeyController.java:81` |
| 1.1.3 | Indexed lookup without plaintext | ✅ | `key_prefix` (first 12 chars) + partial index `idx_api_key_prefix_active` |
| 1.1.4 | BCrypt on every request | ⚠️ | ~50–100 ms CPU **per API call** on the hot path. At any real volume this is your throughput ceiling, and it is a cheap asymmetric DoS (a caller who knows one valid prefix forces a BCrypt per request before rate limiting is even reached — the limiter runs *after* authentication). |
| 1.1.5 | Scopes persisted safely | ⚠️ | `permissions` is `TEXT` holding hand-concatenated JSON (`"[" + join + "]"`), parsed by a hand-rolled splitter. No escaping, no validation against the known scope list. A scope string containing `"` or `,` corrupts the row. `ApiKeyController.java:88-93`, `ApiKeyAuthFilter.parseScopes()` |
| 1.1.6 | Key naming/environment separation | ❌ | No `environment` column. Sandbox and production keys are indistinguishable and hit the same data. |
| 1.1.7 | Secret shown once, never retrievable | ✅ | Correct. |

## 1.2 Key lifecycle

| # | Item | State | Note |
|---|---|---|---|
| 1.2.1 | Create | ✅ | `POST /api/admin/api-keys` |
| 1.2.2 | Update (name/scopes/expiry/rate/IPs) | ✅ | `PUT /{id}` |
| 1.2.3 | Revoke | ✅ | Soft revoke + `revoked_at/by` + rate-limiter eviction |
| 1.2.4 | **Rotate** | ❌ | No rotation endpoint at all. The only path is revoke-then-create → **guaranteed integration outage** at every rotation. No overlap window, no `rotated_from_key_id` lineage. |
| 1.2.5 | Expiry | ⚠️ | Column exists and is enforced, but **nullable and unset by default** → keys are immortal unless an admin remembers. No max-lifetime policy. |
| 1.2.6 | Expiry warning / renewal notice | ❌ | Nothing warns the consumer or the admin before a key dies. First signal is a 401 in production. |
| 1.2.7 | Auto-disable on inactivity | ❌ | `last_used` is recorded but never acted on. |
| 1.2.8 | Emergency kill-switch (all keys, one tenant) | ❌ | Would require N manual revokes. |

## 1.3 Authentication & authorization

| # | Item | State | Note |
|---|---|---|---|
| 1.3.1 | Single auth spine for external traffic | ✅ | `ApiKeyAuthFilter` (`@Order(5)`) covers `/api/v1/**` and `/api/external/**`. Well-documented, well-factored. |
| 1.3.2 | Tenant derived from key, never from caller | ✅ | Correct and important. A supplied `tenantCode` may only *match*, never widen. `ApiKeyAuthFilter.java:185-188` |
| 1.3.3 | Scope enforcement | ✅ | `ApiScopes.require(...)` per endpoint; 403 on miss. |
| 1.3.4 | **Merchant-level authorization** | ❌ | **Does not exist.** No `subject_type`, no MID/SID restriction, no row filter below tenant. This is finding #1. |
| 1.3.5 | Scope granularity | ⚠️ | 6 coarse scopes. No separation of PII vs aggregate, no per-bank scope, no write scopes beyond `write:upload` (unused by any v1 endpoint). |
| 1.3.6 | OAuth 2.0 / client credentials | ❌ | Not present. Long-lived static bearer secrets only. |
| 1.3.7 | mTLS / request signing | ❌ | Not present. |
| 1.3.8 | Endpoints declared `permitAll` | ⚠️ | `SecurityConfig.java:63-64` marks `/api/external/**` and `/api/v1/**` `permitAll`; **all** protection rests on `ApiKeyAuthFilter.shouldNotFilter()` matching the URI. Any path-normalization mismatch (`//api/v1/…`, encoded segments, a new controller mounted just outside the prefix) is an unauthenticated data leak. Defence-in-depth is missing. |
| 1.3.9 | Static break-glass key | ⚠️ | Off by default (`external.api.allow-static-key=false`), constant-time compared — but when on it is **all-tenant, no scopes, and explicitly exempt from rate limiting** (`AuthResult.ok(p, Integer.MAX_VALUE)`). Already flagged as M-4 in `SECURITY_AUDIT_2026-08-04.md`. |

## 1.4 Network / transport controls

| # | Item | State | Note |
|---|---|---|---|
| 1.4.1 | IP allowlist | ⚠️ | **Functionally decorative.** Two independent defects: (a) `ipAllowed()` does exact string equality — despite the column comment promising CIDRs, `10.0.0.0/8` never matches anything; (b) `clientIp()` trusts `X-Forwarded-For` unconditionally, so `X-Forwarded-For: <allowed-ip>` bypasses it from anywhere. Confirms H-1 of the prior security audit. Do not describe this to customers as a control until fixed. |
| 1.4.2 | TLS termination | ❌ | Prior audit found nothing in the repo terminating TLS. API keys and transaction payloads traverse the network in cleartext. |
| 1.4.3 | Egress controls to bank DBs | ❌ | No allowlist, no documented VPN/PrivateLink requirement in code or config. |
| 1.4.4 | MSSQL certificate validation | ⚠️ | `trustServerCert` **defaults true** (`IntegrationConnection.java:53`) → the bank JDBC link is MITM-able by default. |

## 1.5 Rate limiting & quotas

| # | Item | State | Note |
|---|---|---|---|
| 1.5.1 | Per-key rate limit | ⚠️ | Works, but in-memory fixed-window (`ApiRateLimiter`), explicitly single-replica-only. Any horizontal scale silently multiplies every limit by the replica count. |
| 1.5.2 | Daily / monthly quota | ❌ | Only per-minute. Nothing stops 120 req/min × 1440 min = 172,800 req/day. |
| 1.5.3 | Burst / concurrency limits | ❌ | Fixed window allows 2× the limit across a bucket boundary. |
| 1.5.4 | Cost-weighted limiting | ❌ | `/transactions` over a 92-day window costs orders of magnitude more than `/finance/summary`; both count as 1. |
| 1.5.5 | Tenant-level aggregate ceiling | ❌ | 50 keys × 120/min with no bank-level cap. |
| 1.5.6 | Result-set protection | ✅ | `MAX_PAGE_SIZE=500`, `MAX_TXN_WINDOW_DAYS=92`. Good. |
| 1.5.7 | Deep pagination | ⚠️ | `LIMIT/OFFSET` on `fact_transaction` (~18B rows per the code comment). Page 10,000 is a table-scan-grade query. Needs keyset/cursor pagination. |

## 1.6 Audit, monitoring, alerting

| # | Item | State | Note |
|---|---|---|---|
| 1.6.1 | Per-request log | ✅ | `api_request_log`: tenant, key, method, endpoint, status, IP, latency. 90-day retention scheduler. |
| 1.6.2 | Log completeness | ⚠️ | No request id, no user-agent, no scope-used, no bytes/rows returned, no query params (so "who exported what" is unanswerable), no failed-auth rows (**rejected requests return before `recordUsage`** — the log contains successes and post-auth errors only, i.e. **credential brute-force is invisible**). |
| 1.6.3 | Admin action audit | ✅ | `AuditService.log(...)` on create/update/revoke. |
| 1.6.4 | Tamper evidence | ❌ | No append-only enforcement, no hash chain. RLS on `api_request_log` is enabled but deliberately **not FORCEd**, and the app connects as table owner — so RLS is advisory here. |
| 1.6.5 | Alerting on API auth failures | ❌ | Alert rules exist for business metrics; **nothing** alerts on 401 spikes, new-IP usage, scope-denial bursts, or off-hours access. |
| 1.6.6 | Bank connection health monitoring | ⚠️ | `last_test_at` / `last_test_status` are stored and `IntegrationRunLog` records failures with retry — but **nothing watches them**. An expired bank DB password produces retries, a FAILED run log, and silence. No email, no alert, no dashboard flag. |

## 1.7 Inbound bank connectivity (Integration Hub)

| # | Item | State | Note |
|---|---|---|---|
| 1.7.1 | Credentials encrypted at rest | ✅ | AES-256-GCM, versioned `enc:v1:` prefix, idempotent re-save. Good design. |
| 1.7.2 | **Encryption key management** | ❌ | **Hardcoded fallback in source.** `CryptoService(@Value("${app.encryption.key:AcquiraDefaultEncryptKey32Chars!!}"))`. No KMS/Secrets Manager integration, no key rotation, no envelope encryption, no per-tenant DEK. |
| 1.7.3 | Least privilege on bank link | ✅ | `conn.setReadOnly(true)`, single-statement assertion, named-param binding, query timeout, `setMaxRows(2M)`. Genuinely careful. |
| 1.7.4 | Bank credential rotation workflow | ❌ | No `rotated_at`, no expiry tracking, no pre-expiry warning, no dual-credential window. |
| 1.7.5 | Bank connection = DB only | ⚠️ | `DbType` is `ORACLE\|POSTGRES\|MSSQL`. **No REST/SFTP/OAuth connector type exists.** Any bank that offers an API instead of DB access cannot be onboarded without new code. |
| 1.7.6 | Per-tenant pull isolation | ✅ | In-JVM `ReentrantLock` per tenant, fail-fast on overlap. Correct for single-replica; breaks on scale-out. |
| 1.7.7 | Upstream readiness gate | ✅ | Precondition SQL + deferred retry. Thoughtful. |
| 1.7.8 | Admin-authored SQL executed against bank DB | ⚠️ | Tenant admins author arbitrary `SELECT` text (`integration_report.sql_text`). Guarded by read-only + single-statement, but this is a **privileged-insider surface**: a tenant admin can read any table the bank service account can see, and exfiltrate it into staging. No column allowlist, no review/approval workflow, no diff audit on `sql_text` changes. |

## 1.8 Data model

| # | Item | State | Note |
|---|---|---|---|
| 1.8.1 | Merchant identity is tenant-local | ❌ | `dim_merchant UNIQUE(tenant_id, internal_id)`. **No cross-bank merchant identity.** Multi-bank is structurally impossible today. |
| 1.8.2 | Merchant as a principal | ❌ | Users are bank staff (`users` + `user_tenant_access`); roles are `ROLE_SUPER_ADMIN/ADMIN/USER/VIEWER`. **No `ROLE_MERCHANT`, no merchant user, no merchant org.** |
| 1.8.3 | API connection metadata | ⚠️ | `api_key` is the only artifact. No application/client entity, no consumer org, no contact/owner, no environment, no lineage. |
| 1.8.4 | PAN handling | ⚠️ **verify** | `card_number VARCHAR(50)` is stored in `stg_trnx_raw`, `fact_transaction` and `sum_monthly_card` (as part of a UNIQUE key), with **no masking or tokenization anywhere in the ingestion code**. If the bank feed supplies full PAN rather than a masked/truncated value, Acquira is in **PCI-DSS scope** and `sum_monthly_card` is a cardholder-data store with a plaintext PAN in a unique index. This must be confirmed with an actual data sample before design proceeds — see Open Question OQ-1. Note `/api/v1/transactions` does **not** return `card_number` (good). |

---

# 2. Scenarios, assumptions and dependencies

## 2.1 The four scenarios that determine the whole design

| | Scenario | Who calls | What they may see | Supported today |
|---|---|---|---|---|
| **S1** | **Bank/acquirer integration** — bank pulls its own portfolio into its own BI | Bank's systems | Everything in that tenant | ✅ this is exactly what exists |
| **S2** | **Single-bank merchant** — merchant reads only its own MIDs at one bank | Merchant's systems | Own MIDs only | ❌ would leak the whole bank |
| **S3** | **Multi-bank merchant** — one merchant, MIDs at Bank A *and* Bank B, one API call | Merchant's systems | Own MIDs across banks | ❌ impossible: no cross-tenant identity |
| **S4** | **Third-party / ISV / PSP** — an accounting platform acting *on behalf of* many merchants | ISV's systems | Union of merchants that consented | ❌ no delegation model at all |

Everything below is driven by S2/S3/S4. If the business only ever needs S1, most of this plan is unnecessary — **confirm this first (OQ-0)**.

## 2.2 Assumptions I am making (challenge these)

- **A1.** "Merchant fetches from multiple banks" means *the merchant calls Acquira once and gets its data across banks* — **not** Acquira proxying live calls to bank APIs. (Backed by code: there is no bank-side API client.)
- **A2.** Acquira remains the system of record for ingested transaction data; latency of hours/days (batch pull cadence) is acceptable to merchants. Merchants will **not** get real-time authorization data.
- **A3.** A bank must **consent** before its merchant's data is exposed to that merchant via Acquira. Acquira is the acquirer's platform; the acquirer owns the customer relationship and the data-sharing decision.
- **A4.** Acquira continues to run single-replica (as `ApiRateLimiter` and `IntegrationPullService` both assume) in the near term, and scale-out is a later, explicit project.
- **A5.** Merchant identity is established by the **bank vouching for it** (bank admin links MIDs to a merchant org), not by merchant self-assertion.

## 2.3 Hard dependencies (blockers for a merchant-facing API)

1. **TLS in front of the API** — non-negotiable before any external merchant traffic. Currently absent.
2. **`app.encryption.key` sourced from a real secret store** — confirm/fix before onboarding another bank.
3. **X-Forwarded-For trust boundary fixed** — otherwise IP allowlist and any IP-based anomaly detection are theatre.
4. **A merchant identity model** (§4) — nothing merchant-facing can ship without it.
5. **PAN scope determination** (OQ-1) — changes the compliance regime for the entire platform.

---

# 3. Architecture options compared

## Option A — "Merchant holds per-bank credentials; Acquira proxies"

Merchant registers each bank's credentials in Acquira; Acquira calls each bank live per request.

| Pros | Cons |
|---|---|
| Real-time data; Acquira stores less | Requires every bank to expose a per-merchant API — **none do here**; Acquira would custody merchant↔bank credentials (highest-value secret store in the system); latency = slowest bank; a bank outage becomes a merchant-visible outage; N bank-specific auth adapters (OAuth/mTLS/HMAC) to build and maintain; open-banking-style regulatory exposure |

**Verdict: reject.** It does not match a single fact about how this platform gets data.

## Option B — "Aggregate-then-serve" (hub model) — **RECOMMENDED**

Acquira keeps ingesting from banks on its existing schedule and serves merchants from its own store, with merchant-scoped authorization applied at query time.

| Pros | Cons |
|---|---|
| Zero change to the bank-side integration that already works; one auth model to secure instead of N; consistent schema across banks (already normalized in staging); fast, predictable, cacheable responses; a bank outage does not break merchant reads; multi-bank is a *join*, not a federation | Data is as fresh as the last pull (A2); Acquira holds all the data, so a breach is broader — mitigated by scoping, quotas and audit; requires the merchant-identity model that doesn't exist yet |

## Option C — Hybrid: hub for history, pass-through for real-time

Option B, plus per-bank live connectors added only where a bank actually exposes an API and a real-time use case exists.

**Verdict:** the right *destination*, but only after B is live. Do not build connector abstractions for a bank API nobody has offered you yet.

## Option D — Per-bank keys for the merchant (merchant holds N Acquira keys, one per bank)

| Pros | Cons |
|---|---|
| Trivial to build on the existing tenant-bound key — no new identity model; naturally enforces bank-level consent; simplest revocation story (bank revokes its own key) | Merchant integrates N times and aggregates client-side; no cross-bank endpoints ever; key sprawl grows as N×merchants; **still needs merchant-level row scoping** (finding #1) or each key leaks the whole bank |

**Verdict:** viable **transitional step** — it is Phase 1 below. Not the destination.

## Recommendation

**Option B, reached via Option D.**

1. First make a tenant-bound key able to be *narrowed to a merchant* (`subject_type=MERCHANT` + MID grants). This alone converts today's system from "cannot be given to a merchant" to "safe for a single-bank merchant" — S2 solved, small blast radius, no new identity model.
2. Then add the cross-tenant `merchant_org` identity so one key spans banks — S3 solved.
3. Then add OAuth 2.0 client-credentials + delegation for ISVs — S4 solved.

## Authentication mechanism comparison

| Mechanism | Fit for Acquira | Verdict |
|---|---|---|
| **Static API key** (today) | Simple, universally understood, works for server-to-server. Weaknesses: long-lived, bearer (replayable), no audience binding, painful rotation | **Keep as the baseline** for banks and small merchants. Fix rotation and lifecycle. |
| **OAuth 2.0 client credentials** (`client_id`/`client_secret` → short-lived JWT) | Short-lived tokens shrink the theft window; scopes and merchant claims travel in the token, so the hot path verifies a signature instead of BCrypt (fixes 1.1.4); standard tooling | **Adopt for the merchant/ISV tier.** Highest value-per-effort after merchant scoping. |
| **OAuth 2.0 authorization code** | Only needed when a *human merchant* consents to a *third party* (S4) | **Phase 4**, and only if S4 is real. |
| **mTLS** | Strong, non-replayable, standard for bank↔bank links | **Offer to large/bank consumers as an option**, not a default — cert lifecycle is real operational cost. |
| **HMAC request signing** | Prevents replay and body tampering even without TLS | **Recommend for `write:` endpoints only.** Not worth it for reads once TLS exists. |
| **JWT bearer, long-lived** | — | **Reject.** All the downsides of API keys plus non-revocability. |

---

# 4. Proposed target architecture

## 4.1 Principal model

Three principal types, one auth spine:

```
                     ┌───────────────────────────────────────┐
                     │  api_credential  (one table, typed)   │
                     ├───────────────────────────────────────┤
   subject_type =    │  TENANT   → whole bank (today's key)  │
                     │  MERCHANT → one merchant org          │
                     │  PARTNER  → ISV acting for many       │
                     └───────────────────────────────────────┘
                                     │
              ┌──────────────────────┼──────────────────────┐
              ▼                      ▼                      ▼
      tenant_id (bank)        merchant_org_id        partner_org_id
                                     │                      │
                              merchant_org_mid ◄──── delegation grants
                              (org × tenant × MID)
```

**`merchant_org` is the missing keystone.** It is a *global* identity (no `tenant_id`) that owns a set of `(tenant_id, mid)` claims. A merchant banking with Bank A and Bank B has one org and two sets of MID claims. Every merchant-scoped query resolves the caller to a set of `(tenant_id, merchant_id)` pairs and filters on it — which also makes multi-bank a normal `IN (...)` predicate rather than a special case.

## 4.2 Authorization pipeline (replaces today's tenant-only push)

```
request
  │
  ├─ 1. Resolve credential (HMAC-SHA256 lookup hash → constant-time compare)
  ├─ 2. Check active / not expired / not revoked
  ├─ 3. Check environment matches the route (sandbox key → sandbox data only)
  ├─ 4. Check source IP (real client IP, CIDR-aware, trusted-proxy-hop aware)
  ├─ 5. Rate limit + quota (per-minute AND per-day; cost-weighted)
  ├─ 6. Build an AccessScope:
  │        TENANT   → { tenants:[T],  merchants: ALL }
  │        MERCHANT → { tenants:[T1,T2], merchants:[resolved merchant_ids] }
  │        PARTNER  → { … from active delegation grants only }
  ├─ 7. Scope check (read:transactions, …)
  ├─ 8. Controller queries with a MANDATORY AccessScope predicate
  └─ 9. Log request (always — including rejections) with request_id
```

Step 8 is the load-bearing one and must not be optional. Enforce it structurally: a repository helper that *requires* an `AccessScope` argument, so a new endpoint physically cannot forget the filter. Back it with Postgres RLS (`FORCE ROW LEVEL SECURITY`) on `fact_transaction` / `dim_merchant` / `sum_daily_merchant` as the second layer — the prior audit already notes RLS is currently only on peripheral tables.

## 4.3 Key scoping decision — per what?

| Axis | Decision | Reasoning |
|---|---|---|
| **Per merchant?** | **Yes** — the credential names a subject | Without this, a merchant key is a bank-wide breach. Non-negotiable. |
| **Per bank?** | **No, not as the boundary** — bank access is an *attribute set* (`merchant_org_mid` claims) on a merchant credential | Per-bank keys force merchants to integrate N times and make S3 impossible. Bank consent is preserved because each claim is bank-approved and bank-revocable. |
| **Per application?** | **Yes** — one credential per client application | Blast radius, independent rotation, meaningful attribution in logs ("which of your systems is doing this?"). This is what `name` gestures at today; formalize it as an `api_client` row. |
| **Per environment?** | **Yes, hard-separated** — `environment` column + separate base path (`/sandbox/v1` or a sandbox host) | Prevents the classic incident: a sandbox key pasted into prod config, or vice versa. Must be *structural*, not a naming convention. |

**Net: a credential is `(client_application × environment)`, owned by a subject (tenant \| merchant_org \| partner_org).**

## 4.4 Data model

```sql
-- Global merchant identity (NO tenant_id — this is the point)
CREATE TABLE merchant_org (
    merchant_org_id   BIGSERIAL PRIMARY KEY,
    legal_name        VARCHAR(200) NOT NULL,
    external_ref      VARCHAR(100),          -- CR / trade licence / group id
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING|ACTIVE|SUSPENDED
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_by        VARCHAR(100)
);

-- Which MIDs, at which bank, this org may see. THE consent record.
CREATE TABLE merchant_org_mid (
    id                BIGSERIAL PRIMARY KEY,
    merchant_org_id   BIGINT NOT NULL REFERENCES merchant_org(merchant_org_id) ON DELETE CASCADE,
    tenant_id         INT    NOT NULL REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    merchant_id       BIGINT NOT NULL,       -- dim_merchant(merchant_id) within that tenant
    mid               VARCHAR(50) NOT NULL,  -- denormalized for audit readability
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING|APPROVED|REVOKED
    approved_by       VARCHAR(100),          -- MUST be a user of tenant_id (bank consent)
    approved_at       TIMESTAMP,
    revoked_at        TIMESTAMP,
    revoked_by        VARCHAR(100),
    UNIQUE (merchant_org_id, tenant_id, merchant_id)
);
CREATE INDEX idx_mom_org_status ON merchant_org_mid(merchant_org_id, status);

-- The client application a credential belongs to
CREATE TABLE api_client (
    client_id         BIGSERIAL PRIMARY KEY,
    subject_type      VARCHAR(16) NOT NULL,  -- TENANT | MERCHANT | PARTNER
    tenant_id         INT    REFERENCES tenant(tenant_id) ON DELETE CASCADE,
    merchant_org_id   BIGINT REFERENCES merchant_org(merchant_org_id) ON DELETE CASCADE,
    partner_org_id    BIGINT,
    name              VARCHAR(200) NOT NULL,
    owner_email       VARCHAR(200) NOT NULL, -- who to notify on expiry/incident
    status            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_subject CHECK (
        (subject_type='TENANT'   AND tenant_id IS NOT NULL AND merchant_org_id IS NULL) OR
        (subject_type='MERCHANT' AND merchant_org_id IS NOT NULL) OR
        (subject_type='PARTNER'  AND partner_org_id IS NOT NULL))
);

-- Extend the EXISTING api_key rather than replacing it (migration safety)
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS client_id        BIGINT REFERENCES api_client(client_id);
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS environment      VARCHAR(12) NOT NULL DEFAULT 'PRODUCTION'; -- SANDBOX|PRODUCTION
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS lookup_hash      CHAR(64);   -- HMAC-SHA256(pepper, key) — replaces BCrypt on the hot path
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS rotated_from     BIGINT REFERENCES api_key(key_id);
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS grace_expires_at TIMESTAMP;  -- old key still valid during rotation overlap
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS daily_quota      INT;
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS scopes           JSONB;      -- replaces the concatenated TEXT
ALTER TABLE api_key ADD COLUMN IF NOT EXISTS last_rotated_at  TIMESTAMP;
CREATE UNIQUE INDEX IF NOT EXISTS idx_api_key_lookup ON api_key(lookup_hash) WHERE is_active;

-- Delegation for ISVs (S4)
CREATE TABLE api_delegation_grant (
    grant_id         BIGSERIAL PRIMARY KEY,
    partner_org_id   BIGINT NOT NULL,
    merchant_org_id  BIGINT NOT NULL REFERENCES merchant_org(merchant_org_id) ON DELETE CASCADE,
    scopes           JSONB NOT NULL,
    granted_by       VARCHAR(100) NOT NULL,
    granted_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at       TIMESTAMP,
    revoked_at       TIMESTAMP,
    UNIQUE (partner_org_id, merchant_org_id)
);

-- Bank connection credential lifecycle (inbound side)
ALTER TABLE integration_connection ADD COLUMN IF NOT EXISTS credential_expires_at TIMESTAMP;
ALTER TABLE integration_connection ADD COLUMN IF NOT EXISTS last_rotated_at       TIMESTAMP;
ALTER TABLE integration_connection ADD COLUMN IF NOT EXISTS secret_ref            VARCHAR(300); -- KMS/Secrets Manager ARN, replaces inline ciphertext
ALTER TABLE integration_connection ADD COLUMN IF NOT EXISTS health_status         VARCHAR(20);  -- HEALTHY|DEGRADED|FAILING
ALTER TABLE integration_connection ADD COLUMN IF NOT EXISTS consecutive_failures  INT DEFAULT 0;

-- Enrich the request log
ALTER TABLE api_request_log ADD COLUMN IF NOT EXISTS request_id   UUID;
ALTER TABLE api_request_log ADD COLUMN IF NOT EXISTS client_id    BIGINT;
ALTER TABLE api_request_log ADD COLUMN IF NOT EXISTS environment  VARCHAR(12);
ALTER TABLE api_request_log ADD COLUMN IF NOT EXISTS scope_used   VARCHAR(64);
ALTER TABLE api_request_log ADD COLUMN IF NOT EXISTS rows_returned INT;
ALTER TABLE api_request_log ADD COLUMN IF NOT EXISTS auth_outcome VARCHAR(24); -- OK|BAD_KEY|EXPIRED|IP_DENIED|SCOPE_DENIED|RATE_LIMITED
ALTER TABLE api_request_log ADD COLUMN IF NOT EXISTS user_agent   VARCHAR(200);
```

**Why `lookup_hash` replaces BCrypt on the hot path:** an API key is a 122-bit random secret, not a human password — it needs no work factor, only a keyed one-way lookup. `HMAC-SHA256(server_pepper, key)` gives an O(1) unique-index hit and a constant-time compare in microseconds instead of ~50–100 ms. Keep the BCrypt `key_hash` column populated during migration and verify both until every key is backfilled, then drop the BCrypt path.

## 4.5 Secret storage

| Secret | Today | Target |
|---|---|---|
| Merchant/bank API keys | BCrypt hash in `api_key` | HMAC lookup hash + pepper from KMS. Plaintext never stored. |
| Bank DB passwords | AES-GCM in `integration_connection`, **key possibly the hardcoded default** | AWS Secrets Manager (this branch is `deploy/kubernetes-aws`); DB stores only `secret_ref`. Envelope encryption if staying in-DB. |
| `app.encryption.key`, JWT secret | Properties / defaults | KMS-backed, injected, never defaulted. Extend `SecurityStartupGuard` to **refuse to start** in non-dev if either equals its default. |
| Client secrets (OAuth phase) | n/a | Hashed like API keys; shown once. |

---

# 5. API / key management flow

## 5.1 Issuance

```
Bank admin (or merchant admin, for own org)
  └─ POST /api/admin/api-clients            → create application record (name, owner_email, subject)
       └─ POST /api/admin/api-clients/{id}/keys?environment=SANDBOX
            → server generates  aqr_sbx_<base62(160 bits)>
            → stores lookup_hash = HMAC(pepper, key), scopes JSONB, expires_at (default now + 90d)
            → returns plaintext ONCE + a "rotate before <date>" notice
       └─ integrator tests against sandbox
       └─ POST .../keys?environment=PRODUCTION   (requires: sandbox key used ≥1×, owner_email verified)
```

Key format carries its environment so a mispasted key fails loudly rather than silently reading prod:
`aqr_live_…` / `aqr_sbx_…`.

## 5.2 Rotation (the flow that is entirely missing today)

```
POST /api/admin/api-keys/{id}/rotate  { "graceHours": 72 }
  ├─ issue NEW key, rotated_from = {id}, same client/scopes/limits
  ├─ set OLD key grace_expires_at = now + 72h   (old key STILL WORKS)
  ├─ return the new plaintext once
  ├─ notify owner_email
  └─ scheduler: when grace expires → deactivate old key, notify, and if the old key
     was still receiving traffic in the final hour, ALERT (integrator did not switch)
```

This is the difference between a rotation and an outage. Add a scheduled job that emails owners at T-30/T-7/T-1 days before `expires_at`, and auto-rotates keys that hit a max-lifetime policy.

## 5.3 Revocation

- **Immediate:** `DELETE /{id}` (exists) — extend to evict any cached credential and write an `auth_outcome=REVOKED` log line on next use.
- **Bulk:** `POST /api/admin/api-clients/{id}/revoke-all` — kill an application.
- **Emergency:** `POST /api/admin/api-keys/panic?tenantId=` (SUPER_ADMIN, audited) — kill every key for a bank.
- **Cascade:** revoking a `merchant_org_mid` claim must immediately shrink the AccessScope of every credential of that org — no key change needed. This is why scope is resolved per-request from claims, not baked into the key.

## 5.4 Error contract

Consistent, non-leaky, actionable:

| HTTP | `code` | When |
|---|---|---|
| 401 | `missing_credentials` | no `X-API-Key` / `Authorization` |
| 401 | `invalid_credentials` | unknown or revoked key (**never** distinguish these two) |
| 401 | `credential_expired` | past `expires_at` — include `expiredAt` so integrators can self-diagnose |
| 403 | `insufficient_scope` | key lacks the scope — name the required scope (safe) |
| 403 | `merchant_not_authorized` | requested MID not in the caller's claims |
| 403 | `ip_not_allowed` | source IP outside allowlist |
| 403 | `environment_mismatch` | sandbox key on a production route |
| 429 | `rate_limited` / `quota_exceeded` | with `Retry-After`, `X-RateLimit-*` |
| 503 | `upstream_data_unavailable` | last bank pull failed → **say so** rather than silently returning an empty/partial series |

**503 with a `staleness` hint matters more than it looks.** Today, if a bank pull fails, `/api/v1/analytics/volume` returns a shorter series with no indication that data is missing. A merchant reconciling settlements would treat missing days as zero-volume days. Every response should carry `dataAsOf` per bank.

---

# 6. Merchant & bank connection flow

## 6.1 Bank (tenant) onboarding — inbound

```
1. SUPER_ADMIN creates tenant (bank)
2. Bank provides a READ-ONLY DB account, scoped to the reporting views only
3. Admin creates integration_connection
     - secret goes to Secrets Manager; DB holds secret_ref
     - trustServerCert MUST be false for production MSSQL links (default flipped)
     - records credential_expires_at from the bank's password policy
4. Test connection (exists) → health_status = HEALTHY
5. Author integration_report SQL   ← REQUIRES review/approval (see 7.2)
6. Validate query (LIMIT 5 preview — exists)
7. Create schedule (+ optional precondition gate — exists)
8. First pull → run log → data lands
9. Connection health monitor starts watching: 2 consecutive failures → DEGRADED + alert;
   5 → FAILING + page + auto-pause schedule (stop hammering a locked account)
```

## 6.2 Merchant onboarding — outbound

```
1. Bank admin creates (or searches) a merchant_org
2. Bank admin selects MIDs from ITS OWN dim_merchant → merchant_org_mid rows, status=APPROVED
   (only a user of that tenant may approve claims for that tenant — this IS the consent gate)
3. Merchant admin invited by email → verifies → gains access to a merchant-scoped console
4. Merchant creates an api_client (their application) → SANDBOX key
5. Merchant integrates against sandbox (synthetic data, own MID shapes)
6. Merchant requests PRODUCTION key → bank admin approves → key issued
7. Merchant calls /api/v1/... → AccessScope resolved from claims → sees ONLY its MIDs
```

## 6.3 Multi-bank

```
Merchant "Alpha Retail"  (merchant_org_id = 42)
  ├─ Bank A (tenant 1): MID 1001, 1002   [APPROVED by Bank A admin]
  └─ Bank B (tenant 7): MID 55501        [APPROVED by Bank B admin]

GET /api/v1/transactions?startDate=…&endDate=…
  → AccessScope = {(1,1001),(1,1002),(7,55501)}
  → WHERE (f.tenant_id, f.merchant_id) IN (…)
  → rows carry bankCode so the merchant can attribute them
  → response: { dataAsOf: { "BANKA": "2026-08-08", "BANKB": "2026-08-06" }, … }

Bank B withdraws consent → claim REVOKED → next request returns only Bank A rows.
No key rotation, no merchant action, immediate effect.
```

**Cross-tenant querying breaks two current invariants** and must be handled explicitly:
- `TenantContext` holds a single `Long`. A multi-bank request has *no single tenant*. Either loop per-tenant and merge (safer, keeps RLS intact — **recommended**), or widen the context to a set (touches RLS's `get_current_tenant()`, higher risk).
- Currency: banks have different `base_currency`. **Do not sum across banks** unless converted; return per-bank subtotals and let the merchant aggregate, or add an explicit FX policy. Silently adding BHD to AED is a correctness bug that looks like a feature.

---

# 7. Security checklist

## 7.1 Must fix before ANY external merchant traffic (P0)

- [ ] **Confirm `app.encryption.key` is not the hardcoded default in production.** If it is: rotate the property, re-encrypt every `integration_connection.encrypted_password`, and treat every bank DB credential as compromised (bank-side password rotation).
- [ ] **TLS termination** in front of the API. Reject plaintext.
- [ ] **Fix `clientIp()`** — parse `X-Forwarded-For` only from a configured trusted-proxy hop count; otherwise use `getRemoteAddr()`. Until then, `allowed_ips` is not a control.
- [ ] **Merchant-scoped authorization** (`AccessScope`) — without it, no merchant key may be issued. Ever.
- [ ] **Disable the static break-glass key** in production (`external.api.allow-static-key=false`) and assert it in `SecurityStartupGuard`.
- [ ] **Log rejected requests.** Move `recordUsage` so 401/403/429 are recorded with `auth_outcome`. Brute-force is currently invisible.
- [ ] **Resolve the PAN question (OQ-1)** and mask/tokenize if full PANs are present.

## 7.2 High (P1)

- [ ] Replace `permitAll` on `/api/v1/**` and `/api/external/**` with an authenticated matcher, so a filter-path mismatch fails closed instead of open.
- [ ] `scopes` → `JSONB`, validated against a server-side enum on write. Reject unknown scopes with 400 instead of silently storing them.
- [ ] CIDR support in the IP allowlist (the column comment already promises it).
- [ ] Default `expires_at` (90 days) + max-lifetime policy + rotation endpoint + expiry notifications.
- [ ] Daily quotas alongside per-minute limits; cost-weight `/transactions`.
- [ ] `FORCE ROW LEVEL SECURITY` on `fact_transaction`, `dim_merchant`, `sum_daily_merchant`, with a documented exemption role for the batch/maintenance jobs.
- [ ] MSSQL `trustServerCert` default → `false`; require an explicit opt-out with a written justification.
- [ ] **Approval workflow for `integration_report.sql_text`.** A tenant admin authoring arbitrary SELECT against a bank's production DB should require a second approver and a diff audit entry.
- [ ] Alerting: 401 spike per IP/key, first-use-from-new-IP, scope-denial burst, off-hours bulk export, `rows_returned` anomaly, bank connection FAILING.

## 7.3 Medium (P2)

- [ ] `lookup_hash` (HMAC) replacing BCrypt on the hot path; keep BCrypt only for migration.
- [ ] Keyset pagination on `/transactions`.
- [ ] Request-id propagation (`X-Request-Id`) into `api_request_log` and application logs.
- [ ] Append-only / hash-chained audit for key-lifecycle events.
- [ ] Distributed rate limiting (Redis) — a prerequisite for ever running >1 replica.
- [ ] Sandbox environment with synthetic data.

## 7.4 Attack scenarios to design against

| # | Scenario | Current outcome | Control needed |
|---|---|---|---|
| AT-1 | Merchant key leaks (committed to Git, CI log) | Whole bank's data exfiltrated at 120 req/min | Merchant scoping; daily quota; new-IP alert; fast rotation; secret-scanning guidance in docs |
| AT-2 | Attacker forges `X-Forwarded-For` to defeat the IP allowlist | **Succeeds** | Trusted-proxy parsing |
| AT-3 | Credential stuffing / prefix brute force | **Invisible** (rejections unlogged) + each attempt costs a BCrypt | Log rejections; per-IP auth-failure limiter *before* the hash; HMAC lookup |
| AT-4 | Malicious/compromised bank admin authors an exfiltrating report SQL | Succeeds within the read-only account's reach | SQL approval workflow; column allowlist; alert on `sql_text` change |
| AT-5 | Merchant enumerates other MIDs via `/merchants/{mid}/summary` | **Succeeds today** — resolves any MID in the tenant | AccessScope on every lookup; 404 (not 403) for out-of-scope MIDs to avoid an existence oracle |
| AT-6 | Sandbox key used against production | No such distinction exists | `environment` binding + distinct key prefix |
| AT-7 | Insider dumps `api_key` table | Only hashes — **contained** ✅ | Keep it that way; never add a plaintext or reversible column |
| AT-8 | Multi-bank correlation: merchant infers a rival's volume from bank-level aggregates | `/analytics/volume` and `/finance/summary` are **bank-wide** | Merchant credentials must be blocked from bank-aggregate endpoints entirely, or served merchant-scoped variants |
| AT-9 | Replay of a captured request | Succeeds (bearer, no TLS) | TLS; HMAC signing for writes |
| AT-10 | Rate-limit evasion by spreading across many keys of one org | Succeeds (no org-level ceiling) | Aggregate quota at client/org level |

> **AT-8 deserves emphasis.** `/api/v1/analytics/volume`, `/analytics/scheme-breakdown` and `/finance/summary` read `sum_daily_bank` / `sum_daily_insight` — whole-bank aggregates including net revenue. These endpoints must be **structurally unreachable** by a `MERCHANT` credential, not merely scope-gated, or a merchant learns its acquirer's total book.

---

# 8. Compliance considerations

| Area | Implication | Action |
|---|---|---|
| **PCI-DSS** | If `card_number` holds full PAN (OQ-1), Acquira is in scope: encryption at rest, key management, quarterly scans, restricted access, 12-month log retention. `sum_monthly_card` puts PAN in a UNIQUE index. | Determine first. Prefer truncation (first6+last4) or tokenization at ingest — `sum_monthly_card`'s uniqueness works equally well on a token/hash. |
| **PSD2 / open banking** | Only bites if Acquira intermediates *payment* data on behalf of account holders. Serving an acquirer's own settlement data to that acquirer's merchants generally does not. Multi-bank aggregation for merchants moves closer to the line. | Legal review before S3/S4 ships. |
| **CBB (Bahrain) / regional regulators** | The user domain is Bahraini fintech (AFS). Outsourcing, data-residency and cloud-hosting rules apply to bank data leaving a bank's estate. | Confirm data-residency constraints for the AWS deployment; confirm each bank's outsourcing approval covers merchant-facing exposure. |
| **GDPR / local DP law** | Merchant contacts (`primary_contact_email/number`) and cardholder-derived data are personal data. `/api/v1/merchants` returns contact-adjacent fields. | Data-minimize the API response; document lawful basis; define retention beyond the 90-day request log. |
| **Log retention** | 90 days (`api.request-log.retention-days`) is **below** the 12 months PCI expects (3 months hot) and below typical financial audit expectations. | Raise to 12–24 months, archive cold to S3. |
| **Right to erasure vs immutability** | Deleting a merchant must not destroy the audit trail. | Tombstone merchant PII; keep credential/audit events. |
| **Data-sharing agreements** | `merchant_org_mid.approved_by` is the machine-readable evidence that a bank consented. | Make approval a first-class, non-bypassable, audited action. |

---

# 9. Required backend changes

Grouped by phase (see §11). Existing files named where the change lands.

### Foundation
1. `AccessScope` value object + resolver (new, `acquira-common/security`).
2. `ApiKeyAuthFilter` — build `AccessScope`; log rejections; environment check; HMAC lookup; trusted-proxy IP parsing; CIDR matching.
3. `ApiKeyPrincipal` — carry `subjectType`, `merchantOrgId`, `environment`, `clientId`, resolved merchant ids. *(Note: two copies exist — `acquira-common/security` and `acquira-core/config`. Delete the duplicate before extending it.)*
4. `ExternalDataApiController` / `ExternalReportApiController` — every query takes `AccessScope`; bank-aggregate endpoints reject `MERCHANT` subjects.
5. Migration `V2026_08_xx__api_identity_foundation.sql` — §4.4 DDL.

### Key management
6. `ApiKeyController` — `/rotate`, `/panic`, scope validation, JSONB scopes, default expiry, client association.
7. New `ApiClientController` — application CRUD.
8. New `ApiKeyLifecycleScheduler` — expiry notifications (T-30/7/1), grace-window closure, idle-key auto-disable, unswitched-rotation alert.
9. `ApiRateLimiter` — daily quota, cost weighting, org-level ceiling; Redis-backed interface for future scale-out.

### Merchant identity
10. New `MerchantOrgService` + `MerchantOrgController` — org CRUD, MID claim request/approve/revoke, invitations.
11. New `MerchantAccessResolver` — claims → `(tenant_id, merchant_id)` set, cached with explicit invalidation on claim change.
12. Multi-tenant query strategy — per-tenant loop + merge (keeps `TenantContext` single-valued and RLS intact).
13. Per-bank `dataAsOf` from the latest successful `IntegrationRunLog` per tenant, on every response.

### Inbound hardening
14. `CryptoService` — remove the default key; fail fast outside dev; add Secrets Manager provider.
15. `SecurityStartupGuard` — assert encryption key, JWT secret, static-key flag, TLS expectation.
16. `IntegrationConnection` — `trustServerCert` default false; `secret_ref`; credential expiry; health fields.
17. New `IntegrationHealthMonitor` — consecutive-failure tracking, DEGRADED/FAILING transitions, alerts, auto-pause.
18. `sql_text` approval workflow + change audit on `IntegrationReport`.

### Observability
19. Enriched `api_request_log` writes; `X-Request-Id` propagation.
20. New `ApiSecurityAlertService` — auth-failure spikes, new-IP first use, export anomalies, quota breaches.
21. Retention → 12–24 months + S3 archive.

---

# 10. Required dashboard / admin changes

### Bank admin (extends `ApiManagement.jsx`, `IntegrationHub.jsx`)
- **Applications** tab — clients grouped by subject, owner email, environment, key count, last activity.
- Key card: environment badge, expiry countdown with colour states, **Rotate** (with grace-period picker), usage sparkline, "last used from" IP.
- **Merchant Access** tab (new) — pending MID-claim requests with **Approve / Reject**; approved claims with revoke; searchable against `dim_merchant`. *This screen is the consent gate; it must be unmistakable about what approval means.*
- **API Security** tab (new) — auth failures over time, top failing IPs, new-IP-first-use feed, quota breaches, scope denials.
- **Connection health** on the Integration Hub — HEALTHY/DEGRADED/FAILING, consecutive failures, credential expiry countdown, last successful pull per report.
- Scope picker rebuilt from a **server-provided** scope catalogue (today the UI list and `ApiScopes` are two hand-maintained lists that can drift).

### Merchant console (new surface)
- Bank connections: which banks, which MIDs, status, `dataAsOf`.
- Own applications and keys: create sandbox, request production, rotate, revoke.
- Own usage and quota consumption.
- Interactive API docs scoped to the merchant's own grants.

### Super-admin
- Cross-tenant credential inventory; org-level kill switch; audit search across key lifecycle events; policy config (max key lifetime, default quotas, rotation grace).

---

# 11. Phased implementation plan

Each phase ends in a shippable, independently valuable state.

### Phase 0 — Stop the bleeding *(~1 week, do regardless of everything else)*
Verify/fix the encryption key; TLS; fix `clientIp()`; log rejected requests; assert the static key is off; raise log retention; resolve OQ-1.
**Exit:** the existing bank-facing API is defensible. No new features.

### Phase 1 — Merchant scoping on today's key *(~2–3 weeks)*
`AccessScope`; `subject_type` + merchant claims; every `/api/v1` query scope-filtered; bank-aggregate endpoints blocked for merchant subjects; admin approval screen; 404-not-403 for out-of-scope MIDs.
**Exit:** **S2 works** — a single-bank merchant can safely hold a key. This is the phase that unblocks the actual business ask.

### Phase 2 — Key lifecycle maturity *(~2 weeks)*
`api_client`; environments + prefixed keys; rotation with grace; default expiry + notifications; JSONB validated scopes; daily quotas; HMAC lookup hash; enriched logging; security alerting.
**Exit:** keys can be operated for years without an outage or a blind spot.

### Phase 3 — Multi-bank *(~3–4 weeks)*
`merchant_org` across tenants; per-bank claim approval; cross-tenant query merge; per-bank `dataAsOf`; currency policy; merchant console.
**Exit:** **S3 works.**

### Phase 4 — OAuth 2.0 & partners *(~4 weeks, only if S4 is real)*
Client credentials + short-lived JWTs; delegation grants; authorization-code consent; optional mTLS.
**Exit:** **S4 works.**

### Phase 5 — Scale & resilience *(as needed)*
Redis rate limiting; multi-replica (retires the in-JVM pull lock and in-memory limiter); read replicas; caching; webhooks/push as an alternative to polling.

---

# 12. Risks and open questions

## Blocking — answer before development starts

| id | Question | Why it blocks |
|---|---|---|
| **OQ-0** | **Is a merchant-facing API actually a committed requirement, or is the consumer always the bank?** | If banks are the only consumers, Phases 1/3/4 are unnecessary and the work reduces to Phases 0 and 2. This single answer changes the plan by ~80%. |
| **OQ-1** | **Does `card_number` contain full PAN or a masked value?** Check real rows in `stg_trnx_raw` / `fact_transaction`. | Determines whether the platform is in PCI-DSS scope. Changes the schema, the hosting requirements and the audit regime. |
| **OQ-2** | **Is `app.encryption.key` set in production?** | If not, every bank DB credential must be treated as compromised today. |
| **OQ-3** | **Who owns the merchant relationship — Acquira or each bank?** | Decides whether Acquira may onboard a merchant directly or only via bank invitation, and who signs the data-sharing agreement. |
| **OQ-4** | **Will a bank permit its merchant's data to be co-presented with a competitor bank's data in one response?** | If any bank objects, S3 collapses to per-bank keys (Option D permanently). Ask the banks before building. |

## Important — answer before the relevant phase

| id | Question |
|---|---|
| OQ-5 | Data freshness SLA merchants will be promised? Current cadence is batch (see `integration_schedule`), and merchants reconciling settlements care a lot. |
| OQ-6 | FX policy for cross-bank aggregation — per-bank subtotals only, or a converted total with a stated rate source? |
| OQ-7 | Does `dim_merchant` have a reliable identifier to match the same legal entity across banks (VAT/CR number)? `stg_merchant_master_raw` carries `vat_number` but `dim_merchant` does not. Without one, claim linking is manual forever. |
| OQ-8 | Is Acquira committing to single-replica? `ApiRateLimiter` and `IntegrationPullService` both hard-depend on it; it is also a single point of failure and caps API throughput. |
| OQ-9 | Sandbox data strategy — synthetic generation, or anonymized production? (Anonymized production data is a compliance decision, not a technical one.) |
| OQ-10 | Will merchants poll, or should Acquira push (webhooks/S3 drops) once daily data lands? Push is cheaper and gives a natural freshness signal. |
| OQ-11 | Commercial model — is API access metered/billed? If yes, `api_request_log` becomes billing-grade and needs guaranteed (not best-effort) writes; today `recordUsage` swallows its own exceptions. |

## Risks

| id | Risk | Mitigation |
|---|---|---|
| R-1 | Merchant scoping is missed on one endpoint → cross-merchant leak | Structural enforcement (`AccessScope` as a required argument), RLS as a second layer, an automated test that asserts *every* `/api/v1` route rejects an out-of-scope MID |
| R-2 | BCrypt-per-request becomes the throughput ceiling under merchant traffic | HMAC lookup in Phase 2; load-test before onboarding merchants |
| R-3 | Cross-tenant queries subvert `TenantContext`/RLS | Per-tenant loop + merge; never widen `get_current_tenant()` |
| R-4 | Rotation without a grace window causes integrator outages | Grace window + T-30/7/1 notices + "old key still in use" alert |
| R-5 | In-memory rate limiter silently multiplies limits on scale-out | Redis before any replica count > 1; add a startup assertion tying replica count to limiter type |
| R-6 | Bank withdraws consent mid-flight; cached scopes keep serving | Resolve claims per request or cache with explicit invalidation on claim change |
| R-7 | Merchants treat missing days (failed pull) as zero volume | `dataAsOf` per bank on every response; 503 with `upstream_data_unavailable` rather than silent gaps |
| R-8 | Scope taxonomy drifts between UI list and `ApiScopes` | Serve the catalogue from the server; delete the duplicated frontend list |
| R-9 | Duplicate `ApiKeyPrincipal` classes diverge and one path loses a check | Delete `acquira-core/config/ApiKeyPrincipal.java`; keep the `acquira-common/security` one |
| R-10 | Phase 1 ships without Phase 0 and merchant traffic crosses plaintext HTTP | Gate Phase 1 delivery on Phase 0 completion — non-negotiable |

---

## Appendix — key files

| Concern | File |
|---|---|
| API-key auth spine | `acquira-core/src/main/java/com/acquira/core/config/ApiKeyAuthFilter.java` |
| Key admin CRUD | `acquira-core/src/main/java/com/acquira/core/controller/ApiKeyController.java` |
| Scope helper | `acquira-common/src/main/java/com/acquira/common/security/ApiScopes.java` |
| Principal (duplicated) | `acquira-common/.../security/ApiKeyPrincipal.java`, `acquira-core/.../config/ApiKeyPrincipal.java` |
| Rate limiter | `acquira-core/src/main/java/com/acquira/core/config/ApiRateLimiter.java` |
| Public data API | `acquira-core/src/main/java/com/acquira/core/controller/ExternalDataApiController.java` |
| Public report API | `acquira-pdf/src/main/java/com/acquira/pdf/controller/ExternalReportApiController.java` |
| Bank connection model | `acquira-common/src/main/java/com/acquira/common/model/IntegrationConnection.java` |
| Bank pull engine | `acquira-batch/src/main/java/com/acquira/batch/service/IntegrationPullService.java` |
| Integration admin API | `acquira-batch/src/main/java/com/acquira/batch/controller/IntegrationController.java` |
| Secret encryption | `acquira-common/src/main/java/com/acquira/common/service/CryptoService.java` |
| Security filter chain | `acquira-common/src/main/java/com/acquira/common/config/SecurityConfig.java` |
| API schema | `acquira-core/src/main/resources/db/migration/V2026_07_04_01__api_management_foundation.sql` |
| Admin UI | `frontend/src/pages/admin/ApiManagement.jsx`, `frontend/src/pages/admin/IntegrationHub.jsx` |
| Prior security audit | `docs/SECURITY_AUDIT_2026-08-04.md` |
