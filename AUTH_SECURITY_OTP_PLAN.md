# Acquira — Auth Security: Testing Plan + OTP Forgot-Password + Change-Password UI Fix

**Date:** 2026-07-11
**Scope:** Login / lockout / rate-limit security testing, convert Forgot Password from email-link to **OTP (send OTP → verify OTP → set new password)**, and fix the **white background** on the Change Password screen.
**Files read before writing this plan:** `AuthController.java`, `PasswordService.java`, `PasswordResetToken.java`, `PasswordResetTokenRepository.java`, `EmailService.java`, `ChangePasswordPage.jsx`, `LoginPage.jsx`.

---

## 0. What already exists (so we build additively, not from scratch)

**Login security — already solid:**
- Per-user lockout: `MAX_USER_ATTEMPTS` (policy-driven, default 5) → `lockedUntil` set for `LOCKOUT_MINUTES` (default 15). Expired lockout auto-resets the counter (P1-4).
- IP+username rate limiter: 10 attempts / 60s per `(ip|username)` bucket (P2-7), returns **429**.
- Username-enumeration hardened: bad-creds / inactive / pending all return the same generic **401** `"Invalid username or password"` (P1-5); only the user's own lockout returns a distinct **423**.
- Account states blocked: inactive, pending-approval, expired (expiry auto-deactivates once).
- Refresh-token rotation + reuse detection (revoke-all on reuse), HttpOnly `Strict` secure cookie, concurrent-session cap, audit logging on LOGIN / LOGIN_DENIED / PASSWORD_EXPIRED.
- Force-password-change on `mustChangePassword` **or** password aged past `security.password_expiry_days`.

**Forgot password — currently LINK-based (this is what we're changing):**
- `POST /api/auth/forgot-password` → generates a UUID token (1h expiry) into `password_reset_token`, emails a **link** (`/reset-password?token=...`).
- `POST /api/auth/reset-password` → `{token, newPassword}` → validates token, calls `passwordService.adminResetPassword`.
- Frontend collects email only, shows "reset link sent". **There is no in-app OTP entry and no `/reset-password` page component.**

**Change Password screen — the "white background":**
- `ChangePasswordPage.jsx` already uses a gradient `pageBg`, BUT the **light-mode** gradient is `#eef2ff → #f8fafc → #eff6ff` — nearly white. When `theme.mode` is undefined/light it reads as a blank white page. The login page, by contrast, uses the dark glass-card aesthetic (`Login.css` mesh + blobs). That mismatch is the complaint.

---

## PART A — Security Testing (login, attempts, lockout, reset)

Run these against dev (`127.0.0.1:5433` DB, backend `:8081`). No code change needed to test the login/lockout paths — they're already implemented; this validates them and catches regressions after the OTP work.

### A1. Login — happy path & input validation
| # | Test | Expected |
|---|---|---|
| 1 | Valid creds | 200, `jwt` + `refreshToken` cookie set (HttpOnly, Secure, SameSite=Strict, path `/api/auth`) |
| 2 | Empty username or password | 400 `"Username and password are required"` |
| 3 | Whitespace-only username | 400 (trimmed → empty) |
| 4 | Response body never leaks `password_hash` / internal fields | Only whitelisted keys present |

### A2. Failed attempts & per-user lockout
| # | Test | Expected |
|---|---|---|
| 5 | Wrong password ×1–4 (threshold 5) | 401 generic; **no** `attemptsRemaining` in body (enumeration guard) |
| 6 | Wrong password ×5 | 423 `locked`, `lockedUntil` = now + lockout minutes |
| 7 | Correct password **while locked** | 423 (right password must NOT bypass lockout) |
| 8 | Wait out lockout, then wrong password | Counter reset → fresh attempts (P1-4), not instant re-lock |
| 9 | Successful login resets counter | `failed_login_attempts=0`, `locked_until=null` |
| 10 | Lockout threshold/minutes honor Admin→Security Settings policy | Changing policy changes behavior without redeploy |

### A3. IP + username rate limiting (defense-in-depth)
| # | Test | Expected |
|---|---|---|
| 11 | 10 rapid attempts same `(ip,user)` in 60s | 429 `"Too many login attempts…"` |
| 12 | Two different usernames from same IP | Buckets independent — one user's failures don't 429 the other (P2-7) |
| 13 | `X-Forwarded-For` spoof | Server uses first XFF hop; confirm it can't be trivially rotated to evade (note for infra: trust only the proxy's appended IP) |

