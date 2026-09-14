-- ============================================================================
-- V2026_08_10_03: explicit per-column unit contract for transaction feeds.
--
-- WHY
-- ---
-- "CMM = minor units" was applied to only TWO of the six monetary columns.
-- txn_currency_amount and store_base_currency_amount were divided by the
-- currency's minor-unit divisor; msf, vat and total_amount_settled were read
-- raw; interchange_fee was divided by a magic 10000. Nothing recorded that
-- asymmetry, so it was impossible to tell whether a feed's MSF was intended as
-- minor units or major units — and getting it wrong is a 100x (EGP) or 1000x
-- (BHD) error in the revenue line, not a rounding nuance.
--
-- Observed live on an Egypt CMM test file: amounts divided correctly
-- (15050 -> 150.50) while MSF did not (225 stayed 225.00), producing an
-- Executive Dashboard reading "MSF EGP 7.2K on EGP 3.7K volume, net margin
-- 195.84%". Whichever side is wrong, the system could not say which.
--
-- This table makes the contract explicit and per-column, resolvable at three
-- levels: global default -> country -> tenant (most specific wins).
--
--   MINOR        value arrives in minor units; divide by the currency's
--                decimal_notation_value (100 for EGP/AED, 1000 for BHD).
--   MAJOR        value already carries final decimals; store as-is.
--   BASIS_10000  legacy fixed-point: divide by 10000 (interchange_fee today).
--
-- The seeded global defaults reproduce TODAY'S BEHAVIOUR EXACTLY, so this
-- migration changes no existing number. Onboarding a feed then becomes a
-- deliberate configuration step rather than an inherited assumption.
--
-- ACTION REQUIRED before Egypt go-live: confirm with the Egyptian processor
-- whether MSF / VAT / Total Amount Settled arrive in piastres (MINOR) or
-- pounds (MAJOR) and insert the EG rows accordingly. Same for Bahrain if it
-- ever moves off the AMS (major-units) format.
--
-- NOTE: tenant.input_format = 'AMS' still overrides everything to MAJOR — an
-- AMS feed carries final decimals in every column by definition.
--
-- Splitter-safe, idempotent.
-- ============================================================================

CREATE TABLE IF NOT EXISTS feed_amount_contract (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     INT NULL,
    country_code  VARCHAR(2) NULL REFERENCES ref_country(country_code),
    column_name   VARCHAR(40) NOT NULL,
    unit_mode     VARCHAR(20) NOT NULL,
    note          VARCHAR(160) NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_feed_amount_contract
    ON feed_amount_contract (COALESCE(tenant_id, 0), COALESCE(country_code, '*'), column_name);

-- Global defaults == the behaviour that shipped before 2026-08-10.
INSERT INTO feed_amount_contract (tenant_id, country_code, column_name, unit_mode, note)
SELECT v.* FROM ( VALUES
  (NULL::INT, NULL::VARCHAR, 'txn_currency_amount',        'MINOR',       'legacy default: divided at ingest'),
  (NULL, NULL, 'store_base_currency_amount', 'MINOR',       'legacy default: divided at ingest'),
  (NULL, NULL, 'total_amount_settled',       'MINOR',       'assumed consistent with the amount columns - CONFIRM per feed'),
  (NULL, NULL, 'msf',                        'MAJOR',       'legacy default: read raw, never divided'),
  (NULL, NULL, 'vat',                        'MAJOR',       'legacy default: read raw, never divided'),
  (NULL, NULL, 'interchange_fee',            'BASIS_10000', 'legacy default: divided by 10000')
) AS v(tenant_id, country_code, column_name, unit_mode, note)
WHERE NOT EXISTS (SELECT 1 FROM feed_amount_contract x
                  WHERE x.tenant_id IS NULL AND x.country_code IS NULL
                    AND x.column_name = v.column_name);

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_08_10_03__feed_amount_contract.sql')
ON CONFLICT (filename) DO NOTHING;
