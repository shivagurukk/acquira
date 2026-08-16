# Acquira — Master E2E Test Plan (556 new + 31 existing = 587 cases) — Bahrain & Egypt Tenants

**Date prepared:** 2026-08-15
**Prepared from:** live code inspection (frontend routes/guards, all REST controllers, `schema.sql`, `TransactionJobConfig`, `BinManagementController`, `JwtRequestFilter`, `AuthController`) plus all project docs (Feature Guide, Developer Guide, Security Audit 2026-08-04, EG/BH Implementation Report 2026-08-11, API Management Audit, Fee Engine Audit, Pre-Deployment Test Plan, OTP Plan) and the existing 31-case plan `docs/E2E_TEST_CASES_BAHRAIN_EGYPT_2026-08-15.md` (groups A–G), which this plan **extends without duplicating** — where an existing case covers a scenario, the new case cross-references it.

**Environment:** backend Spring Boot **:8081**, frontend Vite **:5173**, Postgres **127.0.0.1:5433/postgres**. Dev `spring.sql.init.mode=always` — **backend restart re-runs schema.sql and wipes tenants/facts; recreate test tenants after every restart.** Ingest the two tenants **sequentially** (partition-creation race on concurrent ingest).

---

## 1. Conventions

- **ID scheme:** `E2E-<MODULE>-NNN` — modules: LOGIN, USER, RBAC, TENANT, BIN, INGEST, FEE, UI, PDF, SEC, ADMIN, FLOW.
- **Priority:** P1 (blocker/critical path) → P4 (cosmetic). **Severity:** C/H/M/L. **Type:** F=Functional, N=Negative, B=Boundary, S=Security, I=Integration, D=DB validation, A=API validation, U=UI. **P/N:** Positive/Negative.
- **Validation tags inside Expected Result:** `[DB]` database check, `[API]` direct API check, `[ISO]` tenant-isolation check, `[PDF]` PDF content check, `[AUD]` audit-log check.
- **Execution columns** (Actual Result, Pass/Fail, Defect ID, Remarks) are intentionally blank at authoring time — record them in the Execution Tracker (§18) per case ID. Every case starts at status **☐ Not executed**.
- Steps are written condensed; each step's expected result is the clause following “→”.

## 2. Test users and roles

| Ref | User | Role / Group | Tenant scope | Source |
|---|---|---|---|---|
| U-SA | `admin` (pw `password`, seeded `{noop}`) or `superadmin` | ROLE_SUPER_ADMIN / Super Admin | all tenants | schema.sql seed |
| U-BA-BH | `tbh_admin` (create) | ROLE_ADMIN / Bank Admin | TBH only | created in E2E-USER-001 |
| U-BA-EG | `teg_admin` (create) | ROLE_ADMIN / Bank Admin | TEG only | created in E2E-USER-002 |
| U-BU-BH | `tbh_user` (create) | ROLE_USER / Business User | TBH only | created in E2E-USER-003 |
| U-FU-EG | `teg_finance` (create) | ROLE_USER / Finance User | TEG only | created in E2E-USER-004 |
| U-OPS | `ops_user` (create) | ROLE_USER / Ops User | TBH+TEG (multi) | created in E2E-USER-005 |
| U-EX | `sivag` / `ITAcquiring` | Bank Admin, tenant 1 (ACQ) | ACQ | seeded |

Groups (seeded): **Super Admin, Bank Admin, Business User, Finance User, Ops User.** Spring roles: ROLE_SUPER_ADMIN, ROLE_ADMIN, ROLE_USER (+vestigial ROLE_BANK_USER default on `/api/admin/users` creation).

## 3. Test tenants and card/BIN data

Tenants (as in the existing plan): **TBH** = TESTBH01 / Test Bank Bahrain / BH / BHD (3 dp) / AMS / card_type_source=BIN; **TEG** = TESTEG01 / Test Bank Egypt / EG / EGP (2 dp) / AMS / BIN. Default seeded tenant **ACQ** (BANK001) is the control tenant and must never receive test rows.

BIN fixtures (verified in local `ref_bin_range`, 806,469 rows): BH-L1 `510146` MC/BH/DEBIT/CIR · BH-L2 `401575` VISA/BH/CREDIT/N · BH-L3 `510543` MC/BH/PREPAID/MRH · EG-L1 `222698` MC/EG/DEBIT/CIR · EG-L2 `400112` VISA/EG/CREDIT/N1 · EG-L3 `400725` VISA/EG/PREPAID/F · NL-1 `429625` VISA/US/CREDIT/B (non-local for both) · XX-1 `999999` (no range — negative). PAN mask `first6+******+last4`. `ref_bin` (manual 6/8-digit table) is **empty** — cases needing it upload `BIN_TEST_SET.csv` (defined in §8 preamble).

Feed files: `TBH_E2E_TXN_JUL2026.csv` / `TEG_E2E_TXN_JUL2026.csv` (AMS, Entity Name in row 2 cell 1 = `TBH`/`TEG`, 8 rows each: 3 local, 2 NL-1 INTERNATIONAL, 1 `ONSHORE` unknown token, 1 XX-1, 1 refund; amounts 3 dp BHD / 2 dp EGP; MIDs `TBH-M001`/`TEG-M001`). Boundary amounts used throughout: `99.999`, `0.001`, `1.005`, `450.755`, `100.505`.

## 4. Known implementation gaps — cases expected to FAIL (kept deliberately, to document the gap with evidence)

| Gap | Evidence | Affected cases |
|---|---|---|
| BIN-based card/product type NOT wired into ingestion (`card_type_source` config-only) | BinManagementController.java:16, Tenant.java:51 | E2E-BIN-030..037, existing C3/C4/D2 |
| Menu grants not enforced server-side (`@menuAccess` bean never referenced) | MenuAccessEvaluator unused | E2E-RBAC-020..027 |
| ~40/53 controllers lack `@PreAuthorize` — ROLE_USER can call admin-adjacent business/finance/report APIs | Security audit M-7 | E2E-SEC-010..018 |
| `X-Forwarded-For` trusted in RateLimitFilter/ApiKeyAuthFilter/AuthController | Security audit H-1 | E2E-SEC-030..032 |
| PANs cleartext in DB; masking only in TransactionController path | Security audit H-2 | E2E-SEC-020..022 |
| schema.sql re-seed resets `admin` password on restart (prod C-1) | Security audit C-1 | E2E-SEC-001 |
| API key rotation absent; IP allowlist exact-string only | API audit | E2E-SEC-040..049 |
| No duplicate-file guard on transaction uploads; APPEND double-counts | FileUploadService | E2E-INGEST-030..032 |

---
## 5. Module: Authentication, Session & Password (E2E-LOGIN) — 55 cases

**Shared preconditions:** backend+frontend up; seeded `admin` available; TBH/TEG tenants + users from §2 created (cases 001–010 need only `admin`). Endpoints under test: `POST /api/auth/login|refresh|logout-all|switch-context|forgot-password|verify-otp|reset-password`, `GET /api/auth/session`, `POST /api/users/change-password`. UI: `/login`, `/change-password`.

| ID | Sub-module | Tenant | Role | Scenario & steps | Test data / precondition | Expected result & validation | Pri | Sev | Type | P/N |
|---|---|---|---|---|---|---|---|---|---|---|
| E2E-LOGIN-001 | Login | n/a | SA | Valid login via UI → redirect | admin/password | 200; payload has jwt, refreshToken, allowedTenants, defaultTenantId, roles, menus, sessionTimeoutMinutes; redirect `/dashboard` (or tenant card if >1 tenant). [AUD] `LOGIN` row | P1 | C | F | P |
| E2E-LOGIN-002 | Login | n/a | any | Wrong password rejected | valid user, bad pw | Generic 401 "Invalid username or password"; no token stored; [AUD] `LOGIN_DENIED` with real reason | P1 | H | N | N |
| E2E-LOGIN-003 | Login | n/a | any | Nonexistent username | `nouser`/any | Same generic 401 as 002 (no enumeration); comparable response time class | P2 | H | S | N |
| E2E-LOGIN-004 | Login | n/a | any | Blank username or password | empty fields | 400 from API; UI blocks/announces error; no request storm | P3 | M | N | N |
| E2E-LOGIN-005 | Login | n/a | any | Inactive user login | U-BU-BH set active=false | Generic 401 (not 403/423); [AUD] LOGIN_DENIED reason=inactive | P1 | H | N | N |
| E2E-LOGIN-006 | Login | n/a | any | Pending-approval user login | SSO access request approved-pending state | Generic 401; no session | P2 | M | N | N |
| E2E-LOGIN-007 | Login | n/a | any | Expired account auto-deactivates | user with accountExpiry in past | Generic 401; [DB] `users.is_active=false` flipped | P2 | H | N | N |
| E2E-LOGIN-008 | Lockout | n/a | any | 5 wrong passwords → lock | U-BU-BH, 5 bad attempts | Attempts 1–4 → 401; 5th sets `locked_until=now+15m`; 6th → **423** `locked:true`, no `lockedUntil` value leaked | P1 | H | S | N |
| E2E-LOGIN-009 | Lockout | n/a | any | Correct password while locked | locked user, right pw | Still 423 (not 200) | P1 | H | S | N |
| E2E-LOGIN-010 | Lockout | n/a | any | Lockout auto-expires and counter resets | wait past `lockout_duration_minutes` (set 1 min via Security Settings) | Login succeeds; [DB] `failed_login_attempts=0` | P2 | M | F | P |
| E2E-LOGIN-011 | Lockout | n/a | SA | Admin unlock before expiry | `POST /api/users/{id}/unlock` | 200; user logs in; [AUD] `UNLOCK_USER` | P2 | M | F | P |
| E2E-LOGIN-012 | Rate limit | n/a | any | IP+username limiter | 11 rapid logins same user | 429 after 10 attempts/60s window | P2 | H | S | N |
| E2E-LOGIN-013 | Rate limit | n/a | any | Buckets independent per username | 10 fails userA then userB from same IP | userB not rate-limited (bucket key = ip-username) | P3 | M | B | P |
| E2E-LOGIN-014 | Session | n/a | any | Login response leaks no secrets | inspect payload | No `password_hash`, no `attemptsRemaining`, no lockedUntil | P1 | H | S | N |
| E2E-LOGIN-015 | Session | TBH | BA | JWT claims minimal | decode access token | Only sub/iat/exp (no roles/tenant claims — per current design); expiry = policy TTL (default 30 min) | P3 | L | A | P |
| E2E-LOGIN-016 | Session | any | any | `GET /auth/session` validates on every protected route mount | open `/dashboard` | "Validating Session…" then page; invalid token → redirect `/login` | P2 | M | F | P |
| E2E-LOGIN-017 | Refresh | any | any | Expired access token auto-refresh | wait past TTL (set short) then call API | Single `POST /auth/refresh`; original request replayed; concurrent 401s queued not duplicated | P1 | H | F | P |
| E2E-LOGIN-018 | Refresh | any | any | Refresh rotation | use refresh twice | 2nd use of OLD token → 401 + **all sessions revoked** (reuse detection); [DB] refresh_token rows revoked | P1 | C | S | N |
| E2E-LOGIN-019 | Refresh | any | any | Refresh token presented as access token | put refresh JWT in Authorization header | Rejected by JwtRequestFilter (`type:refresh`) | P2 | H | S | N |
| E2E-LOGIN-020 | Refresh | any | any | Refresh after `logout-all` | `POST /auth/logout-all` then refresh | 401; [DB] all refresh rows revoked; [AUD] `LOGOUT_ALL` | P2 | H | S | N |
| E2E-LOGIN-021 | Refresh | any | any | Refresh after admin revoke-all-sessions | SA: `POST /api/admin/security/revoke-all-sessions` | Target user's refresh → 401; active access token dies at expiry | P2 | H | S | N |
| E2E-LOGIN-022 | Refresh | any | any | Refresh cookie attributes | inspect Set-Cookie | HttpOnly; Secure; SameSite=Strict; path=/api/auth | P2 | H | S | P |
| E2E-LOGIN-023 | Logout | any | any | UI logout clears storage | sidebar Sign out | localStorage auth keys removed (token, refreshToken, menus, allowedTenants…); `theme`/recent-pages preserved; api cache invalidated; land on `/login` | P2 | M | F | P |
| E2E-LOGIN-024 | Logout | any | any | Back-button after logout | logout then browser Back | Protected page not rendered with data (session check fails → `/login`); no cached tenant data flashed | P1 | H | S | N |
| E2E-LOGIN-025 | Logout | any | any | Old access token after logout | replay saved Bearer token via curl | Documents actual behavior: token remains valid until exp (no server logout) — record as finding if it exceeds policy expectations | P2 | H | S | N |
| E2E-LOGIN-026 | Idle timeout | any | any | Inactivity logout | set session_timeout=1 min; idle 75s | Auto-clear + redirect `/login?expired=1`; banner "session timed out due to inactivity" | P2 | M | F | P |
| E2E-LOGIN-027 | Idle timeout | any | any | Activity resets idle timer | keep moving mouse past timeout | No logout | P3 | L | F | P |
| E2E-LOGIN-028 | Idle timeout | TBH vs TEG | any | Timeout is per-tenant policy | different `security.session_timeout_minutes` per tenant | Value in login/switch-context payload matches active tenant | P3 | M | F | P |
| E2E-LOGIN-029 | Concurrency | any | any | Concurrent-session cap | set maxConcurrentSessions=2; login 3 browsers | Oldest session's refresh revoked; 3rd works | P2 | M | S | P |
| E2E-LOGIN-030 | Password change | any | any | Self change-password happy path | current+new valid pw | 200; next login with new pw; old pw fails; [AUD] `CHANGE_PASSWORD`; [DB] password_history row added | P1 | H | F | P |
| E2E-LOGIN-031 | Password change | any | any | Wrong current password | bad currentPassword | 4xx with message; pw unchanged | P2 | M | N | N |
| E2E-LOGIN-032 | Password change | any | any | Strength rules enforced server-side | `short1!`, `nouppercase1!`, `NOLOWER1!`, `NoNumber!!`, `NoSpecial11` | Each rejected; UI checklist mirrors: ≥8, upper, lower, digit, special | P2 | M | B | N |
| E2E-LOGIN-033 | Password change | any | any | Password history reuse blocked | reuse previous password | Rejected per policy history N | P2 | M | N | N |
| E2E-LOGIN-034 | Forced change | any | any | `mustChangePassword` gate | admin resets user pw; user logs in | Redirect `/change-password`; any other API → 403 `PASSWORD_CHANGE_REQUIRED`; after change, full access restored | P1 | H | F | P |
| E2E-LOGIN-035 | Forced change | any | any | Gate cannot be bypassed by direct URL | navigate `/dashboard` while flagged | Bounced back to `/change-password` | P1 | H | S | N |
| E2E-LOGIN-036 | Password expiry | any | any | Aged password forces change | set `security.password_expiry_days` low / backdate | Login returns mustChangePassword=true; [AUD] `PASSWORD_EXPIRED` | P3 | M | F | P |
| E2E-LOGIN-037 | Forgot pw (OTP) | n/a | any | Request OTP for real email | U-BU-BH email | Generic success message; OTP mailed (or logged in dev); [DB] password_reset_token row, otp BCrypt-hashed | P1 | H | F | P |
| E2E-LOGIN-038 | Forgot pw (OTP) | n/a | any | Request OTP for unknown email | `nobody@x.com` | **Same generic success** (no enumeration); no token row | P1 | H | S | N |
| E2E-LOGIN-039 | Forgot pw (OTP) | n/a | any | Verify correct OTP | 6-digit from mail/log | Opaque ticket returned; UI advances to step 3 | P1 | H | F | P |
| E2E-LOGIN-040 | Forgot pw (OTP) | n/a | any | Wrong OTP ×5 burns token | 5 bad codes | Attempts 1–4 rejected; 5th/6th → 429/burned; must restart flow | P2 | H | S | N |
| E2E-LOGIN-041 | Forgot pw (OTP) | n/a | any | Expired OTP (>10 min) | wait TTL | Verify rejected; new request needed | P2 | M | B | N |
| E2E-LOGIN-042 | Forgot pw (OTP) | n/a | any | OTP input UX | letters, 5 digits | Non-digits stripped; submit disabled until exactly 6 digits | P4 | L | U | N |
| E2E-LOGIN-043 | Forgot pw (OTP) | n/a | any | Resend cooldown | click resend twice fast | 30s cooldown ticker enforced | P4 | L | U | N |
| E2E-LOGIN-044 | Reset pw | n/a | any | Complete reset with ticket | valid ticket + strong pw | 200; login with new pw; **all sessions revoked** [DB] refresh_token; mustChangePassword cleared; [AUD] `PWRESET_DONE` | P1 | H | F | P |
| E2E-LOGIN-045 | Reset pw | n/a | any | Ticket reuse / expiry | replay same ticket; wait 10 min | Both rejected (single-use, TTL) | P2 | H | S | N |
| E2E-LOGIN-046 | Reset pw | n/a | any | Weak new password at reset | `abc` | Rejected server-side; UI submit stays disabled | P2 | M | N | N |
| E2E-LOGIN-047 | SSO | n/a | any | SSO button visibility | SSO disabled in settings | Button hidden; config fetch has 4s abort guard (page still renders) | P3 | L | F | P |
| E2E-LOGIN-048 | SSO | n/a | any | SSO callback not_registered → request access | unregistered Entra user | Request-access form (org select+message) → `request_submitted`; [DB] access_request PENDING; [AUD] `SSO_ACCESS_REQUEST` | P2 | M | F | P |
| E2E-LOGIN-049 | SSO | n/a | any | `/api/sso/request-access` as user-existence oracle | probe with known vs unknown emails, unauthenticated | Responses must not differ (audit finding M-3 — record actual) | P2 | H | S | N |
| E2E-LOGIN-050 | Session/tenant | multi | OPS | Multi-tenant user gets tenant picker at login | U-OPS (TBH+TEG) | In-page tenant-select card; choosing TEG lands with TEG menus/currency | P1 | H | F | P |
| E2E-LOGIN-051 | Session/tenant | TBH | BA | Single-tenant user skips picker | U-BA-BH | Direct to `/dashboard`, X-Tenant-Id=TBH on all calls | P2 | M | F | P |
| E2E-LOGIN-052 | Login after changes | TBH | BA | Login after deactivation mid-session | SA deactivates U-BA-BH while logged in | Next request after token expiry/refresh → 401 (per-request DB re-check kills refresh); new login → 401 | P1 | H | S | N |
| E2E-LOGIN-053 | Login after changes | TBH | BU | Login after role/group change | move U-BU-BH from Business User to Bank Admin | Next login: new menus present; admin routes now allowed; old session gains rights only after re-login/refresh — record actual | P2 | M | F | P |
| E2E-LOGIN-054 | Login after changes | TEG | BU | Login after tenant access removed | delete U-BU-BH's TBH access, add TEG | Login lands in TEG; forced X-Tenant-Id=TBH → 403 | P1 | H | S | N |
| E2E-LOGIN-055 | Browser | any | any | Refresh (F5) mid-session preserves context | F5 on `/business/dashboard` | Same tenant, same page data after session validation; no logout, no tenant reset | P2 | M | F | P |
## 6. Module: User Management (E2E-USER) — 60 cases

