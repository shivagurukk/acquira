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
UPDATE digest_config
   SET require_dcc      = FALSE,
       require_rental   = FALSE,
       require_merchant = FALSE,
       require_trx      = TRUE;

-- 3. Unblock days already held only by the now-optional feeds. Clearing
--    waiting_on lets the next sweep re-gate them against transactions alone;
--    a day still missing transactions will simply be re-flagged 'TRX'.
UPDATE digest_dispatch
   SET waiting_on = NULL
 WHERE status = 'PENDING'
   AND waiting_on IS NOT NULL
   AND waiting_on NOT LIKE '%TRX%';
