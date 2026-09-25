# PLAN — Daily Digest email: audit and improvement roadmap (2026-09-24)

> **BUILD STATUS (2026-09-24): Phase 1 BUILT.**
> Migration `V2026_09_24_01__digest_phase1_trust_audit.sql` (apply once via psql, like the
> other digest scripts — it is not in the startup list). Backend: `DigestScheduler`
> (tenant-local not-today rule, atomic PENDING→SENDING claim with stale-claim release,
> stored subject/HTML per send, per-recipient retry of the stored copy, restatement
> watch with ALERT/RESEND/OFF per tenant, `retryFailed`, `resendCopy`, `sentCopy`),
> `DigestFeedCoverage` (feed picture carried from gate to email), `DigestContentService`
> (coverage on the data), `DigestEmailService` (coverage banner, "not loaded" rows,
> RESTATED banner, figures-free subject by default), `DigestController` (menu-access gate,
> descriptive audit_log actions, allowed-domain list, subject/restatement options,
> `GET /dispatches/{id}/email`, `POST /dispatches/{id}/resend`, `POST /retry-failed`),
> `EmailSmtpController` (releases FAILED days when SMTP is saved/activated). Frontend:
> Privacy & governance card, Retry failed, View/Resend copy per row, restated marker,
> TODAY readiness chip. Tests: `DigestSchedulerGateTest` (9), `DigestEmailCoverageTest` (6),
> existing preview + screen tests green. Migration chain (ingest_trust → dcc → digest ×4 →
> phase 1) applied cleanly on the local portable Postgres.
> Not done in Phase 1: second-admin approval for external recipients (allowed-domain list
> shipped instead; approval flow is a Phase 3 governance item), and no end-to-end SMTP
> send was exercised locally (no mail server; delivery path unchanged from before).

Audience: business stakeholders (CEO / CFO office, Operations, Sales leadership) and the
Acquira delivery team. Sections 1–6 are written for business readers; section 7 is the
technical reference.

---

## 1. Executive summary

The Daily Digest is a once-a-day executive email per bank (tenant) that summarises the
previous business day: volume, transactions, MSF, the full fee stack down to Net Spread,
top merchants, movers, silent merchants, and scheme / card / domestic-international mix.
It went live on 2026-09-04 and has had three follow-up fixes since (mandatory-feed rule,
FX in Net Spread, bank name in the header).

The audit found the core mechanics sound (send-once guarantee, feed gating, admin screen,
manual run / resend). The weaknesses are in **trust and auditability**, not plumbing:

| # | Finding | Business impact | Severity |
|---|---------|-----------------|----------|
| 1 | Feeds a bank has switched off (DCC, rentals) still print as **0.000** with no note | Net Spread is understated and the reader cannot tell "zero" from "not loaded" | **High** |
| 2 | A **partial day** (today, intraday file) can be emailed, and corrections after sending are never re-communicated | Executives can receive wrong numbers that are never restated | **High** |
| 3 | The platform does **not keep a copy** of the email it sent | No way to prove what an executive saw on a given day (audit / dispute) | **High** |
| 4 | Recipient list is free text with **no domain restriction and no change audit** | Confidential bank P&L can be routed to any address; no trail of who changed it | **High** |
| 5 | Headline figures (volume, Net Spread) are in the **subject line** | Visible on lock screens, notification previews and mail-server logs | Medium |
| 6 | USD conversions use a **hard-coded rate snapshot** (dated 2026-09-10) | EGP floats; USD figures drift silently | Medium |
| 7 | Failed days are **terminal** (no auto-retry after SMTP is fixed); duplicate-send risk if the app is ever scaled to 2 pods | Ops effort; latent duplicate emails | Medium |
| 8 | No data-quality context (ingest alerts, fee coverage, holiday effects on the baseline) | "Is this number trustworthy?" cannot be answered from the email | Medium |

Recommended path: three phases over roughly six weeks. Phase 1 (about one week) closes
the four High findings and makes the digest audit-grade. Phase 2 (about two weeks) makes
the content richer and configurable. Phase 3 (two to three weeks) adds audiences and
cadences (weekly / monthly editions, RM portfolio digests, role-based variants).