**Endpoints:** `POST /api/users`, `PUT /api/users/{id}`, `POST /api/users/{id}/reset-password|unlock|assign`, tenant-access CRUD `/{id}/tenant-access[/{accessId}]`, `GET /api/users`, `/enriched`, `/export/csv`, `/check-email`, `/check-username`, `POST /api/admin/users`, `/api/admin/access-requests/{id}/approve|reject`. UI: `/users` (ADMIN+). No hard-delete endpoint exists (deactivate only).

| ID | Sub-module | Tenant | Role | Scenario & steps | Test data / precondition | Expected result & validation | Pri | Sev | Type | P/N |
|---|---|---|---|---|---|---|---|---|---|---|
| E2E-USER-001 | Create | TBH | SA | Create Bank Admin for TBH | username tbh_admin, strong pw, tenant TBH + group Bank Admin default | 201; `mustChangePassword=true`; [DB] users + user_tenant_access row isDefaultTenant; [AUD] `CREATE_USER` | P1 | C | F | P |
| E2E-USER-002 | Create | TEG | SA | Create Bank Admin for TEG | teg_admin | Same as 001 for TEG | P1 | C | F | P |
| E2E-USER-003 | Create | TBH | SA | Create Business User TBH | tbh_user, group Business User | 201; user sees only business menus | P1 | H | F | P |
| E2E-USER-004 | Create | TEG | SA | Create Finance User TEG | teg_finance, group Finance User | 201; finance menus only | P1 | H | F | P |
| E2E-USER-005 | Create | multi | SA | Create multi-tenant Ops user | ops_user, tenant-access TBH + TEG, default TBH | 201; two user_tenant_access rows; switcher lists both | P1 | H | F | P |
| E2E-USER-006 | Create | TBH | SA | Duplicate username rejected | reuse tbh_admin | 400 "username exists"; no row created | P1 | H | N | N |
| E2E-USER-007 | Create | TBH | SA | Duplicate email rejected | reuse existing email | 400; no row | P2 | M | N | N |
| E2E-USER-008 | Create | TBH | SA | Invalid email format | `bad@`, `x y@z`, `noat.com` | Client "Invalid email" + server 400 | P2 | M | N | N |
| E2E-USER-009 | Create | TBH | SA | Blank username | whitespace | Client "Required"; no request | P2 | M | N | N |
| E2E-USER-010 | Create | TBH | SA | Missing password on create | leave pw blank | Client "Password does not meet all requirements"; no submit | P2 | M | N | N |
| E2E-USER-011 | Create | TBH | SA | Weak password variants | short/no-upper/no-lower/no-digit/no-special | Each blocked; strength meter reflects | P2 | M | B | N |
| E2E-USER-012 | Create | TBH | SA | No tenant assignment | remove all rows | Client "At least one tenant assignment is required" | P2 | H | N | N |
| E2E-USER-013 | Create | TBH | SA | Partial tenant-access failure surfaced | force one assign to fail (bad group) | Toast distinguishes "all failed" (invisible user) vs "some failed" | P3 | M | N | N |
| E2E-USER-014 | Create | n/a | BA | Bank Admin cannot assign SUPER_ADMIN | U-BA-BH creates user with SUPER_ADMIN | 403 (`mayAssignRole`) | P1 | H | S | N |
| E2E-USER-015 | Create | TEG | BA | Bank Admin can only create in own tenant | U-BA-BH creates user for TEG via `/api/admin/users?tenantId=TEG` | 403/blocked (non-SA may only pass active tenant) | P1 | H | S | N |
| E2E-USER-016 | Availability | TBH | SA | check-username live | GET /check-username?u=tbh_admin | returns taken; new name returns available | P4 | L | A | P |
| E2E-USER-017 | Availability | TBH | SA | check-email live | GET /check-email | taken vs available correct | P4 | L | A | P |
| E2E-USER-018 | Edit | TBH | SA | Edit display name/email | change fields | 200; [AUD] `UPDATE_USER`; username field disabled in edit | P2 | M | F | P |
| E2E-USER-019 | Edit | TBH | SA | Username immutable on edit | attempt to change | Field disabled; API ignores/rejects | P3 | M | N | N |
| E2E-USER-020 | Edit | TBH | SA | Change email to an existing one | dup email | 400 (dup check only when changed) | P2 | M | N | N |
| E2E-USER-021 | Edit | n/a | SA | Cannot deactivate own account | SA toggles self active=false | 400 "cannot deactivate your own account" | P1 | H | N | N |
| E2E-USER-022 | Edit | TBH | BA | Bank Admin cannot act on SA user | U-BA-BH edits `admin` | 403 (`canActOnUser`) | P1 | H | S | N |
| E2E-USER-023 | Deactivate | TBH | SA | Deactivate then access denied | disable tbh_user, it retries API with old token | After token expiry/refresh → 401; [AUD] UPDATE_USER | P1 | H | S | N |
| E2E-USER-024 | Activate | TBH | SA | Reactivate user | enable tbh_user | Login works again | P2 | M | F | P |
| E2E-USER-025 | Reset pw | TBH | SA | Admin reset password | reset tbh_user | 200; mustChangePassword set; lockout cleared; [AUD] `RESET_PASSWORD`; user forced to change on next login | P1 | H | F | P |
| E2E-USER-026 | Reset pw | TBH | SA | Reset pw weak value blocked | `abc` | Rejected (5 rules) | P2 | M | N | N |
| E2E-USER-027 | Reset pw | TBH | SA | Reset hidden for SSO-only user | SSO user row | Reset action not shown | P3 | L | U | P |
| E2E-USER-028 | Unlock | TBH | SA | Unlock action visibility | user with lockedUntil>now vs not | Unlock shown only when locked | P3 | L | U | P |
| E2E-USER-029 | Assign role | TBH | SA | Change group Business→Bank Admin | edit assignment | New menus after user re-login; [AUD] ASSIGN_TENANT/UPDATE_TENANT_ACCESS | P2 | M | F | P |
| E2E-USER-030 | Assign tenant | TBH | SA | Assign endpoint bad numeric | POST /users/{id}/assign bankId=abc | 400 (not 500) | P2 | M | N | N |
| E2E-USER-031 | Tenant access | multi | SA | Add second tenant grant | add TEG to tbh_user | New user_tenant_access; switcher now lists TEG; [AUD] ASSIGN_TENANT | P2 | M | F | P |
| E2E-USER-032 | Tenant access | multi | SA | Remove tenant grant | delete TEG grant | 200; [AUD] `REVOKE_TENANT_ACCESS`; user can no longer switch to TEG; forced X-Tenant-Id=TEG → 403 | P1 | H | S | N |
| E2E-USER-033 | Tenant access | multi | SA | Change default tenant | set default to TEG | Login lands TEG; only one default enforced | P3 | M | F | P |
| E2E-USER-034 | Tenant access | TBH | SA | Duplicate tenant filtered in picker | open add-grant | Already-granted tenant absent from select | P4 | L | U | P |
| E2E-USER-035 | List/scope | TBH | BA | Bank Admin sees only own-tenant users | U-BA-BH GET /users | Only TBH-scoped users; no TEG users | P1 | H | ISO | P |
| E2E-USER-036 | List/scope | all | SA | SA sees all users | admin GET /enriched | All tenants' users | P2 | M | F | P |
| E2E-USER-037 | Search | TBH | SA | Search by username/email/display | type query | Client filter narrows list | P3 | L | U | P |
| E2E-USER-038 | Filter | TBH | SA | Status filter ALL/ACTIVE/INACTIVE/SSO/PENDING | toggle each | Rows match; page resets to 1 | P3 | L | U | P |
| E2E-USER-039 | Sort | TBH | SA | Sort columns | click User/Role/Status | Sort order applied | P4 | L | U | P |
| E2E-USER-040 | Pagination | TBH | SA | Client pagination PAGE_SIZE=25 | >25 users | Prev/Next; safe-page clamp; resets on filter | P3 | L | U | P |
| E2E-USER-041 | Export | TBH | SA | Users CSV export | Download CSV | Blob, filename from Content-Disposition; [AUD] export audited (GET /export) | P3 | M | F | P |
| E2E-USER-042 | Export | TBH | BA | Export scoped to tenant | U-BA-BH export | No TEG users in file [ISO] | P2 | H | ISO | P |
| E2E-USER-043 | Access req | TBH | SA | Approve access request | approve with tenant+group | User created; [AUD] `ACCESS_REQUEST_APPROVED`; PENDING count decrements | P2 | M | F | P |
| E2E-USER-044 | Access req | TBH | SA | Approve without tenant/group | omit selections | Blocked "Select tenant and group" | P3 | M | N | N |
| E2E-USER-045 | Access req | TBH | SA | Reject access request | reject + reason | [AUD] `ACCESS_REQUEST_REJECTED`; row leaves PENDING | P3 | L | F | P |
| E2E-USER-046 | Access req | TBH | BA | Access-request actions require ADMIN+ | U-BU-BH hits approve endpoint | 403 | P2 | H | S | N |
| E2E-USER-047 | Account expiry | TBH | SA | Set account expiry future | datetime-local | Saved as wall-clock string (not ISO); row badge "Expires <date>" | P3 | L | F | P |
| E2E-USER-048 | Account expiry | TBH | SA | Past expiry blocks login | set expiry in past | Login 401 + is_active flipped (ties to LOGIN-007) | P2 | H | N | N |
| E2E-USER-049 | Self-service | TBH | BU | change-password allowed for any authenticated | U-BU-BH /users/change-password | 200 (only endpoint on UserController open to non-admin) | P2 | M | F | P |
| E2E-USER-050 | RBAC guard | TBH | BU | Non-admin cannot list users | U-BU-BH GET /api/users | 403 (@PreAuthorize ADMIN+) | P1 | H | S | N |
| E2E-USER-051 | RBAC guard | TBH | BU | Non-admin cannot create user | U-BU-BH POST /api/users | 403 | P1 | H | S | N |
| E2E-USER-052 | RBAC guard | TBH | BU | Non-admin cannot reset another's pw | POST /users/{id}/reset-password | 403 | P1 | H | S | N |
| E2E-USER-053 | IDOR | TBH | BA | Edit user in another tenant by id | U-BA-BH PUT /users/{teg_user_id} | 403 (`canActOnUser`) — no cross-tenant edit | P1 | C | S | N |
| E2E-USER-054 | IDOR | TBH | BA | Reset pw of another tenant's user by id | POST /users/{teg_id}/reset-password | 403 | P1 | C | S | N |
| E2E-USER-055 | Delete | TBH | SA | Confirm no hard-delete path | attempt DELETE /api/users/{id} | 404/405 (endpoint absent) — deactivation is the only removal | P3 | M | N | N |
| E2E-USER-056 | Badges | TBH | SA | Row badges render | users with SSO/locked/expired/mustChange | Correct badges shown | P4 | L | U | P |
| E2E-USER-057 | Session after perm change | TBH | BU | Live permission change effect | change group while user active | Existing token keeps old menus until refresh/re-login; document actual latency | P2 | M | S | N |
| E2E-USER-058 | Concurrency | TBH | SA | Two admins edit same user | simultaneous PUT | Last-write-wins or conflict handling; no corruption | P3 | L | N | N |
| E2E-USER-059 | Validation | TBH | SA | Very long field values | 500-char displayName/email | Rejected or truncated safely; no 500 | P3 | L | B | N |
| E2E-USER-060 | Validation | TBH | SA | Unicode / injection in name | `<script>`, `Ω名` | Stored/escaped safely; no XSS on render in user list | P2 | H | S | N |
## 7. Module: Roles, Permissions & RBAC (E2E-RBAC) — 45 cases

**Model:** Spring roles {ROLE_SUPER_ADMIN, ROLE_ADMIN, ROLE_USER} × groups {Super Admin, Bank Admin, Business User, Finance User, Ops User} → menus via `sys_group_menu`. Client `RoleGuard` = exact `userRole` string match. Server URL rules: `/api/admin/**` & `/api/batch/**` = ADMIN+; everything else = authenticated only. Known gap: `@menuAccess` never referenced → menu-only screens' APIs reachable by any authenticated user.

