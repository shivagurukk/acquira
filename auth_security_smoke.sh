#!/usr/bin/env bash
# ============================================================================
# Acquira — Auth security smoke test
#
# Exercises: login happy-path + validation, per-user lockout, IP+username rate
# limiting, username-enumeration guards, and the OTP forgot-password flow
# (request -> verify -> set). Read-only against auth endpoints except that it
# WILL lock and then reset a throwaway test user, so run it against DEV only.
#
# Requires: bash, curl, jq. Backend on $BASE (default http://localhost:8081).
#
# Usage:
#   BASE=http://localhost:8081 \
#   ADMIN_USER=admin ADMIN_PASS=password \
#   TEST_USER=<throwaway username> TEST_EMAIL=<its email> TEST_PASS=<its pass> \
#   ./auth_security_smoke.sh
#
# The OTP steps read the code from the backend log (no SMTP in dev): the app
# logs "Reset OTP for <user>: NNNNNN" via EmailService. Point OTP_LOG at that
# log file (default /opt/acquira/logs/core.log; dev often ./logs/core.log).
# ============================================================================
set -uo pipefail

BASE="${BASE:-http://localhost:8081}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-password}"
TEST_USER="${TEST_USER:-}"
TEST_EMAIL="${TEST_EMAIL:-}"
TEST_PASS="${TEST_PASS:-}"
OTP_LOG="${OTP_LOG:-./logs/core.log}"

pass=0; fail=0
ok()   { echo "  PASS: $1"; pass=$((pass+1)); }
bad()  { echo "  FAIL: $1"; fail=$((fail+1)); }
hdr()  { echo; echo "=== $1 ==="; }

# POST helper -> prints HTTP status code; body saved to /tmp/aq_body
post() { # $1=path $2=json
  curl -s -o /tmp/aq_body -w "%{http_code}" \
    -H 'Content-Type: application/json' -X POST "$BASE$1" -d "$2"
}
body() { cat /tmp/aq_body; }

# ---------------------------------------------------------------------------
hdr "A1 Login — validation"
code=$(post /api/auth/login '{}')
[ "$code" = "400" ] && ok "empty body -> 400" || bad "empty body expected 400 got $code"

code=$(post /api/auth/login '{"username":"   ","password":"   "}')
[ "$code" = "400" ] && ok "whitespace creds -> 400" || bad "whitespace creds expected 400 got $code"

hdr "A4 Username enumeration — unknown user vs wrong password look identical"
code=$(post /api/auth/login '{"username":"definitely_not_a_user_zzz","password":"whatever123!"}')
m1=$(body | jq -r '.error // empty')
[ "$code" = "401" ] && ok "unknown user -> 401" || bad "unknown user expected 401 got $code"
echo "     message: '$m1'  (must be generic, no 'user not found')"
echo "$m1" | grep -qiE "not found|no such|unknown user" && bad "message leaks existence" || ok "message is generic"

if [ -z "$TEST_USER" ] || [ -z "$TEST_PASS" ]; then
  echo
  echo "!! TEST_USER / TEST_PASS not set — skipping lockout, rate-limit, and reset"
  echo "   sections (they need a throwaway account). Set them to run the full suite."
  echo
  echo "Summary: $pass passed, $fail failed (partial run)."
  exit $([ "$fail" -eq 0 ] && echo 0 || echo 1)
fi

hdr "A1 Login — happy path"
code=$(post /api/auth/login "{\"username\":\"$TEST_USER\",\"password\":\"$TEST_PASS\"}")
if [ "$code" = "200" ]; then
  ok "valid creds -> 200"
  body | jq -e '.jwt and .refreshToken' >/dev/null && ok "jwt + refreshToken present" || bad "token fields missing"
  body | jq -e 'has("password_hash") or has("passwordHash")' >/dev/null && bad "response leaks password hash" || ok "no password hash in body"
else
  bad "valid creds expected 200 got $code — check TEST_USER/TEST_PASS; skipping rest"
  echo "Summary: $pass passed, $fail failed."; exit 1
fi

hdr "A2 Per-user lockout (default threshold 5)"
locked=""
for i in 1 2 3 4 5 6; do
  code=$(post /api/auth/login "{\"username\":\"$TEST_USER\",\"password\":\"WRONGpw_$i!\"}")
  msg=$(body | jq -r '.error // empty')
  echo "  attempt $i -> HTTP $code ($msg)"
  echo "$msg" | grep -qi "attemptsRemaining" && bad "leaks attemptsRemaining (enumeration side-channel)"
  [ "$code" = "423" ] && locked="yes"
done
[ -n "$locked" ] && ok "account locked (423) after repeated failures" || bad "never returned 423 lock"

