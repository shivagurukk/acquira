-- ============================================================================
-- V2026_08_08_04: Backfill tenant.home_country_code from country/base_currency.
--
-- WHY
-- ---
-- home_country_code (added V2026_07_15_01, DEFAULT 'AE') selects the tenant's
-- COUNTRY RATE CARD in the fee-computation LATERALs. Until the companion code
-- change (Tenant.homeCountryCode + BankController + TenantManagement.jsx),
-- NOTHING could write it — every tenant, including a Bahrain one, sat on 'AE'
-- and priced off the UAE card. This migration corrects any tenant created
-- before the fix by inferring the country from the free-text country name or
-- the base currency. Matching is deliberately conservative: only unambiguous
-- signals, only for tenants still on the 'AE' default.
--
-- Idempotent: re-running matches nothing once corrected (the WHERE guards on
-- home_country_code = 'AE' plus a non-AED/foreign signal). Splitter-safe.
-- ============================================================================

-- NOTE: the legacy ACQ tenant is EXCLUDED. schema.sql seeds it with
-- country='Bahrain'/base_currency='BHD', but every fee migration since
-- V2026_07_05_01 seeds and prices it on the UAE card (AED caps, AED ticket
-- thresholds, bank_short_code='ACQ' seed target). Flipping it here would
-- silently reprice all of its history onto the BH card on the next re-ingest.
-- If ACQ is genuinely a Bahrain book, that flip must be a deliberate,
-- coordinated change (rate card + re-ingest + reporting sign-off), not a
-- backfill side effect.

UPDATE tenant SET home_country_code = 'BH'
WHERE home_country_code = 'AE'
  AND bank_short_code <> 'ACQ'
  AND (country ILIKE '%bahrain%' OR base_currency = 'BHD');

UPDATE tenant SET home_country_code = 'OM'
WHERE home_country_code = 'AE'
  AND bank_short_code <> 'ACQ'
  AND (country ILIKE '%oman%' OR base_currency = 'OMR');

UPDATE tenant SET home_country_code = 'EG'
WHERE home_country_code = 'AE'
  AND bank_short_code <> 'ACQ'
  AND (country ILIKE '%egypt%' OR base_currency = 'EGP');

INSERT INTO schema_migration_log (filename) VALUES ('V2026_08_08_04__tenant_home_country_backfill.sql') ON CONFLICT (filename) DO NOTHING;
