-- ============================================================================
-- V2026_09_24_01: Daily Digest — Phase 1 (trust and audit).
--
-- Closes the four High findings of PLAN_DAILY_DIGEST_IMPROVEMENT_2026-09-24:
--   I-1 unloaded feeds printed as zero      → feeds_included stamped per send,
--                                             renderer shows "not loaded".
--   I-2 partial / restated days             → discovery stops at the bank-local
--                                             "today"; sent_rows_fact /
--                                             sent_gross_amount snapshot lets
--                                             the sweep detect a material
--                                             change after send (restate_mode).
--   I-6 no copy of the sent email           → sent_subject / sent_html.
--   I-7/I-8 recipients + subject privacy    → allowed_domains, subject_figures.
--   I-10/I-11/I-12 retry + atomic claim     → recipients_failed, claimed_at,
--                                             status SENDING.
--
-- Idempotent; splitter-safe (no $$). Like the other digest scripts this is
-- applied once via psql (V2026_09_04_01 is not in the startup list either).
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. digest_config — new per-tenant options
-- ---------------------------------------------------------------------------
-- subject_figures: FALSE = subject carries no volume / spread figures (privacy
--                  default — lock screens and mail logs see only the bank + date).
-- allowed_domains: comma-separated list; NULL / empty = no restriction (keeps
--                  every currently-configured recipient list valid). Once set,
--                  saving a recipient outside the list is refused.
-- restate_mode:    what to do when a SENT day's data changes materially:
--                  OFF, ALERT (alert_history row, default) or RESEND (a
--                  clearly-labelled restated digest).
-- restate_threshold_pct: gross amount must move by at least this % to count.
ALTER TABLE digest_config ADD COLUMN IF NOT EXISTS subject_figures        BOOLEAN      NOT NULL DEFAULT FALSE;
ALTER TABLE digest_config ADD COLUMN IF NOT EXISTS allowed_domains        TEXT;
ALTER TABLE digest_config ADD COLUMN IF NOT EXISTS restate_mode           VARCHAR(16)  NOT NULL DEFAULT 'ALERT';
ALTER TABLE digest_config ADD COLUMN IF NOT EXISTS restate_threshold_pct  NUMERIC(6,2) NOT NULL DEFAULT 1.00;

-- ---------------------------------------------------------------------------
-- 2. digest_dispatch — audit copy, per-recipient outcome, claim, snapshot
-- ---------------------------------------------------------------------------
-- status gains SENDING: a sweep claims a PENDING row (PENDING → SENDING,
-- claimed_at = now) BEFORE rendering, so two app pods can never both send the
-- same day. A SENDING row older than 30 minutes (pod died mid-send) is
-- released back to PENDING by the next sweep.
ALTER TABLE digest_dispatch ADD COLUMN IF NOT EXISTS sent_subject       TEXT;
ALTER TABLE digest_dispatch ADD COLUMN IF NOT EXISTS sent_html          TEXT;
ALTER TABLE digest_dispatch ADD COLUMN IF NOT EXISTS recipients_failed  TEXT;
ALTER TABLE digest_dispatch ADD COLUMN IF NOT EXISTS feeds_included     VARCHAR(64);
ALTER TABLE digest_dispatch ADD COLUMN IF NOT EXISTS sent_rows_fact     BIGINT;
ALTER TABLE digest_dispatch ADD COLUMN IF NOT EXISTS sent_gross_amount  NUMERIC(21,4);
ALTER TABLE digest_dispatch ADD COLUMN IF NOT EXISTS restate_count      INT          NOT NULL DEFAULT 0;
ALTER TABLE digest_dispatch ADD COLUMN IF NOT EXISTS restated_at        TIMESTAMP;
ALTER TABLE digest_dispatch ADD COLUMN IF NOT EXISTS claimed_at         TIMESTAMP;

-- Sweep lookups: partial-recipient retries and restatement scans both walk
-- SENT rows for a tenant.
CREATE INDEX IF NOT EXISTS ix_digest_dispatch_sent_retry
    ON digest_dispatch (tenant_id, status, business_date DESC);

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_09_24_01__digest_phase1_trust_audit.sql')
ON CONFLICT (filename) DO NOTHING;
