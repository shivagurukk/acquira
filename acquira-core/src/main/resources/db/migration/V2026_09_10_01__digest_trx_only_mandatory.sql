-- ============================================================================
-- V2026_09_10_01: Daily Digest — transactions are the ONLY mandatory feed.
--
-- Symptom: a bank that only loads transactions never saw its digest send —
-- the day sat PENDING with waiting_on = 'DCC+RENTAL' (and 'MERCHANT') forever,
-- because DCC / rental / merchant all defaulted to required = TRUE and those
-- feeds simply never arrive for that tenant.
--
-- Decision (2026-09-10): only require_trx is mandatory; DCC, rental and
-- merchant become OPTIONAL — off by default, an admin turns them on per tenant
-- if the bank actually receives that feed. This flips the stored defaults AND
-- unblocks every currently-held day.
--
-- Idempotent; splitter-safe (no $$). On prod apply once via psql.
-- ============================================================================

-- 1. New-tenant defaults: only transactions stays TRUE.
ALTER TABLE digest_config ALTER COLUMN require_dcc      SET DEFAULT FALSE;
ALTER TABLE digest_config ALTER COLUMN require_rental   SET DEFAULT FALSE;
ALTER TABLE digest_config ALTER COLUMN require_merchant SET DEFAULT FALSE;
ALTER TABLE digest_config ALTER COLUMN require_trx      SET DEFAULT TRUE;

-- 2. Existing tenants: relax the three optional feeds, and make sure
--    transactions is mandatory everywhere.
--
--    ONE-TIME flip, guarded on this migration's own ledger row: after it has
--    run, an admin turning DCC or rental back on for a tenant is a deliberate
--    choice, and re-running this file must not silently undo it. The NOT
--    EXISTS makes the re-run a no-op (and keeps the file splitter-safe — no
--    DO block / $$).
UPDATE digest_config
   SET require_dcc      = FALSE,
       require_rental   = FALSE,
       require_merchant = FALSE,
       require_trx      = TRUE
 WHERE NOT EXISTS (
       SELECT 1 FROM schema_migration_log
        WHERE filename = 'V2026_09_10_01__digest_trx_only_mandatory.sql');

-- 3. Unblock days already held only by the now-optional feeds. Clearing
--    waiting_on lets the next sweep re-gate them against transactions alone;
--    a day still missing transactions will simply be re-flagged 'TRX'.
UPDATE digest_dispatch
   SET waiting_on = NULL
 WHERE status = 'PENDING'
   AND waiting_on IS NOT NULL
   AND waiting_on NOT LIKE '%TRX%';

INSERT INTO schema_migration_log (filename) VALUES ('V2026_09_10_01__digest_trx_only_mandatory.sql') ON CONFLICT (filename) DO NOTHING;