### A4. Username enumeration
| # | Test | Expected |
|---|---|---|
| 14 | Login as nonexistent user | Same generic 401, same timing class as wrong-password (no fast-path difference that reveals existence) |
| 15 | Inactive / pending / expired account with correct password | Generic 401 (not a distinct message); real reason only in `audit_log` (`LOGIN_DENIED`) |

### A5. Token / session security
| # | Test | Expected |
|---|---|---|
| 16 | Use refresh token twice (replay) | Second use → 401 reuse-detected, **all** sessions revoked |
| 17 | Refresh after admin "revoke all sessions" | 401 revoked |
| 18 | Access token after expiry | 401 → silent refresh path works once |
| 19 | Concurrent sessions beyond cap | Oldest sessions revoked when cap set |

### A6. Password reset (validate NEW OTP flow — see Part B for build)
| # | Test | Expected |
|---|---|---|
| 20 | Request OTP for unknown email | Generic success (no email-existence leak) |
| 21 | OTP correct within TTL | Advances to set-password step |
| 22 | OTP wrong ×N | Locked after max attempts; generic failure |
| 23 | OTP expired | Rejected, must re-request |
| 24 | OTP reuse after success | Rejected (single-use) |
| 25 | New password reuses last-N | Rejected by `PasswordService` history check |
| 26 | New password weak / identity-based | Rejected by strength + identifier checks |
| 27 | After reset, old OTP invalid + old sessions revoked | Both true |

### A7. Change password (self-service, `/users/change-password`)
| # | Test | Expected |
|---|---|---|
| 28 | Wrong current password | `"Current password is incorrect"` |
| 29 | New == current | Rejected |
| 30 | Min-age policy (rapid re-change) | Rejected if within `minPasswordAgeHours` |
| 31 | Reuse last-N | Rejected |
| 32 | Success | `must_change_password` cleared, `password_changed_at` updated, history recorded |

**Deliverable:** I'll produce a runnable test script (`auth_security_smoke.sh` using `curl` + `jq`, or a small JUnit `@SpringBootTest` class hitting `MockMvc`) covering #1–#32. Tell me which format you prefer.

---

## PART B — OTP Forgot-Password (replace email-link flow)

**Design:** 6-digit numeric OTP, single-use, short TTL, hashed at rest, attempt-limited. Reuse the existing `password_reset_token` table by adding OTP columns (idempotent ALTER) — no new table needed.

### B1. Schema migration (one idempotent file, mode=never-safe)
`V2026_07_11_01__password_reset_otp.sql`:
```sql
ALTER TABLE password_reset_token ADD COLUMN IF NOT EXISTS otp_hash      VARCHAR(255);
ALTER TABLE password_reset_token ADD COLUMN IF NOT EXISTS attempt_count INT DEFAULT 0;
ALTER TABLE password_reset_token ADD COLUMN IF NOT EXISTS verified      BOOLEAN DEFAULT FALSE;
-- 'token' column stays: after OTP verify we hand back a short-lived opaque
-- reset ticket so the set-password call doesn't resend the OTP over the wire.
```
Add to **both** `application.properties` and `application-prod.properties` schema-locations (per migration discipline). BCrypt the OTP — never store the plaintext.