---

## 2. What exists today (as built)

**Content of the email**
- Header: bank display name, business date.
- Headline cards: Volume, Transactions, MSF, Net Spread, each with an indicative USD figure.
- Context line: volume vs same weekday last week, and vs the month-to-date daily average.
- Revenue and fee stack: MSF, less interchange, scheme fees, PG / e-commerce fees, net
  margin, plus DCC income, rental income, FX income (only for FX-enabled banks), Net Spread.
- Top 5 merchants by volume.
- Movers vs the 4-week same-weekday average: gainers (at least +20%), decliners (at most
  -20%), and "silent" merchants (normally active, zero today).
- Mix: domestic vs international, top 8 schemes, top 8 card types.
- Footer: USD disclaimer and rate date.

**How it is sent**
- A 5-minute background sweep discovers recently loaded days (look-back window, default
  3 days), gates each day on the required feeds, waits for any running ingest to finish,
  applies a quiet period (default 15 minutes after the last load), then holds until the
  bank-local send time if one is set.
- Transactions are the only mandatory feed (decision of 2026-09-10). DCC, rentals and
  merchant master are optional per bank.
- Each day is sent exactly once (database uniqueness on bank + date). Up to 5 attempts,
  then the day is marked FAILED and shown on the admin screen.
- Email goes through the bank's own SMTP configuration, one message per recipient.

**Admin screen (Operations, Daily Digest)**
- Status strip (on/off, last sent, waiting, failed), delivery settings, required-feed
  toggles, "Run for a day" with per-feed readiness chips, preview, test send, and a
  30-row dispatch history with the reason a day is waiting.

**Not built**: weekly or monthly editions, per-recipient personalisation, stored copy of
sent emails, audit trail of settings, webhook event on send, plain-text alternative,
dashboard deep link, branding / logo.

---

## 3. Issues found (detail)

Severity: **High** = wrong or unprovable numbers reaching executives; **Medium** =
operational or confidentiality gap; **Low** = polish.

### 3.1 Trust in the numbers

**I-1 (High) Optional feeds that are off still print as zero.**
When a bank has DCC or rentals switched off in "Required feeds", the email still shows
"DCC income 0.000" and "Rental income 0.000" and rolls them into Net Spread. The reader
cannot distinguish "no DCC today" from "DCC file not loaded yet". Fix: show the row as
"not included (feed not loaded)" or hide it, add a completeness banner listing which feeds
the figures include, and footnote Net Spread accordingly.

**I-2 (High) Today's partial data can be emailed, and never corrected.**
Discovery includes the current calendar date. A bank that pushes an intraday file will get
a digest for today after the 15-minute quiet period, with part-day numbers. Because a day
sends only once, later files for that day are silently absorbed and the executive is never
told the numbers changed. The same applies to re-ingests and corrections of any already-sent
day. Fix: (a) only send days strictly before the bank-local "today" unless an admin runs it
by hand; (b) detect material change after send (row count or gross amount moves by more than
a threshold) and either send a clearly-labelled **Restated** digest or raise an alert.

**I-3 (Medium) Same-weekday baseline ignores public holidays.**
Movers and the "vs same weekday last week" line compare against the previous four same
weekdays. Around Eid, national days and Ramadan the baseline is distorted, so the email
reports false gainers / decliners. The platform already knows the working week per
country but has no holiday calendar. Fix: add a per-country holiday table, exclude
holidays from the baseline, and label the comparison when either day is a holiday.

**I-4 (Medium) USD figures use a hard-coded rate table.**
Rates are a snapshot dated 2026-09-10 inside the code. Pegged currencies (BHD, AED) are
fine; EGP is floating and drifts. Fix: move rates to a table an admin can maintain (or a
scheduled fetch), stamp each figure with the rate date, and let a bank turn USD off.

**I-5 (Low) Currency falls back to AED when the bank record has no base currency.**
A misconfigured bank would headline the wrong currency. Fix: fail the send with a clear
error instead of guessing.

