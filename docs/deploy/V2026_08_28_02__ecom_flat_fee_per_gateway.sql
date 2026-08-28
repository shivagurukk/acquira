-- ============================================================================
-- V2026_08_28_02: ECOM flat fee (PG fee) split per payment gateway.
--
-- WHY
-- ---
-- ecom_flat_fee (V2026_07_31_06) holds ONE flat fee per country/tenant, applied
-- to every ECOM transaction regardless of which gateway carried it. The
-- business charges different PG fees per gateway — Benefit PG vs MPGS vs
-- Pay On must be configurable separately (user request 2026-08-28).
--
-- DESIGN
-- ------
-- * gateway_type = the RAW terminal type (dim_terminal.type, UPPERCASE), the
--   same token terminal_channel_map matches on ('BENEFIT PG','MPGS','PAY ON',
--   'ECOM PROFILE', ...). NULL = any-gateway fallback, i.e. every pre-existing
--   row keeps its exact old meaning.
-- * Resolution precedence in FeeComputationService: tenant override beats
--   country default (as for every other rate table); then exact gateway match,
--   then the row flagged is_default, then the NULL-gateway fallback.
-- * is_default marks the gateway every OTHER ECOM terminal type prices as.
--   Business rule (user, 2026-08-28): an ECOM transaction defaults to MPGS
--   unless its gateway has its own row — so BH's MPGS row is flagged default,
--   and AFS ONE / PAY BY LINK / ECOM PROFILE all take the MPGS fee.
-- * Seeds the three BH gateway rows at 0.1800 BHD — the SAME figure as the
--   old BH any-gateway flat fee (UAT_FIX_bh_pg_fee_2026-08-26), so pricing
--   is bit-for-bit unchanged until the business supplies the real per-gateway
--   figures; each row is now independently editable. The old BH NULL-gateway
--   row is DELETED: with MPGS as the default it is dead config, and keeping
--   two "default" rows invites divergent edits.
-- * AE deliberately untouched: its single NULL-gateway 0.18 AED row still
--   covers all its gateways (the resolver's last-resort branch); add gateway
--   rows + a default flag the same way when AE figures diverge.
-- * Editing a fee only affects NEW ingests — re-run the backfill for
--   already-loaded dates to re-stamp ecom_fee and rebuild summaries.
--
-- Idempotent; splitter-safe (no dollar-quoting, no DO blocks).
-- ============================================================================

ALTER TABLE ecom_flat_fee ADD COLUMN IF NOT EXISTS gateway_type VARCHAR(60) NULL;
ALTER TABLE ecom_flat_fee ADD COLUMN IF NOT EXISTS is_default BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN ecom_flat_fee.gateway_type IS
  'Raw terminal type (dim_terminal.type, UPPERCASE) this fee applies to, e.g. BENEFIT PG / MPGS / PAY ON. NULL = last-resort fallback for any ECOM gateway without its own row.';
COMMENT ON COLUMN ecom_flat_fee.is_default IS
  'TRUE = this gateway''s fee applies to every ECOM terminal type with no exact gateway_type row (business rule 2026-08-28: default gateway is MPGS). At most one default per country/tenant.';

-- Widen the uniqueness key: one row per (country, tenant, gateway),
-- and at most one default row per (country, tenant).
DROP INDEX IF EXISTS uq_ecom_flat_fee_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_ecom_flat_fee_gateway_key
    ON ecom_flat_fee (country_code, COALESCE(tenant_id, 0), COALESCE(gateway_type, '*'));
CREATE UNIQUE INDEX IF NOT EXISTS uq_ecom_flat_fee_one_default
    ON ecom_flat_fee (country_code, COALESCE(tenant_id, 0)) WHERE is_default;

-- Seed BH per-gateway rows at the current BH flat figure so behaviour is
-- unchanged until each figure is edited. Guarded: seeds once, never clobbers.
INSERT INTO ecom_flat_fee (tenant_id, country_code, gateway_type, fee_amount, label)
SELECT v.* FROM ( VALUES
  (NULL::INT, 'BH', 'BENEFIT PG', 0.1800, 'BH Benefit PG fee - seeded at old flat 0.18 BHD, awaiting real per-gateway figure'),
  (NULL,      'BH', 'MPGS',       0.1800, 'BH MPGS fee (DEFAULT for unlisted ECOM gateways) - seeded at old flat 0.18 BHD'),
  (NULL,      'BH', 'PAY ON',     0.1800, 'BH Pay On fee - seeded at old flat 0.18 BHD, awaiting real per-gateway figure')
) AS v(tenant_id, country_code, gateway_type, fee_amount, label)
WHERE NOT EXISTS (SELECT 1 FROM ecom_flat_fee x
                  WHERE x.country_code = v.country_code
                    AND x.tenant_id IS NULL
                    AND x.gateway_type = v.gateway_type);

-- MPGS is the default for BH ECOM. Guarded so a later deliberate move of the
-- default flag to another gateway is never forced back to MPGS by a re-run.
UPDATE ecom_flat_fee SET is_default = TRUE
 WHERE country_code = 'BH' AND tenant_id IS NULL AND gateway_type = 'MPGS'
   AND NOT EXISTS (SELECT 1 FROM ecom_flat_fee d
                   WHERE d.country_code = 'BH' AND d.tenant_id IS NULL AND d.is_default);

-- The old BH any-gateway row (UAT_FIX_bh_pg_fee_2026-08-26) is superseded by
-- the MPGS default; keeping it would leave two editable "defaults".
DELETE FROM ecom_flat_fee
 WHERE country_code = 'BH' AND tenant_id IS NULL AND gateway_type IS NULL;

-- Verify
SELECT country_code, tenant_id, gateway_type, is_default, fee_amount, label
FROM ecom_flat_fee ORDER BY country_code, COALESCE(gateway_type, '*');

INSERT INTO schema_migration_log (filename) VALUES ('V2026_08_28_02__ecom_flat_fee_per_gateway.sql') ON CONFLICT (filename) DO NOTHING;
