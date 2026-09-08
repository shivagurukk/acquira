-- ============================================================================
-- V2026_08_10_04: business sign-off on the UAE-derived Bahrain/Egypt rates,
--                 plus currency-aware ticket-size buckets.
--
-- BUSINESS DECISIONS (user-confirmed 2026-08-11)
-- ----------------------------------------------
--   1. Bahrain and Egypt use the SAME SCHEME FEES as the UAE.
--   2. Bahrain Visa/MasterCard international, and ALL Egypt international
--      interchange, use 1.85% — the same figure as the UAE.
--
-- V2026_08_10_01 had flagged exactly these rows PLACEHOLDER, because a rate
-- copied from another country is indistinguishable from a deliberate choice
-- until somebody says which it is. The business has now said. The rows are
-- therefore promoted to APPROVED and, critically, the source_note records
-- WHY — 'BUSINESS-APPROVED …' rather than 'copy of the UAE grid'. That prefix
-- is load-bearing: V2026_08_10_01's placeholder sweep re-runs on every startup
-- and skips any row carrying it, so an approval can never be silently undone.
--
-- The distinction still matters operationally. These are now real production
-- rates that happen to equal the UAE's, NOT unreviewed leftovers, and the
-- provenance columns on fact_transaction will name the rule that priced each
-- transaction. When Bahrain or Egypt negotiates its own schedule, close these
-- rows with effective_to and insert the successor — do not edit in place.
--
--   3. Ticket-size buckets are no longer AED-calibrated constants.
--
-- Splitter-safe, idempotent.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. SCHEME FEES — Bahrain and Egypt adopt the UAE grid.
--    (DOMESTIC POS 0.11% / ECOM 0.14%, INTERNATIONAL POS 0.75% / ECOM 0.90%,
--     JCB + UnionPay 0.05%, per country x dest x channel x scheme.)
-- ----------------------------------------------------------------------------
UPDATE scheme_fee_rate
   SET rate_status = 'APPROVED',
       source_note = 'BUSINESS-APPROVED 2026-08-11: ' || country_code
                     || ' adopts the UAE scheme-fee grid by explicit decision.'
 WHERE country_code IN ('BH','EG')
   AND COALESCE(source_note, '') NOT LIKE 'BUSINESS-APPROVED%';

-- ----------------------------------------------------------------------------
-- 2. INTERNATIONAL INTERCHANGE — 1.85%, same as the UAE.
--    Scope note: scheme_group IS NULL is the any-scheme international row, so
--    this covers Bahraini Visa and MasterCard and every Egyptian scheme.
--    Bahrain's BENEFIT international row (1.10% + BHD 0.100) is scheme-specific
--    and at a higher priority, so it is untouched and still wins for BENEFIT.
-- ----------------------------------------------------------------------------
UPDATE interchange_rate_local
   SET rate_status = 'APPROVED',
       interchange_pct = 0.018500,
       source_note = 'BUSINESS-APPROVED 2026-08-11: ' || country_code
                     || ' international interchange set to 1.85%, same as UAE.'
 WHERE country_code IN ('BH','EG')
   AND dest = 'INTERNATIONAL'
   AND scheme_group IS NULL
   AND COALESCE(source_note, '') NOT LIKE 'BUSINESS-APPROVED%';