### 3.2 Audit and governance

**I-6 (High) No copy of the sent email is kept.**
The dispatch ledger records date, status, recipients and time, but not the subject or
body. If a CFO asks "what did the digest say on the 12th?", or a number is disputed, the
only option is to re-render today's data, which may have changed since. Fix: store the
rendered subject and HTML (or a hash plus an archived copy in S3) per dispatch, and add
"View sent email" on the admin screen.

**I-7 (High) Recipients are unrestricted and settings changes are not audited.**
Any admin can type any external address. The table records only the last editor. The
platform's audit log is not written for digest settings changes, test sends or manual
runs. Fix: write audit-log entries for every settings change, test send and manual
send; add a per-bank allowed-domain list (default: the bank's own domain); optionally
require a second admin's approval for external addresses.

**I-8 (Medium) Headline figures are in the subject line.**
The subject carries the day's volume and Net Spread. That text appears on phone lock
screens, in notification previews and in mail-server logs outside the bank's control.
Fix: make it a per-bank option ("figures in subject: on/off"), default off.

**I-9 (Low) Access rule differs from the rest of the platform.**
The digest API is gated by fixed roles (ADMIN / SUPER_ADMIN) while most screens are gated
by menu assignment. A user group granted the menu but not the role gets an error. Fix:
switch to the menu-access rule used elsewhere.

### 3.3 Delivery and reliability

**I-10 (Medium) Failed days stay failed.**
After five attempts a day is FAILED and never retried, even after SMTP is repaired. Ops
must re-run each day by hand. Fix: "Retry all failed" action, and automatic retry of
FAILED days once SMTP settings are saved successfully.

**I-11 (Medium) Partial recipient failures are not retried.**
If 2 of 3 recipients fail, the day is marked SENT with a note and the two are never
retried. Fix: track per-recipient status and retry only the failed ones.

**I-12 (Medium, latent) Duplicate sends if the core app is scaled to more than one pod.**
The sweep reads pending days and then sends; two pods could both pick the same day in the
same window. Today the deployment is pinned to one pod, so this is not live, but it will
bite the first time the app is scaled. Fix: claim the row atomically (status PENDING to
SENDING) before rendering, or use the advisory-lock pattern already used by the churn
retraining job.

**I-13 (Low) The send-event webhook is not published.**
The enterprise API roadmap lists "digest sent" as an event. It is a small addition to the
existing webhook dispatcher and lets a bank's own systems archive or forward the digest.

### 3.4 Email quality

**I-14 (Medium)** No plain-text alternative, no preheader text, no bank logo or brand
colours, no link back to the dashboard (the platform has no public URL setting), Unicode
arrows that some clients render as boxes, one SMTP message per recipient rather than a
single message, and a light-only palette that dark-mode mail clients may invert.

**I-15 (Low)** Thresholds are fixed in code: top 5 merchants, 20% mover cut-off, 4-week
baseline, top 8 mix rows. Banks differ in size; a large bank may want top 10 and a 30%
cut-off.

### 3.5 Testing

**I-16 (Medium)** Automated tests cover the HTML renderer and the admin screen, but not
the gating and scheduling logic (feed presence, quiet period, send time, attempts, send
once), which is where the business risk sits. Fix: add unit tests around the gate with an
in-memory or test database before Phase 1 changes land.

---

## 4. What can be built (opportunities)

Each item lists the business value and a rough effort: **S** up to one day, **M** two to
four days, **L** one to two weeks.

### 4.1 Make the numbers trustworthy (Phase 1)
| Item | Value | Effort |
|------|-------|--------|
| Data-completeness banner (feeds included, load times, row counts) and "not loaded" rows | Reader knows exactly what the figures cover | S |
| No same-day sends; restatement digest or alert when a sent day changes materially | Wrong numbers are corrected, visibly | M |
| Store the sent subject and HTML; "View sent email" and "Resend exact copy" | Audit-grade record of what each executive received | M |
| Audit log entries for settings, test sends, manual runs | Who changed what, when | S |
| Allowed-domain list for recipients, optional second-admin approval | Confidential P&L stays inside the bank | S-M |
| Subject-line privacy option | No figures on lock screens | S |
| Retry failed days (manual bulk + automatic after SMTP fix); per-recipient retry | Fewer Ops interventions | M |
| Atomic claim of a day before sending | Safe to scale the app later | S |
| Gate / scheduler unit tests | Confidence in every later change | M |

### 4.2 Richer content (Phase 2)
| Item | Value | Effort |
|------|-------|--------|
| Month-to-date cumulative figures and month-end run-rate projection | The CFO question "where will the month land?" | M |
| Versus target: monthly volume / revenue targets already exist for sales agents; a bank-level target gives "% of plan" | Performance against plan in every email | M |
| DCC opt-in rate and DCC-eligible volume (fields already summarised daily) | Tracks the DCC revenue lever | S |
| Data-quality section: yesterday's ingest alerts (no data, reconciliation gap, fee-coverage drop, volume anomaly) and fee-priced row coverage | Answers "can I trust this?" | M |
| Holiday calendar per country; holiday-aware baselines and labels | Removes false movers around Eid / national days | M |
| Loss-making merchants count and the top 3 by loss (screen already exists) | Early warning on negative-margin merchants | S |
| Merchants at churn risk (churn model already runs in batch) and newly activated merchants | Portfolio movement, not just volume | M |
| 14-day trend strip (simple bar table, no images) | Shape of the fortnight at a glance | S |
| Colour bar charts in the email: 14-day volume and Net Spread bars, scheme-mix bars, mover bars. Rendered as coloured table cells (works in every mail client, no images) or as server-generated PNG images embedded inline (CID); never scripts or external CSS, which mail clients strip | Executives read the shape at a glance; the email looks like the dashboard | M |
| Configurable thresholds per bank (top N, mover %, baseline weeks) | Fits small and large banks | S |
| Live or admin-maintained FX rates with per-figure rate date; USD toggle per bank | Correct USD for floating currencies | M |
| Branding (bank logo, colours), dashboard deep link, plain-text part, preheader, single message with recipients on To/BCC | Professional, accessible email | M |

### 4.3 Audiences and cadence (Phase 3)
| Item | Value | Effort |
|------|-------|--------|
| Weekly edition (Monday, week vs prior week and vs same week last year) and monthly edition (1st of month, month close) | Executive rhythm beyond the daily | L |
| Role variants: Executive (headline), Operations (feeds, alerts, SLA), Finance (fee stack, reconciliation) | Each reader gets what they act on | L |
| Relationship-manager digest: each RM receives only their mapped portfolio (RM to merchant mapping exists) | Sales team gets a daily call list | L |
| Recipients from the user directory with per-user opt-in and audience tag, instead of a free-text list | Governance and self-service | M |
| Attach a PDF version (PDF service exists) | Board-pack ready, archivable | M |
| Publish "digest sent" webhook; optional post to Teams / Slack channel | Integrates with the bank's own tooling | S-M |
| Locale-aware number and date formats (Arabic digits / date order) per bank | Regional readiness | M |

---

## 5. Phased plan

**Phase 1: Trust and audit (about 1 week)**
1. Gate / scheduler unit tests (I-16).
2. Completeness banner and "not loaded" handling (I-1).
3. No same-day sends; restatement detection (I-2).
4. Store sent subject + HTML; View / Resend exact copy (I-6).
5. Audit log entries; allowed-domain list; subject privacy option (I-7, I-8).
6. Retry failed; atomic claim; menu-access gate (I-10, I-12, I-9).

Exit criterion: every sent digest can be reproduced exactly, every settings change is
traceable, and no digest can show an unloaded feed as zero.

**Phase 2: Content (about 2 weeks)**
1. MTD cumulative, run-rate, versus target.
2. DCC opt-in, loss-making merchants, churn-risk and new merchants.
3. Data-quality section from ingest alerts.
4. Holiday calendar and holiday-aware baselines.
5. Configurable thresholds; FX rate table; branding, deep link, plain-text part; colour bar
   charts (14-day trend, scheme mix, movers).

Exit criterion: business sign-off on a redesigned template against three real bank days.

**Phase 3: Audiences (2 to 3 weeks)**
1. Weekly and monthly editions.
2. Role variants and user-directory recipients.
3. RM portfolio digest.
4. PDF attachment, webhook event, Teams / Slack post.

Exit criterion: at least one bank running daily + weekly + RM digests in production.

---

## 6. Decisions needed from the business

1. Should the subject line carry figures at all? (Recommendation: no, by default.)
2. May a digest ever be sent for the current day, or only for completed days?
   (Recommendation: completed days only; admins can still run today by hand.)
3. Restatement policy: if a sent day's data changes by more than X% (suggest 1% of
   volume or any change to Net Spread), send a labelled restatement or only alert Ops?
