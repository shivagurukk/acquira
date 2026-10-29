-- =============================================================================
-- QMR (Quarterly Merchant Report) figures for the UAE tenant
-- Section A: Visa QMR lines      Section B: Mastercard QMR lines
--
-- HOW TO RUN: edit the three values in the `p` CTE (tenant, quarter start,
-- quarter end) and run the whole file in one go. Output is one row per QMR
-- line with the figure and a note on the rule used.
--
-- DEFINITIONS (agree these with the business before submitting):
--   Merchant          = dim_merchant row for the tenant.
--   Location          = dim_store row (SID) for the tenant.
--   Acceptance point  = dim_terminal row (TID). Channel POS/ECOM is resolved
--                       exactly as the fee engine does it: terminal_channel_map
--                       for country AE on UPPER(TRIM(dim_terminal.type)), then
--                       the '*' wildcard row (POS for AE).
--   Card Present      = terminal channel POS; Card Not Present = channel ECOM.
--   Active            = at least one transaction in fact_transaction with
--                       payment_date inside the quarter.
--   New this quarter  = created_date (merchant: date_of_onboarding if set)
--                       inside the quarter.
--   Lost this quarter = transacted in the PREVIOUS quarter but not in this
--                       one, OR status is no longer ACTIVE at quarter end and
--                       the row was active-by-transactions last quarter.
--                       (dim_* has no closure date, so this is activity-based.)
--
-- DATA GAPS - the master feed carries NO chip / contactless / PIN-pad /
-- scheme-approval flags, so these lines use the stated assumptions:
--   Chip enabled, contactless enabled   = every POS terminal (UAE mandate).
--   Tap to Phone                        = POS terminal whose type or device
--                                         number matches the SOFTPOS/TAP
--                                         patterns in `t2p_pattern` below.
--   EMV chip with PIN pad (MC 7b)       = POS terminals minus Tap-to-Phone.
--   "MasterCard merchant" (MC 1)        = merchant with >=1 Mastercard
--                                         transaction in the quarter. The
--                                         "all merchants accept MC" count is
--                                         also returned for comparison.
-- =============================================================================

WITH p AS (
    SELECT
        -- UAE tenant: first tenant whose home country is AE. Replace with a
        -- literal tenant_id if there is more than one AE tenant.
        (SELECT tenant_id FROM tenant WHERE home_country_code = 'AE'
          ORDER BY tenant_id LIMIT 1)::bigint AS tenant_id,
        DATE '2026-07-01' AS q_start,   -- quarter start, inclusive
        DATE '2026-10-01' AS q_end      -- quarter end,   exclusive (first day of next quarter)
),
win AS (
    SELECT tenant_id, q_start, q_end,
           (q_start - INTERVAL '3 months')::date AS pq_start,   -- previous quarter
           q_start                               AS pq_end
    FROM p
),
t2p_pattern AS (
    SELECT '(SOFT ?POS|TAP ?(TO|ON) ?PHONE|T2P|TAP2PHONE|PHONE ?POS)' AS rx
),

-- ---------------------------------------------------------------- merchants
m AS (
    SELECT dm.merchant_id, dm.mid, dm.status,
           COALESCE(dm.date_of_onboarding, dm.created_date) AS onboarded
    FROM dim_merchant dm JOIN win ON dm.tenant_id = win.tenant_id
),
m_act AS (          -- merchants with activity this quarter / previous quarter
    SELECT f.merchant_id,
           BOOL_OR(f.payment_date >= win.q_start  AND f.payment_date < win.q_end)  AS cur_q,
           BOOL_OR(f.payment_date >= win.pq_start AND f.payment_date < win.pq_end) AS prev_q,
           BOOL_OR(f.payment_date >= win.q_start  AND f.payment_date < win.q_end
                   AND (UPPER(TRIM(f.card_scheme)) IN ('MASTERCARD','MASTER CARD','MC','MCI')
                        OR UPPER(TRIM(f.card_scheme)) LIKE 'MASTER%'))             AS cur_q_mc
    FROM fact_transaction f JOIN win ON f.tenant_id = win.tenant_id
    WHERE f.payment_date >= win.pq_start AND f.payment_date < win.q_end
    GROUP BY f.merchant_id
),

-- ---------------------------------------------------------------- locations
s AS (
    SELECT ds.store_id, ds.merchant_id, ds.sid, ds.status, ds.created_date
    FROM dim_store ds JOIN win ON ds.tenant_id = win.tenant_id
),
s_act AS (
    SELECT f.store_id,
           BOOL_OR(f.payment_date >= win.q_start  AND f.payment_date < win.q_end)  AS cur_q,
           BOOL_OR(f.payment_date >= win.pq_start AND f.payment_date < win.pq_end) AS prev_q
    FROM fact_transaction f JOIN win ON f.tenant_id = win.tenant_id
    WHERE f.payment_date >= win.pq_start AND f.payment_date < win.q_end
    GROUP BY f.store_id
),