-- ----------------------------------------------------------------------------
-- 3. TICKET-SIZE BUCKETS — per country, in that country's own currency.
--
--    The buckets behind the merchant "transaction size" analytics were the
--    hardcoded constants < 50 / 50-100 / 100-250 / 250-500 / 500-1K / 1K-5K /
--    5K+, compared raw against store_base_currency_amount. Those numbers are
--    AED-shaped: 50 BHD is a substantial ticket while 50 EGP is a coffee, so
--    the same band meant three different things across the three tenants and
--    the distribution chart was not comparable to anything.
--
--    AE keeps its exact historical thresholds -> zero regression on the UAE
--    tenant. BH and EG get round numbers a local business user would
--    recognise. Flagged ASSUMPTION: these are sensible defaults, not a
--    business-supplied banding, and are meant to be edited in place.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ticket_size_bucket (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     INT NULL,
    country_code  VARCHAR(2) NOT NULL REFERENCES ref_country(country_code),
    seq           INT NOT NULL,
    min_amount    DECIMAL(19,4) NULL,
    max_amount    DECIMAL(19,4) NULL,
    label         VARCHAR(30) NOT NULL,
    note          VARCHAR(120) NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ticket_size_bucket
    ON ticket_size_bucket (country_code, COALESCE(tenant_id, 0), seq);

INSERT INTO ticket_size_bucket (tenant_id, country_code, seq, min_amount, max_amount, label, note)
SELECT v.* FROM ( VALUES
  -- UAE (AED) — byte-for-byte the pre-2026-08-11 hardcoded bands.
  (NULL::INT, 'AE', 1, NULL::NUMERIC,  50::NUMERIC,   '< 50',    'legacy hardcoded band'),
  (NULL, 'AE', 2,    50,    100,  '50-100',  'legacy hardcoded band'),
  (NULL, 'AE', 3,   100,    250,  '100-250', 'legacy hardcoded band'),
  (NULL, 'AE', 4,   250,    500,  '250-500', 'legacy hardcoded band'),
  (NULL, 'AE', 5,   500,   1000,  '500-1K',  'legacy hardcoded band'),
  (NULL, 'AE', 6,  1000,   5000,  '1K-5K',   'legacy hardcoded band'),
  (NULL, 'AE', 7,  5000,   NULL,  '5K+',     'legacy hardcoded band'),
  -- Bahrain (BHD) — ~1 BHD = ~10 AED, so bands are an order of magnitude lower.
  (NULL, 'BH', 1,  NULL,      5,  '< 5',     'ASSUMPTION - adjust to the BH business banding'),
  (NULL, 'BH', 2,     5,     10,  '5-10',    'ASSUMPTION - adjust to the BH business banding'),
  (NULL, 'BH', 3,    10,     25,  '10-25',   'ASSUMPTION - adjust to the BH business banding'),
  (NULL, 'BH', 4,    25,     50,  '25-50',   'ASSUMPTION - adjust to the BH business banding'),
  (NULL, 'BH', 5,    50,    100,  '50-100',  'ASSUMPTION - adjust to the BH business banding'),
  (NULL, 'BH', 6,   100,    500,  '100-500', 'ASSUMPTION - adjust to the BH business banding'),
  (NULL, 'BH', 7,   500,   NULL,  '500+',    'ASSUMPTION - adjust to the BH business banding'),
  -- Egypt (EGP) — ~1 AED = ~13 EGP, so bands are an order of magnitude higher.
  (NULL, 'EG', 1,  NULL,    500,  '< 500',    'ASSUMPTION - adjust to the EG business banding'),
  (NULL, 'EG', 2,   500,   1000,  '500-1K',   'ASSUMPTION - adjust to the EG business banding'),
  (NULL, 'EG', 3,  1000,   2500,  '1K-2.5K',  'ASSUMPTION - adjust to the EG business banding'),
  (NULL, 'EG', 4,  2500,   5000,  '2.5K-5K',  'ASSUMPTION - adjust to the EG business banding'),
  (NULL, 'EG', 5,  5000,  10000,  '5K-10K',   'ASSUMPTION - adjust to the EG business banding'),
  (NULL, 'EG', 6, 10000,  50000,  '10K-50K',  'ASSUMPTION - adjust to the EG business banding'),
  (NULL, 'EG', 7, 50000,   NULL,  '50K+',     'ASSUMPTION - adjust to the EG business banding')
) AS v(tenant_id, country_code, seq, min_amount, max_amount, label, note)
WHERE NOT EXISTS (SELECT 1 FROM ticket_size_bucket x
                  WHERE x.country_code = v.country_code AND x.seq = v.seq AND x.tenant_id IS NULL);

INSERT INTO schema_migration_log (filename)
VALUES ('V2026_08_10_04__approve_uae_derived_rates_and_ticket_buckets.sql')
ON CONFLICT (filename) DO NOTHING;
