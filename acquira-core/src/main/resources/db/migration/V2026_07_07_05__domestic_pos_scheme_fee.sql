-- ============================================================================
-- V2026_07_07_05: Domestic POS scheme fee 0.12% -> 0.11%.
--
-- Business change: DOMESTIC POS scheme fee for the card networks
-- (Visa / MasterCard / Amex / unmapped-wildcard) moves 0.12% -> 0.11%.
-- JCB / UnionPay stay on their flat 0.05% (untouched). All other
-- dest x channel cells (domestic ECOM, international POS/ECOM) unchanged.
--
-- Idempotent absolute-value UPDATE (safe to re-run; second run is a no-op
-- because the rows already hold 0.001100). Splitter-safe (no dollar-quoting),
-- so it may be listed in schema-locations. On prod (sql.init mode=never)
-- apply once via psql.
--
-- NOTE: also update the seed literals in V2026_07_07_01 (scheme-fee reseed
-- block, DOMESTIC POS 0.001200 -> 0.001100) and in schema.sql's merged fee
-- block, so a fresh DB seeds 0.11% directly and this UPDATE stays a no-op.
--
-- BACKFILL: scheme fee is computed at ingest - re-upload affected months to
-- recompute with the new rate.
-- ============================================================================

UPDATE scheme_fee_rate s
SET fee_pct = 0.001100
FROM tenant t
WHERE s.tenant_id = t.tenant_id
  AND t.bank_short_code = 'ACQ'
  AND s.dest = 'DOMESTIC'
  AND s.channel = 'POS'
  AND (s.scheme_group IN ('Visa', 'MasterCard', 'Amex') OR s.scheme_group IS NULL)
  AND s.fee_pct = 0.001200;