-- ---------------------------------------------------------------- terminals
t AS (
    SELECT dt.terminal_id, dt.store_id, ds.merchant_id, dt.tid, dt.status, dt.created_date,
           UPPER(TRIM(COALESCE(dt.type, '')))          AS raw_type,
           UPPER(TRIM(COALESCE(dt.device_number, ''))) AS device,
           COALESCE(
             (SELECT m1.channel FROM terminal_channel_map m1
               WHERE m1.country_code = 'AE'
                 AND (m1.tenant_id IS NULL OR m1.tenant_id = win.tenant_id)
                 AND m1.raw_type = UPPER(TRIM(COALESCE(dt.type, '')))
               ORDER BY (m1.tenant_id IS NOT NULL) DESC LIMIT 1),
             (SELECT m2.channel FROM terminal_channel_map m2
               WHERE m2.country_code = 'AE'
                 AND (m2.tenant_id IS NULL OR m2.tenant_id = win.tenant_id)
                 AND m2.raw_type = '*'
               ORDER BY (m2.tenant_id IS NOT NULL) DESC LIMIT 1),
             'POS') AS channel
    FROM dim_terminal dt
    JOIN dim_store ds ON ds.store_id = dt.store_id
    JOIN win ON dt.tenant_id = win.tenant_id
),
t2 AS (
    SELECT t.*,
           (t.channel = 'POS') AS is_cp,
           (t.channel = 'POS' AND (t.raw_type ~ (SELECT rx FROM t2p_pattern)
                                OR t.device   ~ (SELECT rx FROM t2p_pattern))) AS is_t2p
    FROM t
),
t_act AS (
    SELECT f.terminal_id
    FROM fact_transaction f JOIN win ON f.tenant_id = win.tenant_id
    WHERE f.payment_date >= win.q_start AND f.payment_date < win.q_end
    GROUP BY f.terminal_id
),
s_chan AS (        -- per location: has a CP terminal? has a CNP terminal?
    SELECT s.store_id,
           BOOL_OR(t2.channel = 'POS')  AS has_cp,
           BOOL_OR(t2.channel = 'ECOM') AS has_cnp
    FROM s LEFT JOIN t2 ON t2.store_id = s.store_id
    GROUP BY s.store_id
),