### B2. Backend — 3 endpoints (rewire `AuthController`)
1. `POST /api/auth/forgot-password` `{email}` → if user exists: generate 6-digit OTP, store `otp_hash` (BCrypt) + `expiresAt = now+10min` + `attempt_count=0` + `verified=false` (delete prior tokens for user first, as today). Email the **OTP code** (new `EmailService.sendPasswordResetOtp`). **Always** return generic success.
2. `POST /api/auth/verify-otp` `{email, otp}` → load newest unused token for user; check not expired, `attempt_count < MAX (5)`, BCrypt-match. On success: `verified=true`, issue a fresh opaque `reset ticket` (UUID in `token`), return `{ticket}`. On failure: `attempt_count++`, generic error; lock after max.
3. `POST /api/auth/reset-password` `{ticket, newPassword}` → require `verified=true` + ticket match + not expired + not used → `passwordService.adminResetPassword` → mark used → **revoke all refresh tokens for that user** (force re-login everywhere) → `mustChangePassword=false`.

**Guards:** rate-limit OTP requests per email (reuse the existing limiter keyed by email), audit each step (`PWRESET_OTP_SENT`, `PWRESET_OTP_FAIL`, `PWRESET_DONE`), constant generic responses to avoid enumeration, OTP is single-use and TTL-bound. Keep everything `@Transactional` (the current methods already are — same `deleteByUserId` bulk-delete constraint applies).

### B3. Email
Add `sendPasswordResetOtp(email, username, otp)` to `EmailService` — plain-text, "Your Acquira verification code is NNNNNN, valid 10 minutes." Uses the same pre-login property-fallback sender path as today (no tenant context). Dev fallback: log the OTP when no SMTP.

### B4. Frontend — `LoginPage.jsx` forgot-password panel becomes 3 steps
Currently one step (email → "link sent"). New in-place stepper inside the existing `showForgotPw` panel (no new route needed):
- **Step 1 — Email:** unchanged input → calls `forgot-password` → advance to Step 2 (always, regardless of existence).
- **Step 2 — Enter OTP:** 6-box OTP input, resend (cooldown 30s), calls `verify-otp` → on success store `ticket`, advance.
- **Step 3 — New password:** reuse the strength meter + checklist from `ChangePasswordPage`, calls `reset-password` with `{ticket, newPassword}` → success → back to login with a toast.

Validate with `npx --yes esbuild@0.21.5` before writing.

---

## PART C — Change-Password white-background fix

**Root cause:** light-mode `pageBg` gradient is near-white, and if `theme.mode` isn't `'dark'` the page renders that pale gradient with a plain white card floating in it — reads as "white background".

**Fix (frontend-only, no rebuild of backend):** align the force-change / change-password screen with the login aesthetic:
- Replace the pale light gradient with a **defined** branded gradient (deeper indigo/slate even in light mode), OR reuse the login's `gradient-mesh` + blob treatment so both auth screens match.
- Ensure the card has enough contrast against the background in **both** modes (stronger border + shadow in light mode).
- Confirm `useTheme()` actually returns a defined `theme.mode`; if it can be `undefined` on this route (rendered outside the themed layout), default to the dark treatment so it never falls through to white.

Single-file `write_file` overwrite of `ChangePasswordPage.jsx`, esbuild-validated.

---

## Execution order (my recommendation)
1. **C first** (fastest, frontend-only, visible win) — fix the white screen, deploy `npm run build → dist → restorecon → hard refresh`.
2. **B** — OTP flow: migration → `AuthController`/`EmailService` → `LoginPage.jsx`. Backend rebuild `mvn clean install -pl acquira-common,acquira-core -am` + frontend build.
3. **A** — run the security test script against the finished build; fix any regressions.

## Open decisions for you
- **Test format:** `curl`+`jq` shell script vs JUnit `MockMvc`?
- **OTP length/TTL:** 6 digits / 10 min / 5 attempts OK, or different?
- **Keep the old email-link path** as a fallback, or fully replace with OTP?
- **Change-password background:** match the login mesh exactly, or a simpler solid deep-gradient?

Say "proceed" and I'll start with C, then B, then A — or reorder as you like.
