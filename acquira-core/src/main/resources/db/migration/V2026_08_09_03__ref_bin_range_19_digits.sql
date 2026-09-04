-- ============================================================================
-- V2026_08_09_03: ref_bin_range ranges normalized to 19 digits (MC + Visa).
--
-- Mastercard IP0040T1 account ranges are 19-digit (PAN-length, e.g.
-- 5102999070500000000-…0999999999); Visa BIN-list ranges are 9-digit
-- prefixes. To let ONE containment lookup serve both schemes, all ranges are
-- stored at 19 digits: a 9-digit Visa prefix range low L / high H becomes
-- L + '0000000000' and H + '9999999999'. Lookups pad the PAN prefix with
-- zeros to 19 and compare lexicographically (zero-padded fixed-width strings
-- compare identically to numerics).
--
-- LOAD SEMANTICS (business-confirmed 2026-08-09):
--   VISA* upload -> full refresh: DELETE all VISA rows, reinsert the file.
--   T068 upload  -> full refresh: DELETE all MASTERCARD rows, decode + insert.
--   T067 upload  -> DELTA ONLY: 'A' records upsert by (range_low, GCMS
--                   product), 'I' records remove that key. Never a mass
--                   delete. Test files T167/T168 stage only.
--
-- Idempotent + splitter-safe. The pad UPDATEs key on length(range_low)=9 so
-- re-runs (and reloads of already-padded data) are no-ops.
-- ============================================================================

ALTER TABLE ref_bin_range ALTER COLUMN range_low  TYPE VARCHAR(19);
ALTER TABLE ref_bin_range ALTER COLUMN range_high TYPE VARCHAR(19);

UPDATE ref_bin_range SET range_low  = range_low  || '0000000000' WHERE LENGTH(range_low)  = 9;
UPDATE ref_bin_range SET range_high = range_high || '9999999999' WHERE LENGTH(range_high) = 9;

-- MC upsert key: one row per (scheme, range_low, product). Partial unique
-- index scoped to MASTERCARD so the Visa full-refresh path (which may carry
-- legitimate duplicate lows across products with NULL product rows) is
-- unaffected.
CREATE UNIQUE INDEX IF NOT EXISTS uq_ref_bin_range_mc_key
    ON ref_bin_range (range_low, COALESCE(product_code, ''))
    WHERE scheme = 'MASTERCARD';

INSERT INTO schema_migration_log (filename) VALUES ('V2026_08_09_03__ref_bin_range_19_digits.sql') ON CONFLICT (filename) DO NOTHING;