4. External recipients: allowed at all? If yes, under what approval?
5. Which audiences and cadences matter first: CEO/CFO daily, Ops daily, RM daily,
   executive weekly, board monthly?
6. USD conversion: keep with maintained rates, or show home currency only for floating
   currencies (EGP)?
7. Source of the public-holiday calendar per country (manual table vs external feed).
8. Retention period for stored copies of sent digests (suggest 24 months).

---

## 7. Technical reference

| Area | Location |
|------|----------|
| Sweep, gating, send-once, manual run | `acquira-core/src/main/java/com/acquira/core/service/DigestScheduler.java` |
| Figures (totals, movers, mix) | `acquira-core/src/main/java/com/acquira/core/service/DigestContentService.java` |
| HTML rendering and subject | `acquira-core/src/main/java/com/acquira/core/service/DigestEmailService.java` |
| Admin API | `acquira-core/src/main/java/com/acquira/core/controller/DigestController.java` |
| Admin screen | `frontend/src/pages/ops/DailyDigest.jsx` |
| Tables | `digest_config`, `digest_dispatch` (migrations `V2026_09_04_01`, `V2026_09_05_03`, `V2026_09_05_04`, `V2026_09_10_01`) |
| SMTP send | `acquira-core/src/main/java/com/acquira/core/service/EmailService.java` (`sendEmailWithAttachment`) |
| USD rate snapshot | `acquira-common/src/main/java/com/acquira/common/service/FxRates.java` |
| Ingest alerts to reuse | `acquira-core/src/main/java/com/acquira/core/service/IngestAlertScheduler.java` (`alert_history`) |
| Advisory-lock pattern to reuse | `acquira-batch/src/main/java/com/acquira/batch/service/ChurnRetrainScheduler.java` |
| Webhook dispatcher | `acquira-core/src/main/java/com/acquira/core/webhook/WebhookDispatcher.java` |
| Existing tests | `DigestEmailPreviewTest.java`, `frontend/src/test/dailyDigest.test.jsx` |

Evidence for the findings (file and line):
- I-1: `DigestEmailService.feeStack` always renders DCC / rental rows from totals regardless of `require_dcc` / `require_rental`.
- I-2: `DigestScheduler.discover` uses `c.txn_date <= CURRENT_DATE`; `digest_dispatch` UNIQUE(tenant_id, business_date) with no restatement path.
- I-4: `FxRates.AS_OF = "2026-09-10"`, `USD_PER_UNIT` static map.
- I-6: `digest_dispatch` columns: status, waiting_on, attempts, sent_at, recipients_sent, error_message; no subject / body.
- I-7: `DigestController.saveConfig` writes `updated_by` only; no `audit_log` insert; `parseRecipients` accepts any address containing "@".
- I-8: `DigestEmailService.subject` includes volume and spread.
- I-9: `@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")` vs `@menuAccess.canAccess(...)` used by 40 other controllers.
- I-10 / I-11: `DigestScheduler.recordFailure` marks FAILED at 5 attempts; `send` marks SENT when at least one recipient succeeds.
- I-12: `DigestScheduler.process` selects PENDING rows then sends without a claim; `deploy/k8s/05-core.yaml` pins replicas to 1.
- I-13: `docs/deploy/ENTERPRISE_API_V1.md` "Known limits".
