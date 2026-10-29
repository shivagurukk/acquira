-- ============================================================================
-- Tenant 1: move the re-pulled dim_merchant rows back onto their ORIGINAL
-- merchant_ids so the 91.6M facts and 1.4M summary rows join again.
--
-- DO NOT RUN until diag_tenant1_after_dedup_part2.sql shows:
--   * old_ids_with_2plus_new = 0 and new_ids_with_2plus_old = 0 (clean 1:1)
--   * unmapped_old_ids is small enough to accept (those stay orphaned)
--   * fact_transaction / sum_* rows referencing NEW ids = 0
--
-- Prefer an RDS point-in-time restore of dim_merchant (to 2026-10-08 before
-- the cleanup) if automated backups cover it: it also brings back the RM /
-- referral / risk / contact attributes, which this script cannot. This script
-- is the no-backup fallback. It ends with ROLLBACK; change to COMMIT only
-- after reading the check output.
-- ============================================================================
BEGIN;

-- 0. Hard guards — the whole transaction aborts if the mapping is not clean.
DROP TABLE IF EXISTS tmp_t1_map;
CREATE TEMP TABLE tmp_t1_map AS
WITH old_ids AS (
    SELECT DISTINCT s.merchant_id AS old_id, s.store_id
    FROM sum_daily_insight s
    WHERE s.tenant_id = 1 AND s.store_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM dim_merchant m WHERE m.merchant_id = s.merchant_id)
)
SELECT DISTINCT o.old_id, st.merchant_id AS new_id
FROM old_ids o
JOIN dim_store st ON st.store_id = o.store_id AND st.tenant_id = 1
JOIN dim_merchant nm ON nm.merchant_id = st.merchant_id AND nm.tenant_id = 1;

DO $$
DECLARE dup_old INT; dup_new INT; new_refs BIGINT;
BEGIN
  SELECT COUNT(*) - COUNT(DISTINCT old_id), COUNT(*) - COUNT(DISTINCT new_id)
    INTO dup_old, dup_new FROM tmp_t1_map;
  IF dup_old > 0 OR dup_new > 0 THEN
    RAISE EXCEPTION 'ABORT: mapping is not 1:1 (old dups=%, new dups=%) — inspect tmp_t1_map first', dup_old, dup_new;
  END IF;
  SELECT (SELECT COUNT(*) FROM fact_transaction f JOIN tmp_t1_map x ON x.new_id = f.merchant_id)
       + (SELECT COUNT(*) FROM sum_daily_merchant s JOIN tmp_t1_map x ON x.new_id = s.merchant_id)
       + (SELECT COUNT(*) FROM sum_daily_insight  s JOIN tmp_t1_map x ON x.new_id = s.merchant_id)
       + (SELECT COUNT(*) FROM sum_daily_terminal s JOIN tmp_t1_map x ON x.new_id = s.merchant_id)
    INTO new_refs;
  IF new_refs > 0 THEN
    RAISE EXCEPTION 'ABORT: % fact/summary rows already reference the NEW ids — needs a different plan', new_refs;
  END IF;
  RAISE NOTICE 'mapping ok: % merchants will be moved back to their original ids', (SELECT COUNT(*) FROM tmp_t1_map);
END $$;

-- 1. Let FK checks wait until COMMIT so the PK and its referrers can move together.
DO $$
DECLARE r RECORD;
BEGIN
  FOR r IN
    SELECT c.conname, c.conrelid::regclass AS tbl
    FROM pg_constraint c
    WHERE c.contype = 'f' AND c.confrelid = 'dim_merchant'::regclass AND NOT c.condeferrable
  LOOP
    EXECUTE format('ALTER TABLE %s ALTER CONSTRAINT %I DEFERRABLE INITIALLY IMMEDIATE', r.tbl, r.conname);
  END LOOP;
END $$;
SET CONSTRAINTS ALL DEFERRED;

-- 2. Move the merchant rows: new_id -> old_id (primary key update).
UPDATE dim_merchant m
SET    merchant_id = x.old_id
FROM   tmp_t1_map x
WHERE  m.merchant_id = x.new_id;

-- 3. Re-point everything that was created against the NEW ids (stores, and any
--    other FK table — generic so nothing is missed).
UPDATE dim_store d SET merchant_id = x.old_id FROM tmp_t1_map x WHERE d.merchant_id = x.new_id;

DO $$
DECLARE r RECORD; n BIGINT;
BEGIN
  FOR r IN
    SELECT DISTINCT c.conrelid::regclass AS tbl, a.attname AS col
    FROM pg_constraint c
    JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
    WHERE c.contype = 'f' AND c.confrelid = 'dim_merchant'::regclass
      AND c.conrelid <> 'dim_store'::regclass
  LOOP
    EXECUTE format('UPDATE %s t SET %I = x.old_id FROM tmp_t1_map x WHERE t.%I = x.new_id', r.tbl, r.col, r.col);
    GET DIAGNOSTICS n = ROW_COUNT;
    IF n > 0 THEN RAISE NOTICE 're-pointed % rows in %.%', n, r.tbl, r.col; END IF;
  END LOOP;
END $$;
UPDATE email_queue q SET merchant_id = x.old_id FROM tmp_t1_map x WHERE q.merchant_id = x.new_id;

-- 4. Checks — the two Sep-2026 figures below must now be (almost) equal.
SELECT 'sum_daily_bank' AS source, SUM(total_volume) AS volume, SUM(total_txns) AS txns
FROM sum_daily_bank WHERE tenant_id = 1 AND business_date BETWEEN DATE '2026-09-01' AND DATE '2026-09-30'
UNION ALL
SELECT 'sum_daily_merchant JOIN dim_merchant', SUM(s.total_volume), SUM(s.total_txns)
FROM sum_daily_merchant s JOIN dim_merchant m ON m.merchant_id = s.merchant_id AND m.tenant_id = s.tenant_id
WHERE s.tenant_id = 1 AND s.business_date BETWEEN DATE '2026-09-01' AND DATE '2026-09-30';

SELECT COUNT(DISTINCT s.merchant_id) AS still_orphaned_merchants
FROM sum_daily_merchant s
WHERE s.tenant_id = 1 AND NOT EXISTS (SELECT 1 FROM dim_merchant m WHERE m.merchant_id = s.merchant_id);

-- Read the output. If the two volumes match and still_orphaned is acceptable:
ROLLBACK;   -- change to COMMIT;
