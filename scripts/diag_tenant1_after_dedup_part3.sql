-- ============================================================================
-- Tenant 1 recovery — PART 3 (READ-ONLY). Why did the store-based mapping
-- in part 2 return nothing? Either the stores were deleted and re-created
-- too (new store_ids), or the summaries carry no store_id. Send all output.
-- ============================================================================

-- 1. Stores: how many survive, and where do they point?
SELECT COUNT(*)                                                                    AS tenant1_stores,
       COUNT(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM dim_merchant m WHERE m.merchant_id = st.merchant_id)) AS stores_pointing_at_deleted_merchant,
       COUNT(*) FILTER (WHERE EXISTS (SELECT 1 FROM dim_merchant m WHERE m.merchant_id = st.merchant_id))     AS stores_pointing_at_live_merchant,
       MIN(store_id) AS min_store_id, MAX(store_id) AS max_store_id,
       MIN(created_date) AS oldest_created, MAX(created_date) AS newest_created
FROM dim_store st
WHERE st.tenant_id = 1;

-- 2. Do the summaries' store_ids still exist in dim_store?
SELECT COUNT(*)                                            AS insight_rows,
       COUNT(*) FILTER (WHERE s.store_id IS NULL)          AS rows_without_store,
       COUNT(*) FILTER (WHERE s.store_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM dim_store st WHERE st.store_id = s.store_id)) AS rows_with_deleted_store,
       COUNT(*) FILTER (WHERE s.store_id IS NOT NULL AND     EXISTS (SELECT 1 FROM dim_store st WHERE st.store_id = s.store_id)) AS rows_with_live_store
FROM sum_daily_insight s
WHERE s.tenant_id = 1 AND s.business_date >= DATE '2026-09-01';

-- 3. Same question for the facts (sample month to keep it fast).
SELECT COUNT(*) AS fact_rows,
       COUNT(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM dim_store st WHERE st.store_id = f.store_id)) AS rows_with_deleted_store,
       COUNT(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM dim_terminal t WHERE t.terminal_id = f.terminal_id)) AS rows_with_deleted_terminal
FROM fact_transaction f
WHERE f.tenant_id = 1 AND f.payment_date >= DATE '2026-09-01' AND f.payment_date < DATE '2026-10-01';

-- 4. Foreign keys that reference dim_merchant (part 2 Q4 was not pasted).
SELECT c.conname, c.conrelid::regclass AS tbl, c.condeferrable, c.confdeltype AS on_delete
FROM pg_constraint c
WHERE c.contype = 'f' AND c.confrelid = 'dim_merchant'::regclass
ORDER BY 2;

-- 5. Fallback mapping sources that pair an OLD merchant_id with a name:
--    email_campaign_log(merchant_id, merchant_name). Coverage + uniqueness.
SELECT COUNT(DISTINCT l.merchant_id) AS old_ids_in_campaign_log,
       COUNT(DISTINCT l.merchant_id) FILTER (WHERE NOT EXISTS (SELECT 1 FROM dim_merchant m WHERE m.merchant_id = l.merchant_id)) AS old_ids_deleted,
       COUNT(DISTINCT l.merchant_id) FILTER (WHERE
           (SELECT COUNT(*) FROM dim_merchant m WHERE m.tenant_id = 1 AND m.name = l.merchant_name) = 1) AS resolvable_by_unique_name
FROM email_campaign_log l
WHERE l.tenant_id = 1;

-- 6. email_queue: which columns does it have that could name the merchant?
SELECT column_name FROM information_schema.columns
WHERE table_name = 'email_queue' ORDER BY ordinal_position;

-- 7. Is the whole history still available at the source? Earliest tenant-1
--    fact date tells us how far back a full re-pull would need to go.
SELECT MIN(payment_date) AS earliest_fact, MAX(payment_date) AS latest_fact, COUNT(*) AS rows
FROM fact_transaction WHERE tenant_id = 1;