| ID | Sub-module | Tenant | Role | Scenario & steps | Expected result & validation | Pri | Sev | Type | P/N |
|---|---|---|---|---|---|---|---|---|---|
| E2E-RBAC-001 | Group CRUD | n/a | SA | Create group with menu matrix | `POST /admin/rbac/groups`; select menus per category | Group saved with menu grants; visible in list | P2 | M | F | P |
| E2E-RBAC-002 | Group CRUD | n/a | SA | Group name required | blank name | Client blocks; no upsert | P3 | M | N | N |
| E2E-RBAC-003 | Group CRUD | n/a | SA | Edit group menus | uncheck several menus | Members lose those sidebar items after re-login | P2 | M | F | P |
| E2E-RBAC-004 | Group CRUD | n/a | ADMIN | Create group requires SUPER_ADMIN | U-BA-BH POST /admin/rbac/groups | 403 (method-level SUPER_ADMIN) | P1 | H | S | N |
| E2E-RBAC-005 | Group CRUD | n/a | ADMIN | Bank Admin can VIEW groups/menus | GET /admin/rbac/groups, /menus | 200 (class ADMIN+) | P3 | L | F | P |
| E2E-RBAC-006 | Menus | TBH | BU | Sidebar reflects group grants | login U-BU-BH | Only Business-category menus; no Admin/Tenants/Users | P1 | H | F | P |
| E2E-RBAC-007 | Menus | TEG | FU | Finance user sidebar | login U-FU-EG | Finance menus present; no admin | P2 | M | F | P |
| E2E-RBAC-008 | Menus | multi | OPS | Ops user sidebar | login U-OPS | Operations menus; upload/batch present | P2 | M | F | P |
| E2E-RBAC-009 | Route guard | any | BU | ROLE_USER hitting SUPER_ADMIN-only route | navigate `/tenants` | RoleGuard → redirect `/dashboard` (client) | P1 | H | S | N |
| E2E-RBAC-010 | Route guard | any | ADMIN | ADMIN on SUPER_ADMIN-only route | U-BA-BH navigate `/admin/backups` | Redirect `/dashboard` | P1 | H | S | N |
| E2E-RBAC-011 | Route guard | any | ADMIN | ADMIN allowed on ADMIN routes | `/users`, `/upload`, `/admin/audit-logs` | Rendered | P2 | M | F | P |
| E2E-RBAC-012 | Route guard | any | BU | ROLE_USER on ADMIN route | `/upload` | Redirect `/dashboard` | P1 | H | S | N |
| E2E-RBAC-013 | Settings deep-link | any | ADMIN | superAdminOnly settings hidden | U-BA-BH open `/settings/tenants` | Section hidden in nav and on deep link | P2 | H | S | N |
| E2E-RBAC-014 | Menu vs data | TBH | BU | Menu absent but route open | remove a menu but keep route unguarded | Sidebar hides it; direct URL still renders (documents menu≠route coupling) | P3 | M | N | N |
| E2E-RBAC-015 | API URL rule | any | BU | ROLE_USER on /api/admin/** | GET /api/admin/settings | 403 | P1 | H | S | N |
| E2E-RBAC-016 | API URL rule | any | BU | ROLE_USER on /api/batch/** | GET /api/batch/jobs | 403 | P1 | H | S | N |
| E2E-RBAC-017 | API URL rule | any | BU | ROLE_USER on /api/upload | POST /api/upload | 403 (class @PreAuthorize) | P1 | H | S | N |
| E2E-RBAC-018 | Method guard | any | ADMIN | ADMIN on SUPER_ADMIN method | U-BA-BH POST /api/admin/tenants | 403 | P1 | H | S | N |
| E2E-RBAC-019 | Method guard | any | ADMIN | ADMIN on maintenance run (SA only) | POST /admin/maintenance/run | 403 "Only a Super Admin can…"; GET status allowed | P2 | M | S | N |
| E2E-RBAC-020 | Menu-gap (defect) | TBH | BU | ROLE_USER calls report-builder delete | DELETE /api/reports/templates/{id} | Currently 200 (no @PreAuthorize) — **record as finding M-7** | P1 | H | S | N |
| E2E-RBAC-021 | Menu-gap (defect) | TBH | BU | ROLE_USER calls DataExplorer query | POST /api/explorer/query | Returns tenant data (no role gate) — finding | P1 | H | S | N |
| E2E-RBAC-022 | Menu-gap (defect) | TBH | BU | ROLE_USER calls AnalyticsExplorer | POST /api/analytics/explorer/query | Returns data — finding; verify still tenant-scoped | P1 | H | S | N |
| E2E-RBAC-023 | Menu-gap (defect) | TBH | BU | ROLE_USER calls Finance summary | GET /api/finance/summary | Returns net-revenue data — finding | P1 | H | S | N |
| E2E-RBAC-024 | Menu-gap (defect) | TBH | BU | ROLE_USER calls Executive dashboard | GET /api/analytics/executive | Returns data — finding | P2 | H | S | N |
| E2E-RBAC-025 | Menu-gap (defect) | TBH | BU | ROLE_USER calls RevenueKpi | POST /api/business/revenue-kpis | Returns data — finding | P2 | H | S | N |
| E2E-RBAC-026 | Menu-gap (defect) | TBH | BU | ROLE_USER calls ceo-volume-revenue (in-body guard) | GET /api/business/ceo-volume-revenue | In-body role check should block — verify actually enforced | P2 | M | S | N |
| E2E-RBAC-027 | Menu-gap (defect) | TBH | BU | ROLE_USER hits budget targets write | POST /api/business/budget/targets | 403 (method ADMIN+) — confirm this one IS guarded | P2 | M | S | P |
| E2E-RBAC-028 | Role display | TBH | SA | UserManagement shows role read-only | view user row | `role.replace('ROLE_','')`, not editable directly | P4 | L | U | P |
| E2E-RBAC-029 | Escalation | TBH | BA | Bank Admin cannot self-escalate to SA | edit own group to Super Admin | Blocked by mayAssignRole | P1 | C | S | N |
| E2E-RBAC-030 | Escalation | TBH | BU | Business user cannot grant self admin | via any endpoint | No path; 403 | P1 | H | S | N |
| E2E-RBAC-031 | SMTP guard | TBH | BU | Non-admin on SMTP config | POST /api/email/smtp-configs | 403 (per-method ADMIN+) | P2 | H | S | N |
| E2E-RBAC-032 | API-key guard | TBH | BU | Non-admin on API keys | GET /api/admin/api-keys | 403 | P2 | H | S | N |
| E2E-RBAC-033 | Provision guard | TBH | ADMIN | ADMIN on tenant provisioning | GET /api/admin/provision/scripts | 403 (class SUPER_ADMIN) | P2 | H | S | N |
| E2E-RBAC-034 | Backup guard | TBH | ADMIN | ADMIN on backups | GET /api/admin/backups | 403 (class SUPER_ADMIN) | P2 | H | S | N |
| E2E-RBAC-035 | Migration guard | TBH | ADMIN | ADMIN on migration start | POST /api/admin/migration/start | 403 (SA only) | P2 | H | S | N |
| E2E-RBAC-036 | Interchange guard | TBH | ADMIN | ADMIN on interchange-normalization | POST /admin/interchange-normalization/apply | 403 (SA per-method) | P2 | H | S | N |
| E2E-RBAC-037 | BIN guard | TBH | ADMIN | ADMIN on BIN management | GET /api/admin/bins/stats | 403 (class SUPER_ADMIN) | P2 | H | S | N |
| E2E-RBAC-038 | Transactions export guard | TBH | BU | ROLE_USER CSV export | GET /api/transactions/export/csv | 403 (ADMIN+ on export) | P2 | H | S | N |
| E2E-RBAC-039 | Backfill guard | TBH | ADMIN | ADMIN on backfill | POST /api/batch/backfill | 403 (SA only) | P2 | M | S | N |
| E2E-RBAC-040 | Sales targets guard | TBH | BU | ROLE_USER on sales targets write | POST /api/sales/targets/yearly | 403 (class ADMIN+) | P3 | M | S | N |
| E2E-RBAC-041 | Positive admin | TBH | ADMIN | ADMIN allowed on audit logs | GET /api/admin/audit-logs | 200 | P3 | L | F | P |
| E2E-RBAC-042 | Positive SA | all | SA | SA allowed everywhere | spot-check SA on all guarded endpoints | 200/allowed | P2 | M | F | P |
| E2E-RBAC-043 | No-role default | TBH | any | Server sends no role → defaults ROLE_USER | user with no role_in_tenant/group | Treated ROLE_USER; least privilege | P3 | M | F | P |
| E2E-RBAC-044 | Group mapping | TBH | any | Super Admin group → ROLE_SUPER_ADMIN+ROLE_ADMIN | user in Super Admin group | Gets both authorities | P3 | L | F | P |
| E2E-RBAC-045 | Group delete absent | n/a | SA | No group-delete in UI | inspect RbacGroups | No delete action; groups upsert-only | P4 | L | U | P |
## 8. Module: Tenant Management, Switching & Isolation (E2E-TENANT) — 69 cases

**Endpoints:** `POST /api/admin/tenants` (SA), `POST /api/banks` (SA), `PUT /api/banks/{id}`, `GET /api/admin/countries`, `POST /api/auth/switch-context`, `/api/admin/provision/*`. UI: `/tenants` (SA only), `/admin/tenant-provisioning` (SA). Isolation layers: JwtRequestFilter X-Tenant-Id check, TenantAspect `set_config('app.current_tenant',…)`, application `WHERE tenant_id=?`. Note: SA visibleTenants cached 60s statically.

### 8a. Provisioning & configuration

| ID | Sub-module | Tenant | Role | Scenario & steps | Expected result & validation | Pri | Sev | Type | P/N |
|---|---|---|---|---|---|---|---|---|---|
| E2E-TENANT-001 | Create | TBH | SA | Create Bahrain tenant | [DB] tenant row home_country_code='BH', base_currency='BHD', input_format='AMS', card_type_source='BIN', bank_short_code='TBH'; [AUD] `CREATE_TENANT` | P1 | C | F | P |
| E2E-TENANT-002 | Create | TEG | SA | Create Egypt tenant | [DB] home_country_code='EG', base_currency='EGP', 2dp | P1 | C | F | P |
| E2E-TENANT-003 | Create | n/a | SA | Jurisdiction auto-fills currency | pick Bahrain in dropdown | Currency/symbol/name/homeCountryCode auto-populate (read-only) from ref_country | P2 | M | F | P |
| E2E-TENANT-004 | Create | n/a | SA | Entity name + short code required | blank | Client blocks; short-code helper "must match uploaded file name" | P2 | M | N | N |
| E2E-TENANT-005 | Create | n/a | SA | Duplicate institution_id / short code | reuse TBH | Unique constraint → 400/409, no dup row | P2 | H | N | N |
| E2E-TENANT-006 | Create | n/a | ADMIN | Non-SA cannot create tenant | U-BA-BH POST /api/admin/tenants | 403 | P1 | H | S | N |
| E2E-TENANT-007 | Create | n/a | ADMIN | `/tenants` route SA-only | U-BA-BH navigate | Redirect `/dashboard` | P1 | H | S | N |
| E2E-TENANT-008 | Config | TBH | SA | Feed amount format select | choose CMM vs AMS | Persisted; drives ingest division | P2 | M | F | P |
| E2E-TENANT-009 | Config | TBH | SA | Card type source select | FILE vs BIN | Persisted (note: BIN currently inert in ingestion) | P3 | M | F | P |
| E2E-TENANT-010 | Config | TBH | SA | Edit tenant via PUT /banks/{id} | change bank_name | 200; reflected in switcher | P3 | L | F | P |
| E2E-TENANT-011 | Config | BH | SA | Currency decimals propagate to session | login/switch TBH | Payload currencyDecimals=3; [DB] ref_country.decimal_notation_value=1000 (BH), 100 (EG) | P1 | H | F | P |
| E2E-TENANT-012 | Config | n/a | SA | Startup guard AE+non-AED | create tenant home='AE' currency='BHD' | Startup ERROR logged for misconfig (Phase-1 guard) | P3 | M | N | N |
| E2E-TENANT-013 | Delete | n/a | SA | No tenant-delete path | inspect UI/API | No delete action (documents design) | P4 | L | U | P |
| E2E-TENANT-014 | Provision | TBH | SA | Provisioning script runs on create | create tenant with active scripts | [DB] provision logs; [AUD] `PROVISION_RUN` | P3 | M | I | P |
| E2E-TENANT-015 | Provision | TBH | SA | Provision failure doesn't abort creation | script with bad SQL | Tenant still created; failure logged | P3 | M | N | N |
| E2E-TENANT-016 | Provision | n/a | SA | Script requires name+SQL | blank | Client "Name and SQL are required" | P4 | L | N | N |
| E2E-TENANT-017 | Provision | TBH | SA | Run-now against selected tenant | POST /admin/provision/run/{TBH} | 200; log row; blocks with "Pick a tenant first" if none | P3 | L | F | P |
| E2E-TENANT-018 | SA visibility lag | n/a | SA | New tenant visible after ≤60s | create tenant, immediately query cross-tenant | Documents 60s static cache lag; visible after refresh | P3 | M | N | N |

### 8b. Tenant switching

| ID | Sub-module | Tenant | Role | Scenario & steps | Expected result & validation | Pri | Sev | Type | P/N |
|---|---|---|---|---|---|---|---|---|---|
| E2E-TENANT-020 | Switch | TBH→TEG | SA | Basic switch refreshes data | switcher → TEG | POST /switch-context; menus/currency/decimals change; tenantVersion++; page tree remounts; no reload | P1 | C | F | P |
| E2E-TENANT-021 | Switch | TBH→TEG | SA | Currency+precision change | dashboard amounts | BHD 3dp → EGP 2dp; no AED symbol anywhere | P1 | H | F | P |
| E2E-TENANT-022 | Switch | TEG→TBH | SA | No BH residue after→EG→BH | rapid switch cycle | All widgets re-fetch; zero EGP strings on BH screens; apiCache invalidated | P1 | C | ISO | N |
| E2E-TENANT-023 | Switch | TBH→TEG | SA | Switch mid-report | run a business report, then switch | Report re-runs for TEG; no stale BH rows (reqSeq guards out-of-order) | P1 | H | ISO | N |
| E2E-TENANT-024 | Switch | n/a | any | Single-tenant user: static row | U-BA-BH | Non-clickable row, no dropdown | P3 | L | U | P |
| E2E-TENANT-025 | Switch | TBH→TEG | OPS | Multi-tenant switch as non-SA | U-OPS TBH↔TEG | Both allowed; each scoped; X-Tenant-Id updates first before fetch | P2 | H | F | P |
| E2E-TENANT-026 | Switch | n/a | any | Switch to NaN tenant id | force bad id | `{success:false}`, no redirect, no request | P3 | L | N | N |
| E2E-TENANT-027 | Switch | TBH→TEG | SA | Locale refresh on switch | GET /users/me/locale re-fetched | dateFormat/timezone update per tenant (Africa/Cairo for EG) | P3 | M | F | P |
| E2E-TENANT-028 | Switch | TBH→TEG | SA | Menus replaced on switch | compare sidebars | TEG group menus loaded, not TBH's | P2 | M | F | P |
| E2E-TENANT-029 | Switch | TBH→TEG | SA | Recent-pages persist but data doesn't leak | check sidebar recents | Recent page links kept; opening one loads TEG data | P3 | L | ISO | P |

### 8c. Cross-tenant isolation & IDOR

| ID | Sub-module | Tenant | Role | Scenario & steps | Expected result & validation | Pri | Sev | Type | P/N |
|---|---|---|---|---|---|---|---|---|---|
| E2E-TENANT-030 | Header ISO | TEG | BA | Forged X-Tenant-Id (existing A4) | U-BA-BH JWT + X-Tenant-Id=TEG | 403 "Unauthorized tenant"; no data (JwtRequestFilter:180-205) | P1 | C | S | N |
| E2E-TENANT-031 | Header ISO | n/a | BA | Non-numeric X-Tenant-Id | header `abc` | Logged+ignored → default tenant; no cross-tenant leak | P2 | M | N | N |
| E2E-TENANT-032 | Header ISO | n/a | BA | Empty/undefined X-Tenant-Id | header '' | Falls back to user's default tenant | P3 | L | F | P |
| E2E-TENANT-033 | Header ISO | n/a | SA | SA bypasses access-list check | admin + any X-Tenant-Id | Allowed (SA cross-tenant read) — verify still scoped to that one tenant's rows | P2 | H | S | P |
| E2E-TENANT-034 | IDOR merchant | TBH | BA | Fetch TEG merchant by id | U-BA-BH GET /api/merchants/{teg_mid} | 404/403; no TEG merchant data | P1 | C | S | N |
| E2E-TENANT-035 | IDOR merchant 360 | TBH | BA | Merchant 360 cross-tenant | GET /api/merchants/{teg_id}/360 | Empty/403 | P1 | C | S | N |
| E2E-TENANT-036 | IDOR store | TBH | BA | Store/terminal cross-tenant | GET /api/stores/{teg_store}/terminals | No TEG data | P1 | H | S | N |
| E2E-TENANT-037 | IDOR pdf | TBH | BA | PDF for TEG merchant id | GET /api/business/insights/pdf?merchantId={teg} | 403 (PdfController passes tenantId to block IDOR) | P1 | C | S | N |
| E2E-TENANT-038 | IDOR saved view | TBH | BA | Access TEG saved filter by id | GET/PUT/DELETE /api/filters/views/{teg_id} | 403/not-found; no cross-tenant edit | P2 | H | S | N |
| E2E-TENANT-039 | IDOR template | TBH | BA | Access TEG report template by id | /api/reports/templates/{teg_id} | Not accessible | P2 | H | S | N |
| E2E-TENANT-040 | IDOR budget | TBH | BA | Delete TEG budget target by id | DELETE /api/business/budget/targets/{teg_id} | Blocked/scoped | P2 | H | S | N |
| E2E-TENANT-041 | IDOR alert rule | TBH | ADMIN | Edit TEG alert rule by id | PUT /api/admin/alerts/rules/{teg_id} | Scoped to own tenant | P2 | M | S | N |
| E2E-TENANT-042 | IDOR sweep | TBH | BA | Broad IDOR sweep across /api/** | substitute TEG ids on 15 GET endpoints (merchants, stores, transactions filter, group-analytics, segments, churn-risk, leaderboard, sales-portfolio, budget attainment, email logs) | None return TEG rows; record any that do | P1 | C | S | N |
| E2E-TENANT-043 | Query ISO | TBH | BA | Filter-strip attack | remove all client filters, raw API | Server binds tenant_id server-side; only TBH rows | P1 | H | ISO | N |
| E2E-TENANT-044 | GUC ISO | TBH | BA | app.current_tenant set per request | inspect via AI SQL or logs | set_config called; queries scoped even without explicit WHERE in some paths | P2 | M | D | P |
| E2E-TENANT-045 | DB sweep | all | SA | fact_transaction no cross-tenant rows | [DB] `SELECT tenant_id,COUNT(*) FROM fact_transaction GROUP BY 1` | Only ACQ/TBH/TEG expected ids; no TBH mid under TEG | P1 | C | D | N |
| E2E-TENANT-046 | DB sweep | all | SA | dim_merchant isolation | [DB] group by tenant_id | No cross-tenant merchant rows | P1 | H | D | N |
| E2E-TENANT-047 | DB sweep | all | SA | sum_daily_* & sum_monthly_* isolation | [DB] all summary tables group by tenant_id | Only expected ids | P1 | H | D | N |
| E2E-TENANT-048 | DB sweep | all | SA | kpi/segment tables isolation | [DB] merchant_attribute, explorer, insight tables | Only expected ids | P2 | M | D | N |
| E2E-TENANT-049 | RLS reality | all | SA | RLS effectively inert | [DB] `SELECT relname,relrowsecurity,relforcerowsecurity FROM pg_class WHERE relname IN (...)` | Documents fact_transaction/dim_merchant/users have no RLS; app connects as postgres superuser — finding C-2 | P2 | H | S | N |
| E2E-TENANT-050 | Currency mix | all | SA | Never sum BHD+AED+EGP across tenants | any cross-tenant aggregate | No mixed-currency total anywhere; each aggregate single-currency | P1 | H | D | N |
| E2E-TENANT-051 | Data-bounds ISO | TBH | BA | useDataBounds per tenant | open a report | Default date range = TBH data window, not TEG | P3 | M | F | P |
| E2E-TENANT-052 | Cache ISO | TBH→TEG | SA | apiCache filter-options/data-bounds refresh | switch and reopen | Cached lists rebuilt for TEG (invalidate on switch) | P2 | M | ISO | N |
| E2E-TENANT-053 | Report folder ISO | TEG | ADMIN | list-reports under TEG | GET /business/insights/list-reports | Only TEG files; watch PdfController shared-folder fallback :1150-1158 | P1 | H | ISO | N |
| E2E-TENANT-054 | Download ISO | TEG | ADMIN | download-all under TEG | GET /download-all-reports | Zip named _{TEG} contains no TBH PDFs | P1 | H | ISO | N |
| E2E-TENANT-055 | ACQ control | ACQ | SA | Control tenant untouched | after all test ingest | [DB] ACQ counts unchanged; no TBH/TEG rows under ACQ | P2 | H | D | N |
| E2E-TENANT-056 | Switch during upload | TBH | ADMIN | Switch tenant while a batch runs | start TBH upload, switch to TEG | Batch stays tenant-correct (job carries tenant); UI shows TEG; no cross-write | P2 | H | ISO | N |
| E2E-TENANT-057 | Concurrent SA reads | multi | SA | SA visibleTenants scope | SA reads multiple tenants | visibleTenants = all ids; each query scoped correctly | P3 | M | D | P |
| E2E-TENANT-058 | Session tenant on refresh | TBH | BA | F5 keeps active tenant | reload | Same tenant, same currency after session validation | P2 | M | F | P |
| E2E-TENANT-059 | Login default tenant | TBH | BA | is_default_tenant honored | login U-BA-BH | Lands on default tenant | P3 | L | F | P |
| E2E-TENANT-060 | Two-tenant default | multi | OPS | Exactly one default | inspect user_tenant_access | Single isDefaultTenant=true | P3 | L | D | P |
| E2E-TENANT-061 | Audit tenant stamp | TBH | BA | audit_log carries tenant_id | any audited action | [AUD] tenant_id column = TBH | P3 | M | D | P |
| E2E-TENANT-062 | Fail-open guard | n/a | any | Tenant-context failure fails open (M-6) | force null tenant context path | Documents whether request proceeds with null tenant (finding); should 403 not serve data | P2 | H | S | N |
| E2E-TENANT-063 | Cross-tenant export | TBH | ADMIN | Transactions CSV export scoped | export under TBH | Only TBH rows; PANs masked last-4; ≤100k cap | P2 | H | ISO | N |
| E2E-TENANT-064 | Group-report ISO | TEG | FU | Group analytics scoped | POST /group-analytics/MCC/filtered | Only TEG merchants | P2 | H | ISO | N |
| E2E-TENANT-065 | Email logs ISO | TEG | ADMIN | Email logs scoped | GET /email/logs?month= | Only TEG merchant emails | P2 | M | ISO | N |
| E2E-TENANT-066 | Audit viewer ISO | TEG | ADMIN | Audit logs scoped to active tenant | AuditLogViewer under TEG | Only TEG-tenant events | P2 | M | ISO | N |
| E2E-TENANT-067 | Leakage ISO | TEG | ADMIN | Leakage flags scoped | GET /leakage/flags | Only TEG flags | P2 | M | ISO | N |
| E2E-TENANT-068 | Sales data ISO | TEG | FU | Sales portfolio scoped | GET /sales-portfolio/executive | Only TEG sales data | P2 | M | ISO | N |
| E2E-TENANT-069 | Explorer staging ISO | TBH | BU | Data Explorer reads staging = last upload | after TEG upload then TBH view | TBH sees TBH staging only; no TEG staging bleed | P2 | H | ISO | N |
| E2E-TENANT-070 | Heatmap year ISO | TBH | BU | Heatmap per tenant/year | POST /analytics/heatmap-filtered?year=2026 | Only TBH cells | P3 | M | ISO | N |
## 9. Module: Card / BIN Management & Product Type (E2E-BIN) — 53 cases

**Endpoint:** `/api/admin/bins` (SUPER_ADMIN): GET `/stats|/ranges|` , POST `/upload` (mode REPLACE|APPEND), DELETE `` (truncate ref_bin), DELETE `/mpe/{id}`. UI: `/admin/bin-management` (SA). Upload routing by filename: `visa*`→fixed-width Visa→ref_bin_range; `t067/t068/t167/t168` or PK zip magic→MPE async; else CSV/Excel→ref_bin. CSV columns (case-insensitive, any order): BIN(6/8), SCHEME, CARD_TYPE, PRODUCT, COUNTRY, ISSUER. **`BIN_TEST_SET.csv`** = the 8 fixtures from §3 with correct scheme/type/product/country, used by upload cases. **Known gap:** nothing in ingestion/fee reads ref_bin/ref_bin_range (card_type_source=BIN inert).

### 9a. BIN upload & validation (config layer — testable today)

| ID | Sub-module | Tenant | Role | Scenario & steps | Expected result & validation | Pri | Sev | Type | P/N |
|---|---|---|---|---|---|---|---|---|---|
| E2E-BIN-001 | Upload CSV | n/a | SA | Upload valid BIN_TEST_SET.csv REPLACE | [DB] ref_bin has 8 rows; result reports loaded=8 rejected=0; confirm dialog when totalBins>0 | P1 | H | F | P |
| E2E-BIN-002 | Upload CSV | n/a | SA | Upload APPEND mode | mode=APPEND on top | Upsert ON CONFLICT(bin); existing kept, new added | P2 | M | F | P |
| E2E-BIN-003 | Upload CSV | n/a | SA | Column order/case independence | shuffle headers, mix case | Parsed correctly; extras ignored | P2 | M | F | P |
| E2E-BIN-004 | Upload CSV | n/a | SA | Header aliases | CARD_SCHEME/NETWORK, FUNDING, PRODUCT_CODE, ISO_COUNTRY | All aliases recognized | P3 | M | F | P |
| E2E-BIN-005 | Upload CSV | n/a | SA | Missing BIN column | drop BIN header | 400 "does not look like a CSV"/BIN required | P2 | M | N | N |
| E2E-BIN-006 | Upload CSV | n/a | SA | BIN not 6/8 digits | 5-digit, 7-digit, 9-digit rows | Rows rejected + counted; up to 20 samples returned | P2 | M | B | N |
| E2E-BIN-007 | Upload CSV | n/a | SA | Non-numeric BIN | `ABC123` | Rejected + sampled | P2 | M | N | N |
| E2E-BIN-008 | Upload Excel | n/a | SA | .xlsx BIN mapping | xlsx with trailing `.0` | `510146.0` stripped to 510146; loaded | P3 | M | F | P |
| E2E-BIN-009 | Upload CSV | n/a | SA | Oversize CSV line | >200KB single line | 400 "does not look like a CSV" | P3 | L | N | N |
| E2E-BIN-010 | Upload | n/a | SA | Binary/NUL non-BIN file | random binary | 400 (contains NUL) | P3 | M | N | N |
| E2E-BIN-011 | Upload REPLACE | n/a | SA | REPLACE deletes prior ref_bin | load set A then set B REPLACE | Only set B remains; confirm dialog shown | P2 | M | F | P |
| E2E-BIN-012 | Clear all | n/a | SA | Truncate ref_bin | DELETE with confirm | [DB] ref_bin empty; button disabled when 0 | P2 | M | F | P |
| E2E-BIN-013 | Ranges search | n/a | SA | Search ranges by prefix | q=510146 | Range containing prefix returned; limit clamped 1–500 | P2 | M | F | P |
| E2E-BIN-014 | Ranges search | n/a | SA | Malformed ranges flagged | ranges not 19-digit normalized | stats.malformedRanges>0 → warning alert | P3 | M | F | P |
| E2E-BIN-015 | Stats | n/a | SA | Stats totals accurate | GET /stats | Per-scheme/country counts match ref_bin_range (VISA 593505/MC 212964; BH 1094/EG 3736) | P3 | L | D | P |
| E2E-BIN-016 | Visa upload | n/a | SA | visa* filename → range table | file `visaBINlist.txt` | Routed to fixed-width Visa parse → full replace of VISA ranges | P3 | M | F | P |
| E2E-BIN-017 | MPE T068 | n/a | SA | Mastercard T068 full replace | `TT068...` file | Async PROCESSING → STAGED; full replace MASTERCARD ranges; last-wins dedup | P3 | M | I | P |
| E2E-BIN-018 | MPE T067 | n/a | SA | T067 delta A/I | `TT067...` | A upsert, I delete applied | P3 | M | I | P |
| E2E-BIN-019 | MPE dedup | n/a | SA | Duplicate content sha256 | re-upload same MPE | Allowed (replaces prior); in-flight PROCESSING <2h → 400 | P3 | L | N | N |
| E2E-BIN-020 | MPE count mismatch | n/a | SA | Trailer mismatch | tampered trailer count | status COUNT_MISMATCH (warning), not silent | P3 | M | N | N |
| E2E-BIN-021 | MPE polling | n/a | SA | Processing poll + toast | during PROCESSING | stats re-polled 5s; completion toast tone by status | P4 | L | U | P |
| E2E-BIN-022 | MPE delete | n/a | SA | Delete staged MPE | DELETE /mpe/{id} | Cascade removes records/dir; disabled while PROCESSING | P3 | L | F | P |
| E2E-BIN-023 | Orphan recovery | n/a | SA | Startup fails orphaned PROCESSING | restart mid-process | Orphan rows marked FAILED at startup | P3 | L | N | N |
| E2E-BIN-024 | Guard | n/a | ADMIN | Non-SA blocked | U-BA-BH GET /api/admin/bins/stats | 403 | P1 | H | S | N |
| E2E-BIN-025 | Guard | n/a | BU | ROLE_USER blocked | 403 | P1 | H | S | N |
| E2E-BIN-026 | UI | n/a | SA | No accept filter on file input | select any file | Deliberate — MPE bare names allowed | P4 | L | U | P |
| E2E-BIN-027 | Product mapping | n/a | SA | Visa funding letters → type | D/P/C,H,R rows | DEBIT/PREPAID/CREDIT mapping correct | P3 | M | F | P |
| E2E-BIN-028 | Product mapping | n/a | SA | MC product sets → type | MC_CREDIT/DEBIT/PREPAID codes | Correct card_type derived | P3 | M | F | P |
| E2E-BIN-029 | Range vs bin6 | n/a | SA | Licensed BIN ≠ prefix | inspect Visa ranges | bin6 differs from range prefix on ~98% (per memory) — displayed distinctly | P4 | L | U | P |

### 9b. BIN → card/product identification in ingestion — REQUIREMENT tests (expected FAIL, documents gap)

| ID | Sub-module | Tenant | Role | Scenario & steps | Expected (requirement) vs current | Pri | Sev | Type | P/N |
|---|---|---|---|---|---|---|---|---|---|
| E2E-BIN-030 | Card type from BIN (BH) | TBH | SA | Ingest BH-L1 `510146…` with contradictory file Card Type=CREDIT | **Req:** fact card_type=DEBIT (BIN wins). **Current:** file value wins → **FAIL**; ref_bin* unread | P1 | H | D | P |
| E2E-BIN-031 | Card type from BIN (EG) | TEG | SA | Ingest EG-L1 `222698…` contradictory file type | Same as 030 → **FAIL** | P1 | H | D | P |
| E2E-BIN-032 | First-6 extraction | TBH | SA | Verify LEFT(card_number,6) lookup happens | Inspect ingestion; no first-6 BIN lookup exists → **FAIL** | P1 | H | D | P |
| E2E-BIN-033 | Product from BIN (BH) | TBH | SA | BH-L3 `510543`→product MRH | fact card_product_code from BIN → **FAIL** (not populated from BIN) | P2 | M | D | P |
| E2E-BIN-034 | Product from BIN (EG) | TEG | SA | EG-L3 `400725`→F | → **FAIL** | P2 | M | D | P |
| E2E-BIN-035 | No hardcoding by tenant | both | SA | NL-1 `429625` under both tenants | card_type identical (CREDIT) both tenants — proves no tenant-based hardcode; [DB] compare | P2 | H | D | P |
| E2E-BIN-036 | Unknown BIN | TBH | SA | XX-1 `999999` no range | Ingested without crash; no BIN-derived fields; card_type from file or NULL | P2 | M | N | N |
| E2E-BIN-037 | Missing product mapping | TBH | SA | Known BIN, no product in table | Handled gracefully; no 500; flagged unknown | P2 | M | N | N |

### 9c. Card number format (ingestion negatives)

| ID | Sub-module | Tenant | Role | Scenario & steps | Expected result | Pri | Sev | Type | P/N |
|---|---|---|---|---|---|---|---|---|---|
| E2E-BIN-040 | Invalid PAN | TBH | SA | Row PAN=`51` (too short) | Batch completes; row rejected or null card fields; no 500; error logged | P2 | M | N | N |
| E2E-BIN-041 | Long PAN | TBH | SA | 25-digit PAN | Handled without abort | P3 | L | B | N |
| E2E-BIN-042 | Non-numeric PAN | TBH | SA | `ABCD****EFGH` | Handled; row flagged | P3 | M | N | N |
| E2E-BIN-043 | Blank PAN | TBH | SA | empty card field | Row ingested with null card fields; batch OK | P3 | M | N | N |
| E2E-BIN-044 | Masked PAN preserved | TBH | SA | `510146******1001` | Stored as-is; display masking on UI | P3 | L | F | P |
| E2E-BIN-045 | Duplicate BIN config | n/a | SA | Two ref_bin rows same BIN diff product | Upsert ON CONFLICT(bin) → last wins, no dup | P3 | M | N | N |
| E2E-BIN-046 | Inactive BIN scenario | n/a | SA | (No active flag in schema) verify behavior | Documents absence of inactive-BIN concept | P4 | L | N | N |
| E2E-BIN-047 | Local BIN both schemes | TBH | SA | BH-L1(MC) + BH-L2(Visa) | Both resolve scheme correctly via ref_card_scheme | P3 | M | D | P |
| E2E-BIN-048 | Product type on UI | TBH | BU | Transactions screen shows card/product | Card type column matches [DB] fact rows | P2 | M | U | P |
| E2E-BIN-049 | Product type in PDF | TBH | ADMIN | PDF card analytics page | Credit/debit/prepaid split matches [DB] GROUP BY card_type | P2 | M | PDF | P |
| E2E-BIN-050 | Product type downstream | TBH | SA | Product code drives fee rule tier | [DB] fee resolution uses product code first then network | P2 | M | D | P |
| E2E-BIN-051 | Tenant-specific BIN behavior | both | SA | Same BIN both tenants, product-only scope | Locality still from destination token (not BIN) per 2026-08-09 decision | P2 | M | D | P |
| E2E-BIN-052 | Missing BIN data callout | n/a | SA | ref_bin empty at start | Documents that manual BIN table is empty; upload needed for BIN-source tests | P3 | M | N | N |
| E2E-BIN-053 | BIN stats after upload | n/a | SA | Post-upload stats reflect load | totalBins increments; distinctCountries updates | P3 | L | D | P |
| E2E-BIN-054 | Reject sample cap | n/a | SA | 100 bad rows | Rejected count accurate; ≤20 samples returned | P3 | L | B | N |
| E2E-BIN-055 | Card scheme normalization | TBH | SA | Missing scheme token backfilled | ref_card_scheme int→DEBIT/CREDIT/PREPAID/UNKNOWN mapping (2,4→DEBIT;0,1→CREDIT;3→PREPAID) | P2 | M | D | P |

## 10. Module: Ingestion & Batch (E2E-INGEST) — 45 cases

**Endpoints:** `POST /api/upload|/multi|/process-server-file`, `GET /api/batch/jobs[/{id}]`, SSE `/api/batch/jobs/{id}/progress`. UI: `/upload`, `/ops/server-file`, `/ops/batch-logs`. Flow: ensurePartitions→splitExcel→cleanTargetDay→masterIngest→stagingToFact→populateSummary→metrics. Entity name = row2 cell1 = short code. Files: .xlsx/.csv/.tsv/.txt; .xls rejected. Load mode REPLACE default (JCB*=APPEND).

| ID | Sub-module | Tenant | Role | Scenario & steps | Expected result & validation | Pri | Sev | Type | P/N |
|---|---|---|---|---|---|---|---|---|---|
| E2E-INGEST-001 | Upload BH | TBH | SA | Upload TBH_E2E_TXN_JUL2026.csv | Batch SUCCESS; [DB] fact_transaction tenant_id=TBH = rowcount; TEG count 0 [ISO] | P1 | C | I | P |
| E2E-INGEST-002 | Upload EG | TEG | SA | Upload TEG file (sequential after 001) | TEG count correct; TBH unchanged; no cross-tenant rows | P1 | C | I | P |
| E2E-INGEST-003 | Entity resolve | TBH | SA | Entity name = short code resolves tenant | row2 cell1=TBH | Lands under TBH only | P1 | H | I | P |
| E2E-INGEST-004 | Entity resolve | n/a | SA | Unknown entity name | row2 cell1=`ZZZ` | Error naming entity; no ingest | P2 | H | N | N |
| E2E-INGEST-005 | Entity resolve | n/a | SA | Empty entity id | blank row2 cell1 | Error "Could not identify Entity/Tenant (Row 2, Cell 1 missing)" or session-tenant fallback | P2 | M | N | N |
| E2E-INGEST-006 | Entity resolve | TEG | ADMIN | Bank admin upload for wrong tenant | U-BA-BH uploads TEG file | "You belong to X but trying to upload for Y" / Permission Denied | P1 | H | S | N |
| E2E-INGEST-007 | Entity resolve | n/a | SA | SA upload with session tenant mismatch | SA session=TBH uploads TEG file | Upload refused, both tenants named | P2 | M | N | N |
| E2E-INGEST-008 | Format | TBH | SA | .xls rejected | upload .xls | Error "convert to Modern Excel (.xlsx)" | P2 | M | N | N |
| E2E-INGEST-009 | Format | TBH | SA | .csv/.tsv/.txt accepted | each format | Ingested | P3 | M | F | P |
| E2E-INGEST-010 | Format UI | TBH | ADMIN | Dropzone extension guard | drop .pdf | alert "Please upload an Excel or CSV file." | P3 | L | U | N |
| E2E-INGEST-011 | Format UI | TBH | ADMIN | Uppercase extension `.XLSX` | drop `.XLSX` | Client regex is case-sensitive → rejected (record as UX defect) | P3 | L | N | N |
| E2E-INGEST-012 | Progress | TBH | ADMIN | 5-stage tracker + SSE | during upload | Stages Splitting→Reading→Processing→Loading→Summarizing; rows/sec; progress bar | P3 | L | U | P |
| E2E-INGEST-013 | Summary modal | TBH | ADMIN | Post-upload summary | on completion | Rows read/written/skipped; unresolved-merchant %; scheme chips; load mode shown | P3 | M | U | P |
| E2E-INGEST-014 | Currency decimals | TBH | SA | BHD 3dp preserved at ingest | 100.505 BHD (AMS, no division) | [DB] fact stores 100.5050 (21,4); not truncated to 2dp | P1 | C | D | P |
| E2E-INGEST-015 | Currency decimals | TEG | SA | EGP 2dp | 150.50 EGP | [DB] fact 150.5000 | P1 | H | D | P |
| E2E-INGEST-016 | Boundary | TBH | SA | Boundary amounts | 99.999, 0.001, 1.005, 450.755 | [DB] stored exactly at 4dp; no rounding drift | P1 | H | B | P |
| E2E-INGEST-017 | CMM division | n/a | SA | CMM tenant minor units ÷ | (control: a CMM tenant) | Amounts divided by decimal divisor; MSF/VAT unit per feed_amount_contract | P2 | M | D | P |
| E2E-INGEST-018 | AMS no-division | TBH | SA | AMS skips all division | TBH AMS file | Amounts unchanged, currency codes still normalized | P2 | H | D | P |
| E2E-INGEST-019 | Destination map | TBH | SA | LOCAL→DOMESTIC (existing C6) | rows Destination=LOCAL | [DB] destination=DOMESTIC via destination_token_map (BH) | P1 | H | D | P |
| E2E-INGEST-020 | Destination map | TEG | SA | LOCAL→DOMESTIC (EG) | EG rows | Correct EG mapping | P1 | H | D | P |
| E2E-INGEST-021 | Destination map | both | SA | INTERNATIONAL token | NL-1 rows | destination=INTERNATIONAL | P2 | M | D | P |
| E2E-INGEST-022 | Destination neg | TBH | SA | Unknown token ONSHORE (existing C8) | ONSHORE row | destination NULL, destination_raw='ONSHORE'; fee_resolution_status=UNMAPPED_DESTINATION; not priced | P1 | H | N | N |
| E2E-INGEST-023 | Bad rows | TBH | SA | Row missing payment_date | one dateless row | Excluded by WHERE payment_date NOT NULL; reconciliation count check passes; batch completes | P2 | M | N | N |
| E2E-INGEST-024 | Reconciliation | TBH | SA | Staged-vs-inserted count assert | normal file | Counts match; logged | P2 | M | D | P |
| E2E-INGEST-025 | Refund | TBH | SA | RFND row | refund row | Negative volume/MSF, zero interchange+scheme fee | P2 | M | D | P |
| E2E-INGEST-026 | Refund gap | TBH | SA | ECOM flat fee on refund | refund with ECOM channel | Documents fee-audit finding: ECOM flat NOT zeroed on refund (interchange/scheme are) | P2 | M | N | N |
| E2E-INGEST-027 | Txn type gap | TBH | SA | Reversal/chargeback priced as purchase | VOID/PREAUX/chargeback row | Documents finding: only RFND/REFUND recognized; others priced as purchase (double count) | P2 | H | N | N |
| E2E-INGEST-028 | Partitions | TBH | SA | ensurePartitions creates monthly | new month data | fact_transaction_y2026m0X exists; no error | P3 | M | D | P |
| E2E-INGEST-029 | Concurrent ingest race | both | SA | Two tenants ingested concurrently | parallel upload | Documents partition-creation race (`already exists`) — must ingest sequentially | P2 | H | N | N |
| E2E-INGEST-030 | Dedup | TBH | SA | Re-upload same file REPLACE | upload TBH file twice | Idempotent (delete+reinsert per date); counts unchanged | P2 | H | D | P |
| E2E-INGEST-031 | Dedup gap | TBH | SA | APPEND double-counts | JCB* filename or load.mode=APPEND, re-upload | Rows duplicated — documents no file-checksum dedup | P2 | H | N | N |
| E2E-INGEST-032 | Split-day loss | TBH | SA | Two files same date+scheme (fee-audit TC-12) | upload file A then partial file B same day | Second load deletes first's rows wholesale → silent data loss; document | P1 | H | N | N |
| E2E-INGEST-033 | Server file | TBH | ADMIN | Process server folder | /ops/server-file valid path | Merchant files first then transactions, sequential; File Results table | P2 | M | I | P |
| E2E-INGEST-034 | Server file path | TBH | ADMIN | Path traversal blocked | path `../../etc/passwd` | 403 SecurityException (FileUploadService allowed-paths, toRealPath) | P1 | H | S | N |
| E2E-INGEST-035 | Server file path | TBH | ADMIN | Symlink prefix rejected | symlinked path | 403 | P2 | H | S | N |
| E2E-INGEST-036 | Multi upload | TBH | ADMIN | /upload/multi bulk | multiple files | Merchant→transaction order; one ingestion run per tenant | P3 | M | I | P |
| E2E-INGEST-037 | Merchant master | TBH | SA | Merchant file UPSERT | merchant CSV | ON CONFLICT(tenant_id,internal_id); normalizeSid fixes `4.00E+14` | P2 | M | D | P |
| E2E-INGEST-038 | Batch monitoring | TBH | ADMIN | Batch job list + SSE | /ops/batch-logs | Jobs listed; live SSE; 30s poll fallback; "Live updates unavailable" after 10s | P3 | L | U | P |
| E2E-INGEST-039 | Batch status | TBH | ADMIN | Job status endpoint | GET /batch/jobs/{id} | Status/steps accurate | P3 | L | A | P |
| E2E-INGEST-040 | Ref cache | n/a | SA | ref_country change needs restart | edit ref_country, re-ingest without restart | Stale REF_CACHE used — documents restart requirement | P3 | M | N | N |
| E2E-INGEST-041 | Load mode config | TBH | SA | tenant_setting load.mode | set APPEND per tenant | Honored over global default | P3 | L | F | P |
| E2E-INGEST-042 | Network error | TBH | ADMIN | Backend down during upload | stop :8081 | UI "Batch service is not running… start acquira-core (8081)" | P3 | L | N | N |
| E2E-INGEST-043 | Large file | TBH | ADMIN | Near 2GB upload | large file | No content-type/magic check (finding M-5); single replica OOM risk | P3 | M | N | N |
| E2E-INGEST-044 | Job cap | TBH | ADMIN | Upload poll wall-clock cap | very long job | Poll stops at 30-min cap or 5 consecutive errors | P4 | L | N | N |
| E2E-INGEST-045 | DB pull job | TBH | SA | dbPullTransactionJob path | integration pull | Same flow minus splitExcel/masterIngest; staging pre-populated | P3 | M | I | P |
## 11. Module: Fee / Interchange Engine (E2E-FEE) — 40 cases

**Basis:** fees computed off `store_base_currency_amount` (settlement). Rule select `ORDER BY (tenant_id IS NOT NULL) DESC, priority DESC, id ASC LIMIT 1`. Only `rate_status=APPROVED` prices. Golden values from Implementation Report 2026-08-11 §D. `net_revenue = msf − interchange − scheme_fee − ecom_fee`. Generic 1.85% fallback removed → NULL + status instead.

| ID | Sub-module | Tenant | Role | Scenario & steps | Expected result & validation | Pri | Sev | Type | P/N |
|---|---|---|---|---|---|---|---|---|---|
| E2E-FEE-001 | Basis | TBH | SA | Fee off settlement amount | [DB] fee computed from store_base_currency_amount not txn_currency_amount | P1 | H | D | P |
| E2E-FEE-002 | BH BENEFIT intl | TBH | SA | BENEFIT international interchange | 100.000 × 1.10% + 0.100 flat = **1.2000** [DB] | P1 | H | D | P |
| E2E-FEE-003 | BH BENEFIT cap | TBH | SA | Cap before flat (MCC 5541) | 0.6% × 45.750 = 0.2745 → capped **0.0850** [DB] | P1 | H | D | P |
| E2E-FEE-004 | BH Visa vs MC | TBH | SA | Distinct rules, no wildcard share | Visa rule 5442 ≠ MC rule 5056 [DB] applied_rule_id differs | P2 | M | D | P |
| E2E-FEE-005 | EG Visa | TEG | SA | EG Visa interchange | 1.75% rule 10098 [DB] | P1 | H | D | P |
| E2E-FEE-006 | EG MC | TEG | SA | EG Mastercard | 0.70% rule 9710 [DB] | P1 | H | D | P |
| E2E-FEE-007 | EG Meeza | TEG | SA | Meeza scheme | 1.85% rule 17726 [DB] | P2 | M | D | P |
| E2E-FEE-008 | EG channel | TEG | SA | POS vs ECOM | POS 1.75% vs ECOM 1.90% rule 11255 [DB] | P2 | M | D | P |
| E2E-FEE-009 | Scheme fee grid | both | SA | UAE grid adopted BH/EG | DOM POS 0.11%/ECOM 0.14%; INTL POS 0.75%/ECOM 0.90% [DB] | P2 | M | D | P |
| E2E-FEE-010 | Provenance | TBH | SA | Every priced row has rule id (existing D1) | [DB] applied_rule_id, fee_resolution_status populated; no generic-fallback rows | P1 | H | D | P |
| E2E-FEE-011 | Status NO_RATE | TBH | SA | Unmatched rate | row with no rule | fee NULL + status NO_RATE_FOUND (not silent 1.85%) | P1 | H | N | N |
| E2E-FEE-012 | Status PLACEHOLDER | both | SA | BH/EG Visa+MC intl pre-approval | PLACEHOLDER_RATE status, NULL scheme fee | P2 | M | N | N |
| E2E-FEE-013 | Status UNMAPPED_CHANNEL | both | SA | Unknown terminal channel | UNMAPPED_CHANNEL status | P2 | M | N | N |
| E2E-FEE-014 | Status UNMAPPED_DEST | TBH | SA | ONSHORE token | UNMAPPED_DESTINATION, unpriced | P1 | H | N | N |
| E2E-FEE-015 | Status NO_DEST | TBH | SA | Blank destination | NO_DEST status | P2 | M | N | N |
| E2E-FEE-016 | Two-tier resolve | TBH | SA | Product code first then network | [DB] resolution order product→network, space-insensitive | P2 | M | D | P |
| E2E-FEE-017 | Approved-only | both | SA | Non-APPROVED rate ignored | draft rate row | Not applied; falls through to status | P2 | M | D | P |
| E2E-FEE-018 | Effective-dating | both | SA | Effective_from/to matching | dated rate rows | Correct rate for txn date | P2 | M | D | P |
| E2E-FEE-019 | Net revenue | TBH | SA | Margin formula | [DB] net_revenue = msf−interchange−scheme_fee−ecom_fee | P1 | H | D | P |
| E2E-FEE-020 | Precision 4dp | TBH | SA | Interchange at 4dp | 1.75% on 100.505 = 1.7588 [DB] (21,4) | P1 | H | B | P |
| E2E-FEE-021 | Rollup exactness | TBH | SA | fact Σ = sum_daily_bank | [DB] BHR 1398.0150 reconciles exactly | P1 | H | D | P |
| E2E-FEE-022 | Rollup exactness | TEG | SA | EG rollup | EGY 801.2500 = fact Σ | P1 | H | D | P |
| E2E-FEE-023 | Rollup exactness | ACQ | SA | ACQ regression | ACQ 66.3600 unchanged | P2 | M | D | P |
| E2E-FEE-024 | Ticket bands | TBH | SA | Currency-scaled bands | BHR <5/25-50/50-100/100-500 BHD (renamed min_ticket) | P2 | M | D | P |
| E2E-FEE-025 | Ticket bands | TEG | SA | EG bands | EGY <500 EGP | P3 | M | D | P |
| E2E-FEE-026 | No cross-country leak | both | SA | Rule leakage query | [DB] cross-country rule match returns 0 | P1 | H | ISO | N |
| E2E-FEE-027 | On-us gap | both | SA | On-us not modelled | on-us row | Documents finding: phantom interchange on self | P3 | M | N | N |
| E2E-FEE-028 | Regional gap | both | SA | GCC intra-region | one INTERNATIONAL bucket only — documented gap | P3 | L | N | N |
| E2E-FEE-029 | Premium default | both | SA | Unknown subtype→Premium | unknown card_subtype | Defaults to most-expensive Premium — record risk | P3 | M | N | N |
| E2E-FEE-030 | Debit default | both | SA | Generic VISA/MCRD token→DEBIT | card_type=0 tokens | Treated DEBIT — record | P3 | M | N | N |
| E2E-FEE-031 | Shadow-row reseed | both | SA | Restart reverts rate edits | edit a rate, restart | Migration delete-then-insert reverts edit; row count grows — finding | P2 | H | N | N |
| E2E-FEE-032 | Reprice history | both | SA | Re-ingest reprices at current rates | re-ingest old month | Historical rows repriced (pre effective-dating) — document | P3 | M | N | N |
| E2E-FEE-033 | Interchange normalization | TBH | SA | Month restatement preview | /admin/interchange-normalization preview | Weight% 4dp, extra added, Remaining Difference computed | P2 | M | F | P |
| E2E-FEE-034 | Interchange normalization | TBH | SA | Apply requires diff 0.00 | non-zero remaining | Apply blocked until 0.00 | P2 | H | N | N |
| E2E-FEE-035 | Interchange normalization | TBH | SA | Apply rewrites fact + summaries | apply run | [DB] fact fees overwritten; summaries rebuilt; [AUD] INTERCHANGE_NORMALIZATION_APPLY | P2 | H | D | P |
| E2E-FEE-036 | Interchange normalization | TBH | SA | Preserves old txn fee + volume-weighted extra | inspect result | Per memory: keeps old fee, adds volume-weighted extra | P2 | M | D | P |
| E2E-FEE-037 | Interchange guard | TBH | ADMIN | Non-SA blocked | 403 | P2 | H | S | N |
| E2E-FEE-038 | Interchange history | TBH | SA | Versioned runs | View History | Per-run merchant detail available | P3 | L | F | P |
| E2E-FEE-039 | Interchange cancel | TBH | SA | Cancel preview | POST /runs/{id}/cancel | Run cancelled cleanly | P3 | L | F | P |
| E2E-FEE-040 | ECOM flat fee | both | SA | Per-country ecom_flat_fee | AE=0.18, BH/EG unset→0 | Applied per country [DB] | P3 | M | D | P |

## 12. Module: UI Screens vs Database (E2E-UI) — 40 cases

Verify displayed data reconciles to DB per tenant, with isolation. All read-only screens.

| ID | Sub-module | Tenant | Role | Scenario & steps | Expected result & validation | Pri | Sev | Type | P/N |
|---|---|---|---|---|---|---|---|---|---|
| E2E-UI-001 | Transactions | TBH | BU | Grid matches DB (existing E1) | /transactions | Counts/amounts(3dp)/card types = fact_transaction WHERE TBH; no TEG rows [ISO] | P1 | H | U | P |
| E2E-UI-002 | Transactions | TEG | FU | EG grid (existing E2) | 2dp; no TBH rows | P1 | H | U | P |
| E2E-UI-003 | Transactions | TBH | BU | Keyset pagination | page forward/back | Cursor paging; back-stack; no dupes/gaps; no total-count reliance | P2 | M | U | P |
| E2E-UI-004 | Transactions | TBH | ADMIN | CSV export honors filters | apply filter, export | Blob rows = filtered set; PAN masked last-4; ≤100k | P2 | M | F | P |
| E2E-UI-005 | Dashboard | TBH | BU | KPIs per tenant (existing E3) | /dashboard | volume/count = SUM/COUNT per tenant_id; switch refreshes | P1 | H | U | P |
| E2E-UI-006 | Debit/Prepaid | TBH | BU | Split matches DB (existing E4) | /business/debit-prepaid | GROUP BY card_type reconciles | P2 | M | U | P |
| E2E-UI-007 | Cache isolation | TBH→TEG | SA | Rapid switch no residue (existing E5) | switch cycle | All widgets re-fetch; TEG values | P1 | H | ISO | N |
| E2E-UI-008 | Volume/Revenue | TBH | BU | Summary reconciles | /business/volume-revenue | Grid totals = DB | P2 | M | U | P |
| E2E-UI-009 | Merchant financial | TBH | BU | MSF/interchange/net | /business/merchant-financial | Per-merchant values = DB | P2 | M | U | P |
| E2E-UI-010 | Attrition | TBH | BU | MoM/YoY classification | /business/attrition | Classifier clamped to latest data date; complete-month baselines; NEW status; MoM hidden >31d (per memory) | P2 | M | U | P |
| E2E-UI-011 | Attrition meta | TBH | BU | Report+meta in sync | attrition-report-with-meta | Report and meta agree (memory: must stay in sync) | P2 | M | A | P |
| E2E-UI-012 | Retention | TBH | BU | Churn/reactivation | /business/retention | vs prior equal window = DB | P3 | M | U | P |
| E2E-UI-013 | Zero-txn | TBH | BU | Full-set aggregators | /business/zero-transaction | Top-6 reflect full set (not 500 sample); server pagination | P2 | M | U | P |
| E2E-UI-014 | Zero-txn export | TBH | BU | Export honors status filter | export | ≤1000 rows, filter respected | P3 | L | F | P |
| E2E-UI-015 | Daily merchant | TBH | BU | Daily grid | /business/daily-dashboard | Values = sum_daily_merchant; store_id always NULL (documented) | P3 | M | U | P |
| E2E-UI-016 | Merchant analytics | TBH | BU | Server pagination + export | page & export current vs full | Current page vs size=10000 full export both correct | P3 | M | U | P |
| E2E-UI-017 | Group reports | TBH | BU | 4 tabs vs DB | /business/groups MCC/Merchant/Sales/Referral | Metrics on total_base_volume = DB; card filters silently ignored on merchant-backed (documented) | P2 | M | U | P |
| E2E-UI-018 | Finance dashboard | TEG | FU | Margin bridge | /finance/dashboard | Net margin/MSF/interchange/scheme/ecom = DB; Today/MTD/YTD | P2 | M | U | P |
| E2E-UI-019 | Finance summary | TEG | FU | Banded matrix drill | /finance/summary period→day→merchant | Drill values = DB | P2 | M | U | P |
| E2E-UI-020 | Finance lists | TEG | FU | Loss/low-margin tabs | /finance/lists | Rows = loss-making/high-vol-low-margin queries | P2 | M | U | P |
| E2E-UI-021 | Loss-making | TBH | BU | Negative-margin merchants | /business/loss-making | Correct negative-margin set | P2 | M | U | P |
| E2E-UI-022 | Heatmap | TBH | BU | Annual growth | /business/heatmap year | Cells = DB; CSV heatmap_{year} | P3 | L | U | P |
| E2E-UI-023 | Merchant hierarchy | TBH | BU | Tree + pagination | /merchants | merchant→store→terminal; search; resets page on switch | P3 | M | U | P |
| E2E-UI-024 | Merchant summary | TBH | BU | Server-side grid | /merchant-summary | Pagination 20/50/100; export via server endpoint | P3 | L | U | P |
| E2E-UI-025 | Merchant 360 | TBH | BU | Merchant workspace | /merchant/universe | 360 data = DB; some views UnderConstruction placeholder | P3 | L | U | P |
| E2E-UI-026 | Data Explorer | TBH | BU | Summary vs fact grain | /explorer query | Response returns grain; amount-range filter forces fact; dims≤5, limit≤5000 | P2 | M | A | P |
| E2E-UI-027 | Data Explorer | TBH | BU | Export CSV/Excel | export | Files match grid | P3 | L | F | P |
| E2E-UI-028 | Interactive Explorer | TBH | BU | Associative cross-filter | /analytics/interactive | KPI tiles switch metric; cross-filter | P3 | L | U | P |
| E2E-UI-029 | Trends hub | TBH | BU | Daily/monthly/merchants | /trends/hub | Trends = DB | P3 | L | U | P |
| E2E-UI-030 | Saved views | TBH | BU | Save view rules | create view | name required; filterJson≤10KB; max 50/user/tenant; one default/type | P2 | M | F | P |
| E2E-UI-031 | Saved views | TBH | BU | New default clears old | set 2nd default | Only one default per dashboardType | P3 | M | F | P |
| E2E-UI-032 | Saved views | TBH | BU | Shared vs private | shared view | Tenant-visible; private owner-only | P2 | M | ISO | P |
| E2E-UI-033 | Report builder | TBH | BU | Template CRUD + export | /reports templates | Create/export excel/csv works | P3 | M | F | P |
| E2E-UI-034 | Report schedules | TBH | BU | Schedule create/delete | schedule template | Row created; delete works | P3 | L | F | P |
| E2E-UI-035 | AI assistant | TBH | BU | NL query safe | /ai-assistant ask | READ ONLY txn; tenant predicate injected; row cap; UNION blocked; table whitelist | P2 | H | S | P |
| E2E-UI-036 | AI assistant | TBH | BU | Offline provider | Ollama offline | Input disabled with placeholder; graceful | P3 | L | U | N |
| E2E-UI-037 | Forecasting | TBH | BU | Projections | /business/forecasting | 4 grids populate; no crash on sparse data | P3 | L | U | P |
| E2E-UI-038 | Top performers | TBH | BU | Six Top-10 panels | /business/top-performers | Per-panel CSV; leaderboards use total_base_volume | P3 | L | U | P |
| E2E-UI-039 | Opportunity | TBH | BU | Upsell scoring | /business/opportunity | Grid sorted by score; CSV | P3 | L | U | P |
| E2E-UI-040 | No 404 route | any | any | Unknown path renders blank | navigate `/nonexistent` | Documents missing catch-all/404 (UX defect) | P3 | L | N | N |
## 13. Module: PDF / Statement / Report Generation (E2E-PDF) — 35 cases

**Endpoints:** `/api/business/insights/pdf|generate-all|generate-by-mid|check-status|list-reports|download-report|download-all-reports|batch-status/{jobId}`; `/api/external/reports/*` (API-key). UI: `/business/report-manager` (ADMIN+), `/business/emails` (ADMIN+). Output path `reports/<bankShortCode>/<YYYY-MM>/`. Email via email_queue → EmailQueueProcessor (60s).

| ID | Sub-module | Tenant | Role | Scenario & steps | Expected result & validation | Pri | Sev | Type | P/N |
|---|---|---|---|---|---|---|---|---|---|
| E2E-PDF-001 | Generate BH | TBH | ADMIN | Report Manager scope ONE TBH-M001 (existing F1) | TenantConfirmDialog shows Test Bank Bahrain/TBH/Bahrain/BHD; check-status; generate; PDF under reports/TBH/2026-07/; batch SUCCESS | P1 | C | F | P |
| E2E-PDF-002 | BH content | TBH | ADMIN | PDF correctness (existing F2) | [PDF] cover: merchant name, period, BHD, sales/txn = DB (3dp); card split=GROUP BY card_type; local/intl split=DOMESTIC/INTL counts; avg ticket=DB | P1 | H | PDF | P |
| E2E-PDF-003 | Generate EG | TEG | ADMIN | TEG-M001 (existing F3) | PDF under reports/TEG/2026-07/, EGP | P1 | C | F | P |
| E2E-PDF-004 | EG content | TEG | ADMIN | EGP 2dp (existing F4) | [PDF] EGP amounts 2dp; Meeza rows if present | P1 | H | PDF | P |
| E2E-PDF-005 | Cross-tenant ISO | both | ADMIN | PDF isolation (existing F5) | [PDF] BH PDF has zero EGP/TEG names/totals and inverse; totals reconcile exactly to that tenant's DB | P1 | C | ISO | N |
| E2E-PDF-006 | Folder ISO | TEG | ADMIN | list/download not serving TBH (existing F6) | Under TEG, list-reports/download-all exclude TBH; watch shared-folder fallback :1150-1158 | P1 | H | ISO | N |
| E2E-PDF-007 | Regen overwrite | TBH | ADMIN | Re-run same month (existing F7) | check-status → overwrite confirm; no dup/mix | P2 | M | F | P |
| E2E-PDF-008 | Scope ALL | TBH | ADMIN | Generate all merchants | generate-all | Only merchants with generate_report_flag=1; batch progress polled 2s | P2 | M | F | P |
| E2E-PDF-009 | Scope FILE | TBH | ADMIN | MID-list upload | CSV/TXT of MIDs (accept .csv,.txt,.tsv) | Matched MIDs generated; unmatched reported back | P2 | M | F | P |
| E2E-PDF-010 | Scope ONE validation | TBH | ADMIN | ONE requires MID | blank MID | Confirm dialog won't open | P3 | L | N | N |
| E2E-PDF-011 | Scope FILE validation | TBH | ADMIN | FILE requires file | no file | Blocked | P3 | L | N | N |
| E2E-PDF-012 | Flag block | TBH | ADMIN | Merchant without report flag | generate for flag=0 mid | Blocked in both ALL and by-mid modes | P2 | M | N | N |
| E2E-PDF-013 | IDOR block | TBH | ADMIN | PDF for TEG merchant id | GET /insights/pdf?merchantId={teg} | 403 (tenantId passed to coreClient) | P1 | C | S | N |
| E2E-PDF-014 | Null tenant | any | ADMIN | PDF with null TenantContext | force null context | Fails closed 403 | P2 | H | S | N |
| E2E-PDF-015 | Single PDF | TBH | ADMIN | Download single | download-report | Blob, filename from Content-Disposition | P3 | L | F | P |
| E2E-PDF-016 | Download all | TBH | ADMIN | Zip download | download-all-reports | Merchant_Reports_TBH.zip; only TBH files | P2 | H | ISO | N |
| E2E-PDF-017 | Engine not ready | TBH | ADMIN | Playwright not loaded | PDF_ENGINE_NOT_READY | UI shows remediation hint; no crash | P3 | M | N | N |
| E2E-PDF-018 | Module not loaded | TBH | ADMIN | PDF_MODULE_NOT_LOADED | handled with hint | P3 | L | N | N |
| E2E-PDF-019 | Batch status | TBH | ADMIN | Progress telemetry | batch-status/{jobId} | completed/total, succeeded/failed, avg ms, ETA | P3 | L | A | P |
| E2E-PDF-020 | Batch cancel | TBH | ADMIN | Cancel batch | batch-cancel | Job stops cleanly | P3 | L | F | P |
| E2E-PDF-021 | Currency in PDF | TEG | ADMIN | Correct symbol/decimals | EG PDF | EGP symbol, 2dp; no AED fallback | P2 | H | PDF | P |
| E2E-PDF-022 | Logo in PDF | TBH | ADMIN | Per-tenant logo | BH PDF | Correct bank logo (base64-injected) | P3 | L | PDF | P |
| E2E-PDF-023 | Precision chain | TBH | ADMIN | Amount precision fact→PDF | 450.755 BHD | [PDF] shows 450.755 (3dp) end-to-end | P1 | H | PDF | P |
| E2E-PDF-024 | Statement email | TBH | ADMIN | Bulk send | /business/emails send-bulk?month= | window.confirm; email_queue rows PENDING; EmailQueueProcessor sends within ~60s | P2 | M | I | P |
| E2E-PDF-025 | Statement email retry | TBH | ADMIN | Retry failed row | per-row Retry | send/{merchantId}?month= re-queued | P3 | L | F | P |
| E2E-PDF-026 | Email template | TBH | ADMIN | REPORT_PDF template used | check subject/body | From tenant REPORT_PDF template; vars merchant_name/mid/month_year resolved | P3 | M | I | P |
| E2E-PDF-027 | Email logs ISO | TEG | ADMIN | Logs scoped | /email/logs?month= | Only TEG merchant emails | P2 | M | ISO | N |
| E2E-PDF-028 | SMTP config | TBH | ADMIN | Active SMTP resolves | send with active config | AES-decrypted password used; __UNCHANGED__ preserves stored secret on edit | P2 | M | I | P |
| E2E-PDF-029 | SMTP test | TBH | ADMIN | Test config probe | POST smtp-configs/{id}/test | Connectivity result surfaced | P3 | L | F | P |
| E2E-PDF-030 | External report API | ext | key | API-key report list | GET /api/external/reports/list with X-API-Key | Only that key's tenant reports; scope enforced | P2 | H | A | P |
| E2E-PDF-031 | External report API neg | ext | none | No API key | omit header | 401 | P2 | H | S | N |
| E2E-PDF-032 | Single merchant PDF | TBH | BU | MerchantInsights pdf download | /business/insights/pdf?year=&month= | Blob Merchant_Insight_{y}_{m}.pdf; scoped to tenant | P3 | M | F | P |
| E2E-PDF-033 | PDF reconciliation | TBH | ADMIN | Totals reconcile to DB exactly | compare PDF totals to fact Σ | Any excess = leakage; must match to the fils | P1 | H | PDF | N |
| E2E-PDF-034 | Refund in PDF | TBH | ADMIN | Refund reflected | merchant with refund row | Net volume reduced correctly in PDF | P3 | M | PDF | P |
| E2E-PDF-035 | Campaign PDF attach | TBH | ADMIN | EmailCampaign REPORT_PDF | campaign launch | Draft until launch; PDF attached; retry-failed works | P3 | L | I | P |

## 14. Module: Security & API Access (E2E-SEC) — 49 cases

Security-audit-driven. Many document known findings (record actual, don't assume fixed).

### 14a. Auth/session/config hardening

| ID | Sub-module | Tenant | Role | Scenario & steps | Expected result & validation | Pri | Sev | Type | P/N |
|---|---|---|---|---|---|---|---|---|---|
| E2E-SEC-001 | Seed reset (C-1) | n/a | n/a | Restart backend, try admin/password | If login succeeds → confirms schema re-seed resets admin (prod-critical); [DB] users admin role/pw reset | P1 | C | S | N |
| E2E-SEC-002 | JWT secret default (L-1) | n/a | n/a | Check jwt.secret not default in prod config | Default `AcquiraDefaultDevKeyAtLeast32Chars!!` must not be active in prod | P1 | C | S | N |
| E2E-SEC-003 | Token after logout | any | any | Replay Bearer post-logout | Token valid till exp (no server logout) — record gap | P2 | H | S | N |
| E2E-SEC-004 | Refresh reuse | any | any | Reuse rotated refresh | 401 + revoke-all (covered LOGIN-018, cross-ref) | P1 | H | S | N |
| E2E-SEC-005 | Password in transit | any | any | No TLS in shipped path (M-1) | HSTS emitted over plain HTTP — record | P2 | H | S | N |
| E2E-SEC-006 | CSP absent (H-4) | any | any | Inspect response headers | No CSP anywhere — record finding | P2 | H | S | N |
| E2E-SEC-007 | Token in localStorage (H-4) | any | any | Inspect storage | JWT+refresh in localStorage (XSS-exposed) — record | P2 | H | S | N |
| E2E-SEC-008 | Stored XSS campaign (H-4) | TBH | ADMIN | Campaign template body `<img onerror>` | EmailCampaignHub dangerouslySetInnerHTML sink — verify sanitization; record if executes | P1 | H | S | N |
| E2E-SEC-009 | XSS user fields | TBH | SA | `<script>` in display name | Not executed on user list render | P2 | H | S | N |

### 14b. RBAC/authorization gaps (M-7 sweep)

| ID | Sub-module | Tenant | Role | Scenario & steps | Expected result | Pri | Sev | Type | P/N |
|---|---|---|---|---|---|---|---|---|---|
| E2E-SEC-010 | Priv sweep | TBH | BU | ROLE_USER → ReportBuilder delete | DELETE /api/reports/templates/{id} | Should 403; currently open — record M-7 | P1 | H | S | N |
| E2E-SEC-011 | Priv sweep | TBH | BU | ROLE_USER → DataExplorer | POST /api/explorer/query | Record if reachable | P1 | H | S | N |
| E2E-SEC-012 | Priv sweep | TBH | BU | ROLE_USER → AnalyticsExplorer | POST /analytics/explorer/query | Record | P1 | H | S | N |
| E2E-SEC-013 | Priv sweep | TBH | BU | ROLE_USER → Finance | GET /api/finance/summary | Record (net revenue exposure) | P1 | H | S | N |
| E2E-SEC-014 | Priv sweep | TBH | BU | ROLE_USER → Executive | GET /api/analytics/executive | Record | P2 | H | S | N |
| E2E-SEC-015 | Priv sweep | TBH | BU | ROLE_USER → RevenueKpi | POST /api/business/revenue-kpis | Record | P2 | H | S | N |
| E2E-SEC-016 | Priv sweep | TBH | BU | ROLE_USER → GroupAnalytics | POST /api/group-analytics/MERCHANT/filtered | Record; verify still tenant-scoped | P2 | M | S | N |
| E2E-SEC-017 | Priv sweep positive | TBH | BU | ROLE_USER → admin endpoints blocked | GET /api/admin/settings | 403 (URL rule works) | P1 | H | S | P |
| E2E-SEC-018 | Menu enforcement | TBH | BU | Menu-only screen API reachable | any menu-gated endpoint | @menuAccess never enforced — record | P2 | H | S | N |

### 14c. PAN / data exposure (H-2)

| ID | Sub-module | Tenant | Role | Scenario & steps | Expected result | Pri | Sev | Type | P/N |
|---|---|---|---|---|---|---|---|---|---|
| E2E-SEC-020 | PAN cleartext | all | SA | Query fact_transaction card_number | [DB] PANs stored cleartext — record H-2 | P1 | H | S | N |
| E2E-SEC-021 | PAN in explorer | TBH | BU | AnalyticsExplorer "Card" dimension | POST group by card_number | Full PAN exposed (no masking in explorer path) — record | P1 | H | S | N |
| E2E-SEC-022 | PAN in summary | all | SA | sum_monthly_card.card_number, top_spending_customer_id | [DB] cleartext PAN copied to summaries — record | P1 | H | S | N |
| E2E-SEC-023 | PAN masked in txn UI | TBH | BU | Transactions grid | Only last-4 shown (masking applied in TransactionController path) | P2 | M | F | P |
| E2E-SEC-024 | PAN masked in export | TBH | ADMIN | CSV export | Masked last-4 in file | P2 | H | F | P |

### 14d. External API keys (API audit AT-1..AT-10)

| ID | Sub-module | Tenant | Role | Scenario & steps | Expected result | Pri | Sev | Type | P/N |
|---|---|---|---|---|---|---|---|---|---|
| E2E-SEC-030 | XFF spoof rate limit (H-1) | n/a | n/a | Send X-Forwarded-For spoofed first hop | Rate limit bypassable — record H-1 | P1 | H | S | N |
| E2E-SEC-031 | XFF spoof API-key IP allowlist | ext | key | Forge XFF to match allowlist | IP allowlist decorative — record | P1 | H | S | N |
| E2E-SEC-032 | XFF audit IP | any | any | Spoofed IP in audit_log | Attacker-chosen IP recorded — record | P2 | M | S | N |
| E2E-SEC-033 | Key create once | TBH | ADMIN | Create API key | Raw key shown once; only BCrypt hash stored; GET returns prefixes | P2 | M | F | P |
| E2E-SEC-034 | Key scope required | TBH | ADMIN | Create key no scope | "Select at least one permission" | P3 | M | N | N |
| E2E-SEC-035 | Key auth | ext | key | Valid key on /api/v1/transactions | 200 within scope; startDate/endDate required, ≤92d | P2 | M | A | P |
| E2E-SEC-036 | Key expiry | ext | key | Expired key | 401 | P2 | M | S | N |
| E2E-SEC-037 | Key rate limit | ext | key | Exceed per-key limit | 429 | P2 | M | S | N |
| E2E-SEC-038 | AT-5 merchant enum | ext | key | /merchants/{other_mid}/summary | Currently returns other merchant (key=bank scope) — record AT-5 | P1 | C | S | N |
| E2E-SEC-039 | AT-8 bank-wide aggregate | ext | key | /api/v1/analytics/volume, /finance/summary | Bank-wide net revenue reachable by key — record AT-8 | P1 | H | S | N |
| E2E-SEC-040 | Key rotation absent | TBH | ADMIN | Attempt rotate | No rotation endpoint (revoke+create = outage) — record | P3 | M | N | N |
| E2E-SEC-041 | Immortal key | TBH | ADMIN | Create key blank expiry | expires_at null → never expires — record | P3 | M | N | N |
| E2E-SEC-042 | IP allowlist CIDR | ext | key | Set 10.0.0.0/8 | Exact-string match never matches CIDR — record | P3 | M | N | N |
| E2E-SEC-043 | Rejected before recordUsage | ext | key | Brute force bad keys | Rejected requests not logged (invisible brute force) — record | P2 | M | S | N |
| E2E-SEC-044 | Scope corruption | TBH | ADMIN | Scope with `,`/`"` | Hand-split TEXT corrupts row — record | P3 | M | N | N |
| E2E-SEC-045 | permitAll prefix leak | ext | none | `//api/v1/...`, encoded segments | shouldNotFilter URI mismatch → unauth leak — record | P1 | H | S | N |
| E2E-SEC-046 | Break-glass key (M-4) | n/a | n/a | Static all-tenant key | All-tenant, no scopes, MAX rate — verify off by default | P2 | H | S | N |
| E2E-SEC-047 | Encryption key fallback | n/a | n/a | app.encryption.key unset | Falls back to hardcoded key encrypting bank passwords — record OQ-2 | P1 | C | S | N |
| E2E-SEC-048 | Integration SQL abuse | TBH | ADMIN | Arbitrary SELECT in integration_report | No column allowlist/approval — record | P2 | H | S | N |
| E2E-SEC-049 | MSSQL trustServerCert | n/a | SA | Bank JDBC link | trustServerCert defaults true (MITM) — record | P2 | H | S | N |
| E2E-SEC-050 | AI SQL red-team | TBH | BU | Injection via /ai/ask | READ ONLY + tenant predicate + UNION block + whitelist hold; no cross-tenant/write | P1 | H | S | N |
| E2E-SEC-051 | AI SQL row cap | TBH | BU | Huge result request | setMaxRows enforced | P2 | M | S | P |
| E2E-SEC-052 | Backup path traversal | n/a | SA | Restore `../../etc/passwd` | 400 IllegalArgumentException (H6 guard holds) | P1 | H | S | N |
| E2E-SEC-053 | Migration rate limit | n/a | SA | Hammer /api/admin/migration/* | 5/min limit (bypassable via XFF — cross-ref H-1) | P3 | M | S | N |
| E2E-SEC-054 | Audit completeness | TBH | ADMIN | Sensitive actions audited | perform create/update/delete | audit_log rows for all POST/PUT/DELETE + exports | P2 | M | AUD | P |
| E2E-SEC-055 | Tracked secrets in git | n/a | n/a | Repo hygiene (H-5) | acq-congif.txt, sample data, PDFs tracked — record | P3 | L | S | N |
## 15. Module: Admin, Settings & Platform Ops (E2E-ADMIN) — 40 cases

Covers SettingsHub panels, SMTP/S3/SSO/Alerts/Budget/Maintenance/Backup/Migration/Integration/Audit/Regional/API-management screens.

| ID | Sub-module | Tenant | Role | Scenario & steps | Expected result & validation | Pri | Sev | Type | P/N |
|---|---|---|---|---|---|---|---|---|---|
| E2E-ADMIN-001 | Security Settings | TBH | ADMIN | Save password policy | /admin/security-settings | GET/PUT /admin/settings; composition rules persist; [AUD] UPDATE_SECURITY_POLICY | P2 | M | F | P |
| E2E-ADMIN-002 | Security Settings | TBH | ADMIN | Lockout/rate-limit config | set max failed=5, lockout=15m | Values drive LOGIN-008; live from SecurityPolicyService | P2 | M | F | P |
| E2E-ADMIN-003 | Security Settings | TBH | ADMIN | Session timeout config | set session_timeout_minutes | Feeds idle logout (LOGIN-026) | P2 | M | F | P |
| E2E-ADMIN-004 | Security Settings | TBH | ADMIN | Enforcement-pending honesty | MFA/IP allowlist/API-key cards | Labeled "enforcement pending"; no false claim (TC-F9) | P2 | H | U | P |
| E2E-ADMIN-005 | Security Settings | TBH | ADMIN | Revoke all sessions | button | POST revoke-all-sessions; toasts if unavailable | P3 | M | F | P |
| E2E-ADMIN-006 | Security Settings | TBH | ADMIN | Locked users panel + unlock | list + unlock | GET locked-users; per-user unlock | P3 | L | F | P |
| E2E-ADMIN-007 | SMTP | TBH | ADMIN | Create/edit SMTP config | host/port required | Saved; secret AES-encrypted; edit placeholder "leave blank to keep current" | P2 | M | F | P |
| E2E-ADMIN-008 | SMTP | TBH | ADMIN | Secret sentinel | save without retyping password | __UNCHANGED__ preserves stored secret (not blanked) | P2 | H | F | P |
| E2E-ADMIN-009 | SMTP | TBH | ADMIN | Activate one config | activate | Only one active per tenant | P3 | L | F | P |
| E2E-ADMIN-010 | SMTP | TBH | ADMIN | Test config | test button | Probe result surfaced | P3 | L | F | P |
| E2E-ADMIN-011 | S3 | TBH | ADMIN | S3 archiving config | region/key/bucket | Saved AES-256; test connection | P3 | M | F | P |
| E2E-ADMIN-012 | S3 | TBH | ADMIN | Secret masked/preserved | edit without retype | Stored secret preserved | P3 | M | F | P |
| E2E-ADMIN-013 | SSO | TBH | ADMIN | Entra config required fields | client id/secret required | Toggle gates form; redirect URI read-only+copy | P3 | M | F | P |
| E2E-ADMIN-014 | SSO | TBH | ADMIN | Email domain routing | comma-separated domains | Per-bank SSO routing saved | P3 | L | F | P |
| E2E-ADMIN-015 | Alerts | TBH | ADMIN | Create alert rule | name required, metric/operator/threshold | Rule saved; [AUD] CREATE_ALERT_RULE | P3 | M | F | P |
| E2E-ADMIN-016 | Alerts | TBH | ADMIN | Recipients validation | comma-separated emails | Accepted; history table populates | P4 | L | F | P |
| E2E-ADMIN-017 | Alerts | TBH | ADMIN | Delete rule | delete | [AUD] DELETE_ALERT_RULE | P4 | L | F | P |
| E2E-ADMIN-018 | Budget | TBH | ADMIN | Monthly target YYYYMM validation | `202607` vs `2026-7` | Regex enforced "Month must be YYYYMM" | P3 | M | N | N |
| E2E-ADMIN-019 | Budget | TBH | ADMIN | Non-negative target | negative value | Rejected | P3 | M | N | N |
| E2E-ADMIN-020 | Budget | TBH | ADMIN | Annual phasing writes 12 rows | seasonal/equal | 12 monthly rows created; all validated | P3 | M | F | P |
| E2E-ADMIN-021 | Budget | TBH | ADMIN | Attainment pro-rated | view attainment | Pro-rated for days elapsed; variance/status | P3 | L | F | P |
| E2E-ADMIN-022 | Budget guard | TBH | BU | ROLE_USER write blocked | POST /budget/targets | 403 (method ADMIN+) | P2 | M | S | N |
| E2E-ADMIN-023 | Maintenance | n/a | SA | Save config (single row) | /admin/maintenance config | id=1 row; server local time window | P2 | M | F | P |
| E2E-ADMIN-024 | Maintenance | n/a | SA | Empty window guard | start=end | "Window is empty", never auto-runs | P3 | M | N | N |
| E2E-ADMIN-025 | Maintenance | n/a | SA | Run now idle guard | run while batch active | "Skipped — A batch job is currently running"; overridable ?force=true&overrideBatch=true | P2 | M | N | N |
| E2E-ADMIN-026 | Maintenance | n/a | ADMIN | ADMIN cannot save/run | U-BA-BH PUT config | 403 "Only a Super Admin…"; GET status allowed | P2 | M | S | N |
| E2E-ADMIN-027 | Maintenance | n/a | SA | Bad table identifier | `x; DROP TABLE y` | "invalid identifier", skipped, scheduler safe | P2 | H | S | N |
| E2E-ADMIN-028 | Backup | n/a | SA | Create backup | POST create | Success; file listed | P2 | M | F | P |
| E2E-ADMIN-029 | Backup | n/a | SA | Filename regex guard | `../../etc/passwd`, `a.exe` | 400 IllegalArgumentException (H6) | P1 | H | S | N |
| E2E-ADMIN-030 | Backup | n/a | SA | Restore two-confirm | restore | Two window.confirm; exit0=success, 1=warnings | P2 | M | F | P |
| E2E-ADMIN-031 | Backup guard | n/a | ADMIN | ADMIN blocked | class SUPER_ADMIN | 403 | P2 | H | S | N |
| E2E-ADMIN-032 | Migration | TBH | SA | Dry run read-only | dry-run legacy_transactions | Total rows/date range/columns; no writes | P3 | M | F | P |
| E2E-ADMIN-033 | Migration | TBH | SA | Column mapping required targets | omit mid/payment_date/amount | Blocked (3 required) | P3 | M | N | N |
| E2E-ADMIN-034 | Migration | TBH | SA | Delete-a-day two-step | delete-day, active tenant | Arm-then-confirm; per-table rows removed; scoped to active tenant; [AUD] DELETE_DAY | P2 | H | F | P |
| E2E-ADMIN-035 | Migration | TBH | SA | Rebuild summaries mirrors ingest | rebuild-summaries | [DB] rebuilt months match TransactionJobConfig.populateSummary columns (memory: drift risk); [AUD] SUMMARY_REBUILD | P2 | H | D | P |
| E2E-ADMIN-036 | Integration | TBH | ADMIN | Connection secret unchanged sentinel | edit connection | `•••• (unchanged)` preserves password | P3 | M | F | P |
| E2E-ADMIN-037 | Integration | TBH | ADMIN | Report column-mapping JSON validation | bad JSON | Client "not valid JSON" | P3 | L | N | N |
| E2E-ADMIN-038 | Audit viewer | TBH | ADMIN | Filter + export CSV | filter by category/date | Rows correct; CSV export (GET /export audited) | P3 | M | F | P |
| E2E-ADMIN-039 | Regional | TBH | ADMIN | Date/timezone/load-mode prefs | set date format | Applies after refresh; load mode from next upload | P3 | L | F | P |
| E2E-ADMIN-040 | Connections regression | n/a | SA | No idle-in-transaction leak | after batch | [DB] pg_stat_activity idle-in-transaction=0; Hikari pool ≤30 | P2 | H | D | P |

## 16. Module: Complete Business E2E Flows (E2E-FLOW) — 25 cases

Full journeys: Login → Access → Tenant → Transaction → BIN/Product → Processing → Save → DB verify → PDF → PDF validate → Logout. Each flow chains multiple modules.

| ID | Flow | Tenant | Role | Scenario | Expected end-to-end result | Pri | Sev | Type | P/N |
|---|---|---|---|---|---|---|---|---|---|
| E2E-FLOW-001 | Happy path BH | TBH | SA | Login→create TBH→upload BH file→verify DB→dashboard→generate PDF→validate→logout | Every stage passes; PDF totals=DB (3dp, BHD); no TEG leakage | P1 | C | I | P |
| E2E-FLOW-002 | Happy path EG | TEG | SA | Same for Egypt | EGP 2dp; PDF=DB; no TBH leakage | P1 | C | I | P |
| E2E-FLOW-003 | Bank-admin scoped flow | TBH | BA | U-BA-BH login→upload TBH→view→PDF | Works within TBH; cannot touch TEG anywhere | P1 | H | I | P |
| E2E-FLOW-004 | Multi-tenant switch flow | multi | OPS | Login→TBH work→switch TEG→work→switch back | Each tenant's data isolated; no residue on switch | P1 | C | ISO | N |
| E2E-FLOW-005 | Permission-failure flow | TBH | BU | Login→attempt upload/PDF/admin | Each blocked (403/redirect); business reports still work | P1 | H | S | N |
| E2E-FLOW-006 | Validation-failure flow | TBH | SA | Upload file with bad rows/unknown BIN/unmapped dest | Batch completes; bad rows flagged, not crashed; statuses set | P2 | H | N | N |
| E2E-FLOW-007 | Re-login flow | TBH | BA | Work→logout→re-login→resume | State clean; correct tenant on return | P2 | M | F | P |
| E2E-FLOW-008 | Password-expiry flow | TBH | BU | Login→forced change→continue | mustChangePassword gate → change → full access | P2 | H | F | P |
| E2E-FLOW-009 | Forgot-password flow | n/a | BU | Forgot→OTP→reset→login | Full OTP journey; sessions revoked; new pw works | P2 | H | F | P |
| E2E-FLOW-010 | Refund flow | TBH | SA | Ingest refund→DB→PDF | Negative volume/MSF; zero interchange; PDF net reduced | P2 | M | I | P |
| E2E-FLOW-011 | Interrupted upload | TBH | ADMIN | Kill backend mid-upload→restart→retry | Clear error; retry succeeds; no partial corruption (REPLACE idempotent) | P2 | H | N | N |
| E2E-FLOW-012 | Duplicate submission | TBH | ADMIN | Re-submit same file | REPLACE idempotent; APPEND double-counts (documented) | P2 | H | N | N |
| E2E-FLOW-013 | Browser back nav | TBH | BA | Deep in flow→browser Back | No stale cross-tenant data; session intact | P2 | M | S | N |
| E2E-FLOW-014 | Browser refresh mid-flow | TBH | BA | F5 during report | Same tenant/page after session validate | P2 | M | F | P |
| E2E-FLOW-015 | Tenant switch mid-flow | multi | OPS | Switch tenant during report generation | Report re-scopes; no mixed-tenant output | P1 | H | ISO | N |
| E2E-FLOW-016 | Retry after failure | TBH | ADMIN | PDF engine not ready→retry after ready | Second attempt succeeds | P3 | M | N | N |
| E2E-FLOW-017 | Integration flow | TBH | SA | Configure connection→pull→dbPull job→verify | Pulled data ingested via dbPullTransactionJob; scoped | P3 | M | I | P |
| E2E-FLOW-018 | Data-consistency flow | TBH | SA | Submitted vs DB vs UI vs PDF vs CSV | All four representations reconcile (450.755 chain) | P1 | H | I | N |
| E2E-FLOW-019 | Cross-tenant recovery | multi | OPS | Error on TEG→switch TBH | TBH unaffected; TEG error contained | P2 | M | N | N |
| E2E-FLOW-020 | Boundary-value flow | TBH | SA | Ingest 99.999/0.001/1.005/450.755→PDF | Exact precision at every layer; no rounding drift | P1 | H | B | P |
| E2E-FLOW-021 | Split-day flow | TBH | SA | Upload two same-date files | Second deletes first's rows (data loss) — documented risk | P1 | H | N | N |
| E2E-FLOW-022 | Restart-repricing flow | both | SA | Ingest→restart→re-ingest | Rates reverted by reseed; history repriced — documented | P2 | H | N | N |
| E2E-FLOW-023 | End-to-end isolation audit | multi | SA | Full ingest both tenants→DB sweep→PDF isolation | Zero cross-tenant rows anywhere; PDFs reconcile per tenant | P1 | C | ISO | N |
| E2E-FLOW-024 | Report-manager full | TBH | ADMIN | Scope FILE MID list→generate→email→download-all | Matched MIDs generated; emailed via queue; zip tenant-only | P2 | M | I | P |
| E2E-FLOW-025 | Attrition consistency flow | TBH | BU | Ingest 2 months→attrition report+meta+PDF | MoM/YoY correct; report and meta in sync; window clamped to latest date | P2 | M | I | P |
## 17. Test Coverage Matrix (Requirement → Cases → Tenant → Role → P/N → Status)

Every documented requirement mapped to ≥1 case. Status column filled at execution.

| # | Requirement / Module | Case IDs | Tenant(s) | Role(s) | P/N mix | Status |
|---|---|---|---|---|---|---|
| R1 | Login / auth / generic-401 anti-enumeration | LOGIN-001..007, 014 | n/a | SA/all | P+N | ☐ |
| R2 | Lockout + rate limiting (5/15m, 10/60s) | LOGIN-008..013 | n/a | all | N | ☐ |
| R3 | Session: refresh rotation, reuse detection, cookie, idle timeout, logout | LOGIN-016..029, 055 | any | all | P+N | ☐ |
| R4 | Password change / strength / history / forced-change | LOGIN-030..036, USER-025..026, 049 | any | all | P+N | ☐ |
| R5 | Forgot-password OTP (6-digit, BCrypt, 10m, 5-try, ticket) | LOGIN-037..046, FLOW-009 | n/a | all | P+N | ☐ |
| R6 | SSO (Entra), request-access, enumeration oracle | LOGIN-047..049 | n/a | all | P+N | ☐ |
| R7 | Login after account/role/tenant change | LOGIN-050..054, USER-023, 032, 057 | multi | all | P+N | ☐ |
| R8 | User CRUD + validation (dup, email, required) | USER-001..020, 055..060 | TBH/TEG | SA/BA | P+N | ☐ |
| R9 | Activate/deactivate, reset pw, unlock | USER-021..028 | TBH | SA | P+N | ☐ |
| R10 | Assign/change role & tenant, multi/single tenant | USER-029..034, 047..048 | multi | SA | P+N | ☐ |
| R11 | User list/search/filter/sort/pagination/export | USER-035..042 | TBH | SA/BA | P | ☐ |
| R12 | Access requests approve/reject | USER-043..046 | TBH | SA/BA | P+N | ☐ |
| R13 | User-management RBAC + IDOR | USER-050..054, RBAC-004 | TBH | BU/BA | N | ☐ |
| R14 | Role-based access control (routes + API) | RBAC-006..019, 027..044 | all | all | P+N | ☐ |
| R15 | Unauthorized access / privilege escalation | RBAC-020..026, 029..040, SEC-010..018 | TBH | BU/BA | N | ☐ |
| R16 | Group/menu management | RBAC-001..005, 045 | n/a | SA/ADMIN | P+N | ☐ |
| R17 | Tenant provisioning & config (BH/EG) | TENANT-001..018 | TBH/TEG | SA | P+N | ☐ |
| R18 | Currency decimals per tenant (BHD 3 / EGP 2) | TENANT-011, INGEST-014..016, PDF-021, 023 | BH/EG | SA | P | ☐ |
| R19 | Tenant switching refreshes all data | TENANT-020..029, UI-007 | TBH↔TEG | SA/OPS | P+N | ☐ |
| R20 | Tenant isolation (header/IDOR/query/DB/PDF/report) | TENANT-030..070, PDF-005..006, 016, FEE-026 | all | all | N | ☐ |
| R21 | Cross-tenant security (URL/ID/payload/nav) | TENANT-034..044, 062, SEC-038 | TBH/TEG | BA | N | ☐ |
| R22 | RLS reality / fail-open | TENANT-049, 062, SEC-020 | all | SA | N | ☐ |
| R23 | BIN upload/validation/config | BIN-001..029, 045..055 | n/a | SA | P+N | ☐ |
| R24 | Card type from BIN (first-6) — requirement | BIN-030..037, existing C3/C4/D2 | TBH/TEG | SA | P(FAIL) | ☐ |
| R25 | Local/non-local from destination token | INGEST-019..022, existing C6..C8 | TBH/TEG | SA | P+N | ☐ |
| R26 | Product type mapping (config table, not hardcoded) | BIN-033..035, 047..051, existing D1..D3 | both | SA | P+N | ☐ |
| R27 | Invalid/short/long/non-numeric card | BIN-040..044, existing C10 | TBH | SA | N | ☐ |
| R28 | Ingestion / entity-name resolution | INGEST-001..013, 033..045 | TBH/TEG | SA/ADMIN | P+N | ☐ |
| R29 | File format validation & upload security | INGEST-008..011, 034..035, 043 | TBH | ADMIN | N | ☐ |
| R30 | Duplicate/split-day/append data integrity | INGEST-030..032, FLOW-012, 021 | TBH | SA | N | ☐ |
| R31 | Fee/interchange correctness (golden values) | FEE-001..025, 040 | TBH/TEG | SA | P | ☐ |
| R32 | Fee negative statuses (no silent fallback) | FEE-011..015, 026..032 | both | SA | N | ☐ |
| R33 | Interchange normalization | FEE-033..039 | TBH | SA | P+N | ☐ |
| R34 | Rollup reconciliation (fact Σ = summaries) | FEE-021..023, PDF-033, ADMIN-035 | all | SA | P | ☐ |
| R35 | UI vs DB reconciliation | UI-001..029, 037..039 | TBH/TEG | BU/FU | P | ☐ |
| R36 | Saved views / report builder rules | UI-030..034 | TBH | BU | P | ☐ |
| R37 | Data Explorer grain / AI SQL safety | UI-026, 035..036, SEC-050..051 | TBH | BU | P+N | ☐ |
| R38 | PDF generation (BH/EG) | PDF-001..004, 008..012, 032 | TBH/TEG | ADMIN | P+N | ☐ |
| R39 | PDF content & precision correctness | PDF-002, 004, 021..023, 033..034 | both | ADMIN | P+N | ☐ |
| R40 | PDF/report tenant isolation | PDF-005..006, 013..014, 016, 027 | both | ADMIN | N | ☐ |
| R41 | Statement email / SMTP / queue | PDF-024..029, 035, ADMIN-007..010 | TBH/TEG | ADMIN | P | ☐ |
| R42 | External report/data API (keys, scopes) | PDF-030..031, SEC-030..049 | ext | key | P+N | ☐ |
| R43 | Security hardening (seed reset, JWT, CSP, XSS, PAN) | SEC-001..009, 020..024, 052..055 | all | all/SA | N | ☐ |
| R44 | Admin/settings panels | ADMIN-001..039 | TBH | ADMIN/SA | P+N | ☐ |
| R45 | Maintenance/backup/migration ops | ADMIN-023..035, 040 | n/a | SA/ADMIN | P+N | ☐ |
| R46 | Audit logging coverage | SEC-054, TENANT-061, ADMIN-038 | TBH | ADMIN | P | ☐ |
| R47 | Complete E2E business flows | FLOW-001..025 | all | all | P+N | ☐ |
| R48 | Boundary values (99.999/0.001/1.005/450.755) | INGEST-016, FEE-020, PDF-023, FLOW-020 | TBH | SA | B | ☐ |
| R49 | Recovery / interrupted / retry / browser nav | FLOW-011..016, 019 | TBH/multi | all | N | ☐ |
| R50 | Attrition/retention window semantics | UI-010..012, FLOW-025 | TBH | BU | P | ☐ |

## 18. Execution Tracker (per-case, filled during execution)

For each case ID record: **Actual Result · Pass/Fail/Blocked · Defect ID · Evidence ref · Remarks.** Maintain as a spreadsheet keyed by ID. Template row:

`E2E-LOGIN-001 | <observed> | PASS/FAIL/BLOCKED | DEF-### | screenshot/log/SQL ref | notes`

## 19. Recommended Execution Order

1. **Environment gate:** backend `:8081` + frontend `:5173` up; clean schema; `admin` login (LOGIN-001). If backend restarted, recreate tenants.
2. **Auth core:** LOGIN-001..049 (defer OTP mail-dependent to when SMTP/dev-log available).
3. **Tenant provisioning:** TENANT-001..018 → creates TBH/TEG (blocks most downstream).
4. **User & RBAC setup:** USER-001..005 (create test users) → USER/RBAC remainder.
5. **BIN config:** BIN-001..029 (upload BIN_TEST_SET.csv).
6. **Ingestion (sequential):** INGEST-001 (BH) → INGEST-002 (EG) → INGEST remainder.
7. **Fee validation:** FEE-001..040 (needs ingested data).
8. **UI vs DB:** UI-001..040.
9. **PDF:** PDF-001..035.
10. **Isolation sweep:** TENANT-030..070, PDF-005..006.
11. **Security:** SEC-001..055 (SEC-001 seed-reset last — requires restart).
12. **Admin/ops:** ADMIN-001..040.
13. **Full flows:** FLOW-001..025 (integration capstone).

## 20. Missing Data / Blockers / Prerequisites (must resolve before or during execution)

| # | Item | Why needed | Blocks | Action owner |
|---|---|---|---|---|
| M1 | **`ref_bin` is empty** — manual BIN table has no rows | BIN-source-of-truth cases (BIN-030..037) and any test that expects ref_bin lookup | R24 partial | Tester uploads `BIN_TEST_SET.csv` (8 fixtures, §3) |
| M2 | **BIN→card/product wiring absent** in ingestion (`card_type_source=BIN` inert) | Requirement R24/R26 cannot pass as specified | R24, R26 | Dev — decide wire-up vs defer; cases kept as documented FAIL |
| M3 | Feed files `TBH_/TEG_E2E_TXN_JUL2026.csv` not yet built | All ingestion/fee/UI/PDF cases | R25–R41 | Tester creates per §3 spec (8 rows each) |
| M4 | Test users (U-BA-BH, U-BA-EG, U-BU-BH, U-FU-EG, U-OPS) not created | All role/RBAC/isolation cases | R13–R21 | Created by USER-001..005 |
| M5 | Egypt feed MSF/VAT/settled unit (major vs piastres) unconfirmed | Egypt revenue could be 100× off if piastres | FEE (EG) accuracy | Business — confirm `feed_amount_contract` |
| M6 | BH/EG `terminal_channel_map` rows marked ASSUMPTION | Channel-based fee cases may mis-resolve | FEE-013 | Business — supply real terminal vocab |
| M7 | BH/EG ticket bands marked ASSUMPTION | Ticket-band cases (FEE-024..025) | FEE bands | Business |
| M8 | SMTP dev config / OTP dev-log access | OTP + email cases (LOGIN-037..046, PDF-024) | R5, R41 | Ops — enable dev SMTP or OTP logging |
| M9 | External API key + scope provisioned | External API cases (SEC-030..049, PDF-030) | R42 | SA creates key with scopes |
| M10 | DB read access (psql on :5433) | All `[DB]` validations | ~40% of cases | Provided in env |
| M11 | Prod-like config for SEC-001/002/047 | Seed-reset & encryption-key findings are prod-config specific | R43 partial | Verify against prod ConfigMap, not dev |
| M12 | Legacy `legacy_transactions` table for migration cases | ADMIN-032..033 | R45 partial | Optional — skip if absent |
| M13 | Chargeback/reversal sample rows | INGEST-027, fee txn-type gap | R30 | Tester adds VOID/PREAUTH/chargeback rows to feed |

## 21. Notes on cases expected to FAIL (intentional gap documentation)

These are **not** authoring errors — they assert the requirement and are expected to fail against current code, producing evidence of the gap:
- **BIN-030..037** + existing C3/C4/D2 — BIN-based card/product identification not wired.
- **RBAC-020..026, SEC-010..018** — missing `@PreAuthorize` on business/finance/report endpoints (M-7).
- **SEC-001, 002, 047** — schema re-seed password reset, default JWT/encryption keys (prod config).
- **SEC-020..022, 038..045** — cleartext PAN, merchant-scope API keys, XFF/CIDR allowlist gaps.
- **INGEST-031, 032, FLOW-012, 021** — append double-count, split-day data loss.
- **FEE-027..032** — on-us, regional, premium/debit defaults, shadow-row reseed, history repricing.

Record each as PASS **only if the code has since been fixed**; otherwise FAIL with the finding reference. Do not mark them Blocked.

## 22. Final Test Report — template to complete after execution

**Totals:** prepared 556 (this plan) + 31 (existing A–G) = **587 cases**. Executed __ · Passed __ · Failed __ · Blocked __ · Not executed __ · Pass % __.

**By module:** LOGIN 55 · USER 60 · RBAC 45 · TENANT 69 · BIN 53 · INGEST 45 · FEE 40 · UI 40 · PDF 35 · SEC 49 · ADMIN 40 · FLOW 25.

Fill after run:
- Results by module / by tenant (BH vs EG vs ACQ) / by role (SA/ADMIN/BU/FU/OPS).
- Tenant-isolation issues · User-management issues · BIN/product-mapping issues · PDF-generation issues.
- Defects by severity: Critical / High / Medium / Low · Blockers.
- Missing requirements · Missing test data/BINs (from §20) · Retesting required.
- **Release-readiness assessment** with go/no-go per tenant (Bahrain, Egypt) and the sign-off checklist from the onboarding plan (one full BH month reconciled to the fils, zero cross-tenant rows, no fallback rates, PDF=DB).

### Failed-case report format (one per failure)
Test Case ID · Failed step · Expected · Actual · Reproduction steps · Tenant · Role · Test data · Error message · API/DB observation · Evidence ref · Severity · Recommended defect title.
