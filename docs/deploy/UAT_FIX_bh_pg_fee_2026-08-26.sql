-- ============================================================================
-- UAT FIX: BH PG fee (ECOM flat fee) — same figure as UAE, per user 2026-08-26.
--
-- The ingest charges this flat amount per ECOM transaction in the tenant's
-- SETTLEMENT currency (V2026_07_31_06). UAE's row is 0.18 (AED); this seeds
-- 0.18 for BH, which means 0.18 BHD per ECOM txn.
-- NOTE: 0.18 BHD is ~10x the value of 0.18 AED. If the business intends the
-- same *value* as UAE rather than the same *number*, use 0.0185 instead.
--
-- Idempotent: seeds once, never clobbers a later in-UI edit.
-- Applies to NEW ingests only — re-run the backfill for already-loaded dates
-- to stamp ecom_fee on existing rows and rebuild summaries.
--
-- Benefit / Benefit QR SCHEME FEE: intentionally still unseeded — rate to be
-- confirmed by the business (expected 2026-08-27).
-- ============================================================================

INSERT INTO ecom_flat_fee (tenant_id, country_code, fee_amount, label)
SELECT NULL, 'BH', 0.1800, 'BH ECOM flat 0.18 (BHD) - same figure as UAE, user-confirmed 2026-08-26'
WHERE NOT EXISTS (SELECT 1 FROM ecom_flat_fee WHERE country_code = 'BH' AND tenant_id IS NULL);

-- Verify
SELECT country_code, tenant_id, fee_amount, label FROM ecom_flat_fee ORDER BY country_code;
