-- ============================================================================
-- Local Debit Bank Dashboard — ADD or CORRECT a few BINs.
--
-- Use this when you need to add a handful of BINs (typically the ones the
-- dashboard's "Unmatched BIN worklist" is showing) without touching the full
-- 158-row seed in 02_seed_uae_bin_bank.sql.
--
-- HOW TO USE
--   1. Edit the VALUES block below: one line per BIN.
--        - BIN must be exactly 6 digits. Feeds only expose the first 6 clear
--          PAN digits, so an 8-digit BIN can never match — use its first 6.
--        - Bank name: keep the spelling identical to existing rows, or the
--          dashboard will show the same bank twice under two labels. Check with:
--            SELECT DISTINCT bank_name FROM ref_tenant_bin_bank WHERE tenant_id = <id>;
--   2. Run it against the target database with the tenant id:
--        psql -h <host> -p <port> -U <user> -d <db> \
--             -v ON_ERROR_STOP=1 -v tenant_id=1 -f 04_add_bins_template.sql
--
-- Re-running is safe: an existing BIN has its bank name updated in place.
-- Nothing else is deleted.
--
-- EFFECT IS IMMEDIATE AND RETROSPECTIVE: bank names are resolved when the
-- dashboard queries, so these BINs move out of "Other Banks" across ALL
-- history as soon as the page reloads. No summary rebuild is needed.
-- That is also why this is DBA-only — a wrong bank name here misattributes
-- every historical month at once.
-- ============================================================================

BEGIN;

INSERT INTO ref_tenant_bin_bank (tenant_id, bin, bank_name, source_file) VALUES
    -- ↓↓↓ EDIT THESE LINES ↓↓↓ (6-digit BIN, exact bank name)
    (:tenant_id, '601382', 'REPLACE WITH BANK NAME', 'manual-2026-08-20'),
    (:tenant_id, '123456', 'REPLACE WITH BANK NAME', 'manual-2026-08-20')
    -- ↑↑↑ add/remove lines as needed; comma between rows, none on the last ↑↑↑
ON CONFLICT (tenant_id, bin) DO UPDATE
    SET bank_name   = EXCLUDED.bank_name,
        source_file = EXCLUDED.source_file,
        loaded_at   = CURRENT_TIMESTAMP;

-- Safety net: refuse to commit if anything that is not 6 digits crept in.
DO $$
DECLARE bad INT;
BEGIN
    SELECT COUNT(*) INTO bad FROM ref_tenant_bin_bank WHERE bin !~ '^[0-9]{6}$';
    IF bad > 0 THEN
        RAISE EXCEPTION 'ref_tenant_bin_bank contains % row(s) whose BIN is not 6 digits — rolling back', bad;
    END IF;
END $$;

COMMIT;

-- What the tenant now has
SELECT COUNT(*) AS total_bins, COUNT(DISTINCT bank_name) AS distinct_banks
FROM ref_tenant_bin_bank WHERE tenant_id = :tenant_id;

-- Watch for accidental near-duplicate spellings of the same bank
SELECT bank_name, COUNT(*) AS bins
FROM ref_tenant_bin_bank WHERE tenant_id = :tenant_id
GROUP BY bank_name ORDER BY bank_name;