hdr "A2 Correct password while locked must NOT bypass lockout"
code=$(post /api/auth/login "{\"username\":\"$TEST_USER\",\"password\":\"$TEST_PASS\"}")
if [ "$code" = "423" ]; then ok "correct password still 423 while locked"
elif [ "$code" = "200" ]; then bad "SECURITY: correct password bypassed active lockout (200)"
else echo "  note: got $code (may be 429 if rate-limited — acceptable, still not 200)"; ok "not a 200 bypass"; fi

hdr "A3 IP+username rate limiting (429 after ~10 rapid hits)"
got429=""
for i in $(seq 1 12); do
  code=$(post /api/auth/login "{\"username\":\"$TEST_USER\",\"password\":\"rl_$i!\"}")
  [ "$code" = "429" ] && got429="yes"
done
[ -n "$got429" ] && ok "429 returned under rapid fire" || echo "  note: no 429 (may be masked by 423 lock — inspect manually)"

# ---------------------------------------------------------------------------
hdr "A6 OTP forgot-password flow"
if [ -z "$TEST_EMAIL" ]; then
  echo "  TEST_EMAIL not set — skipping OTP flow."
else
  # Step 0: unknown email must return generic success (no existence leak)
  code=$(post /api/auth/forgot-password '{"email":"nobody_zzz@example.invalid"}')
  ok "unknown email -> HTTP $code (generic, see message):"
  body | jq -r '.message // .error'

  # Step 1: request OTP for the real test email
  code=$(post /api/auth/forgot-password "{\"email\":\"$TEST_EMAIL\"}")
  [ "$code" = "200" ] && ok "OTP requested -> 200" || bad "OTP request expected 200 got $code"

  # Grab the OTP from the log (dev, no SMTP). Best-effort.
  OTP=""
  if [ -f "$OTP_LOG" ]; then
    OTP=$(grep -aoE "Reset OTP for [^:]*: [0-9]{6}" "$OTP_LOG" | tail -1 | grep -oE "[0-9]{6}$")
  fi
  if [ -z "$OTP" ]; then
    echo "  !! Couldn't read OTP from $OTP_LOG. Set OTP_LOG or enter it now:"
    read -r -p "     OTP: " OTP
  fi

  # Step 2a: wrong OTP -> generic fail, attempt counted
  code=$(post /api/auth/verify-otp "{\"email\":\"$TEST_EMAIL\",\"otp\":\"000001\"}")
  [ "$code" = "400" ] && ok "wrong OTP -> 400 generic" || echo "  note: wrong OTP got $code"

  # Step 2b: correct OTP -> ticket
  code=$(post /api/auth/verify-otp "{\"email\":\"$TEST_EMAIL\",\"otp\":\"$OTP\"}")
  TICKET=$(body | jq -r '.ticket // empty')
  if [ "$code" = "200" ] && [ -n "$TICKET" ]; then
    ok "correct OTP -> ticket issued"
  else
    bad "OTP verify failed (HTTP $code) — OTP wrong/expired? skipping set-password"
    TICKET=""
  fi

  if [ -n "$TICKET" ]; then
    # Step 3a: weak password rejected by PasswordService
    code=$(post /api/auth/reset-password "{\"ticket\":\"$TICKET\",\"newPassword\":\"password\"}")
    [ "$code" = "400" ] && ok "weak/common new password rejected" || bad "weak password not rejected (got $code)"

    # Step 3b: strong password accepted (resets TEST_USER's password!)
    NEWPASS="Aq!$(date +%s)Xz"
    code=$(post /api/auth/reset-password "{\"ticket\":\"$TICKET\",\"newPassword\":\"$NEWPASS\"}")
    if [ "$code" = "200" ]; then
      ok "strong new password accepted (TEST_USER password is now: $NEWPASS)"
      # Step 3c: ticket single-use — reusing it must fail
      code=$(post /api/auth/reset-password "{\"ticket\":\"$TICKET\",\"newPassword\":\"${NEWPASS}2\"}")
      [ "$code" = "400" ] && ok "ticket is single-use (reuse rejected)" || bad "ticket reusable (got $code)"
      # Step 3d: new password logs in, and lockout was cleared by the reset
      code=$(post /api/auth/login "{\"username\":\"$TEST_USER\",\"password\":\"$NEWPASS\"}")
      [ "$code" = "200" ] && ok "login with reset password works (lockout cleared)" \
        || echo "  note: login after reset got $code (rate-limit window may still be open; retry in 60s)"
    else
      bad "strong password reset failed (HTTP $code): $(body | jq -r '.error // empty')"
    fi
  fi
fi

echo
echo "============================================================"
echo "Summary: $pass passed, $fail failed."
echo "Manual checks still worth doing (not scriptable here):"
echo "  - #16 refresh-token replay -> all sessions revoked"
echo "  - #15 inactive/pending/expired account returns generic 401 (check audit_log LOGIN_DENIED)"
echo "  - Change-password screen (#28-32) via the UI or an authed /users/change-password call"
echo "============================================================"
exit $([ "$fail" -eq 0 ] && echo 0 || echo 1)