-- ---------------------------------------------------------------- figures
fig AS (
    SELECT 'A' AS sec, 1 AS line, 'Total Number of Merchants managed by Acquirer' AS item,
           COUNT(*) AS value, 'all dim_merchant rows for tenant' AS rule FROM m
    UNION ALL SELECT 'A', 2, 'Total Number of Merchant Locations managed by Acquirer',
           COUNT(*), 'all dim_store rows' FROM s
    UNION ALL SELECT 'A', 3, 'Number of Active Merchant Locations managed by Acquirer',
           COUNT(*), 'store with >=1 txn in quarter' FROM s JOIN s_act ON s_act.store_id = s.store_id AND s_act.cur_q
    UNION ALL SELECT 'A', 4, 'Total Number of Card Not Present Merchant Locations',
           COUNT(*), 'store with >=1 ECOM-channel terminal' FROM s_chan WHERE has_cnp
    UNION ALL SELECT 'A', 5, 'Number of Active Card Not Present Merchant Locations',
           COUNT(*), 'CNP store with >=1 txn in quarter'
           FROM s_chan JOIN s_act ON s_act.store_id = s_chan.store_id AND s_act.cur_q WHERE has_cnp
    UNION ALL SELECT 'A', 6, 'Total Number of Card Present Merchant Locations',
           COUNT(*), 'store with >=1 POS-channel terminal' FROM s_chan WHERE has_cp
    UNION ALL SELECT 'A', 7, 'Total Number of Card Present Merchant Acceptance Points',
           COUNT(*), 'POS-channel terminals' FROM t2 WHERE is_cp
    UNION ALL SELECT 'A', 8, 'Number of Card Present Merchant Acceptance Points - Chip enabled',
           COUNT(*), 'ASSUMPTION: every POS terminal is chip enabled' FROM t2 WHERE is_cp
    UNION ALL SELECT 'A', 9, 'Number of Card Present Merchant Acceptance Points - Contactless enabled',
           COUNT(*), 'ASSUMPTION: every POS terminal is contactless enabled' FROM t2 WHERE is_cp
    UNION ALL SELECT 'A', 10, 'Number of Card Present Merchant Acceptance Points - Tap to Phone enabled',
           COUNT(*), 'POS terminal whose type/device matches SOFTPOS/TAP-TO-PHONE pattern' FROM t2 WHERE is_t2p
    UNION ALL SELECT 'A', 11, 'Number of Active Card Present Merchant Acceptance Points',
           COUNT(*), 'POS terminal with >=1 txn in quarter' FROM t2 JOIN t_act ON t_act.terminal_id = t2.terminal_id WHERE is_cp

    UNION ALL SELECT 'B', 1, 'Number of MasterCard merchant',
           COUNT(*), 'merchant with >=1 Mastercard txn in quarter' FROM m_act WHERE cur_q_mc
    UNION ALL SELECT 'B', 1, 'Number of MasterCard merchant (alt: all merchants accept MC)',
           COUNT(*), 'all merchants with >=1 txn of any scheme in quarter' FROM m_act WHERE cur_q
    UNION ALL SELECT 'B', 2, 'Number of new merchant added this quarter',
           COUNT(*), 'date_of_onboarding/created_date inside quarter'
           FROM m JOIN win ON TRUE WHERE m.onboarded >= win.q_start AND m.onboarded < win.q_end
    UNION ALL SELECT 'B', 3, 'Number of merchant lost this quarter',
           COUNT(*), 'transacted previous quarter, none this quarter'
           FROM m_act WHERE prev_q AND NOT cur_q
    UNION ALL SELECT 'B', 4, 'Total merchant location',
           COUNT(*), 'all dim_store rows' FROM s
    UNION ALL SELECT 'B', 5, 'Number of new merchant location added this quarter',
           COUNT(*), 'dim_store.created_date inside quarter'
           FROM s JOIN win ON TRUE WHERE s.created_date >= win.q_start AND s.created_date < win.q_end
    UNION ALL SELECT 'B', 6, 'Number of merchant location lost this quarter',
           COUNT(*), 'store transacted previous quarter, none this quarter'
           FROM s_act WHERE prev_q AND NOT cur_q
    UNION ALL SELECT 'B', 7, 'Number of POS terminals at your merchant location',
           COUNT(*), 'POS-channel terminals' FROM t2 WHERE is_cp
    UNION ALL SELECT 'B', 7, '7a. MasterCard-approved EMV chip terminal (with or without PIN pad)',
           COUNT(*), 'ASSUMPTION: every POS terminal is EMV chip' FROM t2 WHERE is_cp
    UNION ALL SELECT 'B', 7, '7b. MasterCard-approved EMV chip terminal with a PIN pad',
           COUNT(*), 'ASSUMPTION: POS terminals minus Tap-to-Phone' FROM t2 WHERE is_cp AND NOT is_t2p
    UNION ALL SELECT 'B', 8, 'Number of merchant location accepting Mastercard contactless',
           COUNT(*), 'ASSUMPTION: every CP location is contactless' FROM s_chan WHERE has_cp
    UNION ALL SELECT 'B', 9, 'Number of MasterCard-approved contactless terminals',
           COUNT(*), 'ASSUMPTION: every POS terminal is contactless' FROM t2 WHERE is_cp
)
SELECT CASE sec WHEN 'A' THEN 'VISA' ELSE 'MASTERCARD' END AS qmr,
       line, item, value, rule
FROM fig
ORDER BY sec, line, item;


-- =============================================================================
-- SUPPORTING CHECKS - run these first so the assumptions above can be judged.
-- =============================================================================

-- 1. What terminal types does the UAE tenant actually have, and how does each
--    resolve (POS / ECOM)? Any type that should be CNP but resolves to POS
--    needs a terminal_channel_map row before the figures are trusted.
-- SELECT UPPER(TRIM(COALESCE(dt.type,''))) AS raw_type,
--        COUNT(*) AS terminals,
--        COALESCE((SELECT channel FROM terminal_channel_map
--                   WHERE country_code='AE' AND raw_type = UPPER(TRIM(COALESCE(dt.type,''))) LIMIT 1),
--                 '* -> POS') AS resolves_to
-- FROM dim_terminal dt
-- WHERE dt.tenant_id = (SELECT tenant_id FROM tenant WHERE home_country_code='AE' ORDER BY tenant_id LIMIT 1)
-- GROUP BY 1 ORDER BY 2 DESC;

-- 2. Status values on merchants / stores / terminals (to decide whether a
--    status-based "lost" rule should replace the activity-based one).
-- SELECT 'merchant' AS tbl, status, COUNT(*) FROM dim_merchant WHERE tenant_id = <AE_TENANT> GROUP BY 2
-- UNION ALL SELECT 'store', status, COUNT(*) FROM dim_store WHERE tenant_id = <AE_TENANT> GROUP BY 2
-- UNION ALL SELECT 'terminal', status, COUNT(*) FROM dim_terminal WHERE tenant_id = <AE_TENANT> GROUP BY 2
-- ORDER BY 1, 3 DESC;

-- 3. Card scheme spellings seen in the quarter (to confirm the Mastercard match).
-- SELECT UPPER(TRIM(card_scheme)) AS scheme, COUNT(*)
-- FROM fact_transaction
-- WHERE tenant_id = <AE_TENANT> AND payment_date >= DATE '2026-07-01' AND payment_date < DATE '2026-10-01'
-- GROUP BY 1 ORDER BY 2 DESC;
