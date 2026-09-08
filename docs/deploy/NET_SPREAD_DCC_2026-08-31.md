# Net Spread + DCC revenue feed — deploy kit (2026-08-31)

## What ships

1. **DCC revenue ingestion** — dedicated file at SID level
   (`SID, Tenant Id, Merchant Share, Acquirer Share, Date`, tenant base
   currency, header-name mapped). All three channels: screen upload, Server
   File Processor, scheduled integration pull (`ReportType.DCC`).
   **REPLACE-BY-DATE**: re-uploading a day wipes and reloads exactly that
   tenant's `fact_dcc_revenue` rows for the dates in the file.
   Detection marker: a header containing `Acquirer Share` (checked before the
   transaction/merchant checks). The file's `Tenant Id` column (short code,
   institution id, or numeric tenant id) is validated per row — mismatch →
   REJECTED in `stg_dcc_revenue_raw`, surfaced via
   `GET /api/business/net-spread/dcc-status`.

2. **Ancillary summary columns** — `dcc_acquirer`, `dcc_merchant`,
   `rental_amount` on `sum_daily_merchant` and `sum_daily_finance_rollup`,
   always re-derived from `fact_dcc_revenue` / `fact_rental` by the shared
   `AncillarySql` (acquira-common):
   - after every summary rebuild (`SummaryPopulationService.populateSummary`);
   - inside `FinanceRollupSql.rebuildRange` (its clean-slate delete);
   - after every DCC and rental apply (which also create zero-transaction
     merchant-day rows for ancillary-only days, and clean them up again when
     a replace removes them).
   `net_spread = total_margin + dcc_acquirer + rental_amount` is DERIVED at
   read time, never stored. Dimensional summaries (sum_daily_full etc.)
   deliberately do NOT carry these columns (grain rule).
   Rental + DCC jobs now also clear the report caches (CacheEvictionJobListener).

3. **Net Spread dashboard** — `/executive/net-spread`, replica of the
   Executive Daily Merchant layout at MERCHANT grain over sum_daily_merchant:
   columns … NM | DCC (Acquirer) | Rental | **Net Spread**, rescued badge
   (margin < 0, spread ≥ 0), rescue-count band, spread ribbon, margin-loss
   lens, CSV, USD toggle. Original daily-merchant page untouched.

## Prod steps

1. `psql` the two migrations, in order:
   - `db/migration/V2026_08_31_01__dcc_revenue.sql` (tables + summary columns
     + one-time seed of rental history into the new columns)
   - `db/migration/V2026_08_31_02__net_spread_menu.sql` (menu + grants)
2. `mvn -DskipTests install`, restart acquira-core and acquira-batch.
3. Rebuild + redeploy the frontend (lazyWithReload covers stale chunks).

## Verify on UAT

1. Upload a DCC file → detection says DCC, rows PROCESSED, dashboard shows
   the acquirer share for those dates.
2. Re-upload the SAME dates with different numbers → figures replace exactly
   (no accumulation). Upload the same file twice → idempotent.
3. Row with wrong Tenant Id → REJECTED; unknown SID → UNMATCHED (both visible
   in /net-spread/dcc-status); UNMATCHED applies on a later load after the
   merchant master catches up.
4. Rental/DCC on a day with no transactions → still appears in the totals
   (zero-volume summary row).
5. Run a summary rebuild (or re-ingest a transaction day) → DCC/rental
   figures survive.
6. Reconcile the page totals and CSV against the file by hand; check the
   rescued count matches merchants negative on NM but positive on spread.

## Phase C (2026-09-02) — Net Spread on every executive screen

Net Spread = **net margin + DCC (acquirer share) + rental**, derived at read
time from the summary columns AncillarySql maintains — never stored. One
shared SQL definition now backs every page:
`acquira-common/.../service/NetSpreadSql.java` (`margin`, `ancillary`,
`spread` + SUM variants over a `sum_daily_merchant` alias).

| Screen | Source of the ancillary lines | What changed |
|---|---|---|
| Executive Dashboard `/dashboard` | `sum_daily_finance_rollup` (tenant-day), bucketed by week / month_key | every bucket + totals + prev + run-rate carry `dccAcquirer`, `rental`, `netSpread`, `spreadPct`; hero tile, rail metrics, table columns, CSV |
| Volume & Revenue `/business/ceo-volume-revenue` | facts by (merchant, store) — DCC is SID-keyed, rental at store/terminal level | per-row `dccAcquirer` / `rental` / `netSpread` / `spreadPct` / `rescued`; totals + `rescuedRows` + `unattributedAncillary` (merchant-level rental with no SID, shown in the tile caption, not in rows); sort keys `dcc`, `rental`, `spread`; CSV |
| Loss-Making `/business/loss-making` | `sum_daily_merchant` at MID grain (complete) | same payload; loss filter stays on net margin, rows a DCC/rental line pulls back to ≥ 0 carry a RESCUED chip |
| Top Performers `/business/top-performers` | `sum_daily_merchant` (card-filter path: scalar subquery per merchant) | `netSpread` on every merchant row, `topMerchantsByNetSpread`, `topRmsByNetSpread`, `concentration.totalNetSpread` |
| Sales Hierarchy `/sales/executive` (+ agent/team/country) | `sum_daily_merchant` | `totalSpread` / `prevSpread` / `spreadRate` / `spreadChangePct` on every node; `spread` on drill-down merchants, agents, teams, monthly trend |
| Sales Pulse `/executive/sales` | `sum_daily_merchant` | `spread` / `previousSpread` / `spreadGrowthPct` per executive, `teamSpread` per team, `summary.totalSpread`; momentum + signals still rank on `sales` |

**Net-margin definition aligned.** Top Performers, Sales Hierarchy, Sales
Pulse and the Sales Leaderboard used an inline 3-leg
`msf − interchange − scheme_fee`; they now read the batch 4-leg
`total_margin` (which also subtracts the PG/ecom fee) like the Executive
Dashboard, Volume & Revenue, Daily Merchant and Net Spread pages. Expect
those four sales screens to read LOWER by exactly the PG fee for merchants
with e-commerce volume — that is the correction, not a regression.

Not changed: Daily Merchant Performance (dimension-sliced `sum_daily_full`
has no ancillary columns by design — the Net Spread page is its merchant-grain
replica) and Attrition (carries no margin measure at all).

No migration. Deploy = `mvn install` (acquira-common changed) + restart core +
frontend rebuild. Verify: Executive Dashboard MTD Net Spread tile =
Net Spread page totals for the same dates; Loss-Making rescued count = Net
Spread page rescued count.

## Phase C.2 (2026-09-02, same day) — polish + follow-through

1. Executive Dashboard: hero back to FOUR tiles — Net Spread is the second
   line of the Net Margin tile; table folds DCC + Rental into one
   "Ancillary" column (hover shows the split); Net Spread rail metric with %.
2. Volume & Revenue: merchant-level rental/DCC (no SID) is now **split
   evenly across the merchant's trading stores** in the window, so the
   store view sums to the merchant view. `totals.allocatedAncillary` says
   how much was split; `totals.unattributedAncillary` is now only merchants
   with NO trading store (no row to land on). Stated once under the KPI band
   with links to Loss-Making / Net Spread. Rule chosen: even split (rent has
   no volume basis; a weighted split would move rent between stores monthly).
3. Loss-Making: "N RESCUED" pill on the Loss Rows tile.
4. Sales Pulse: Net Spread is a second headline with its own delta; the
   monthly trend chart has a Net Margin / Net Spread toggle (`orgSeries[].spread`
   from a second `monthlySpreadSeries` scan). The whole page is now
   ReportCache-cached per (tenant, period, filters) and warmed for MTD.
5. Sales Hierarchy: Net Spread KPI uses the ancillary hue + spread % of
   volume; new "Δ Spread" column.
6. Top Performers: boards grouped into tabs (Volume / Net Margin /
   Net Spread / Transactions & Signings) — in-memory switch, no refetch.
7. Shared glossary: `frontend/src/components/MarginGlossary.jsx` (one text
   for net margin / ancillary / net spread / rescued / %), rendered as an
   info hint on Dashboard, Volume & Revenue, Top Performers, Sales
   Hierarchy, Sales Pulse and Net Spread.
8. Colour: `--mix-ancillary` (plum, cat-5) in index.css (both modes) and
   `SERIES.ancillary` / `SERIES.netSpread` in chartPalette — used for every
   spread/ancillary figure so it never reads as margin-green.
9. Daily Merchant: "Net Spread for these days →" masthead button deep-links
   to `/executive/net-spread?month=&dates=` (page reads the params on first
   load, then clears them).
10. Attrition: fourth Measure "Net Spread" (`*_spread` keys on every window,
    indices 34–42 of the report row); greyed with `meta.spreadAvailable=false`
    on the card/store-filtered (insight) route.
11. Merchant PDF (P12 DCC): `SumDailyMerchant` now maps dcc_acquirer /
    dcc_merchant / rental_amount; `buildDccPerformance` sets
    dccRevenueGenerated / optInRevenue = merchant share from the feed,
    missed revenue = opt-out volume × the merchant's OWN measured rate, and
    new DTO fields dccMerchantRevenue / dccAcquirerRevenue / rentalIncome /
    dccRevenueSource ("FEED"|"NONE"). p12-dcc.html shows a "Your DCC
    Earnings" hero cell ONLY when source = FEED. The 3% constant is gone.

Deploy: `mvn install` (common + pdf templates changed), restart core,
rebuild frontend. No migration.

## Ingest hardening (2026-09-03) — merchant upsert, re-price safety, folder runs

Scope chosen by the user: fix audit items 3, 4, 5; leave load-mode/JCB code and the
concurrent-ingest guard untouched (single-file uploads only).

**Merchant master upload (MerchantMasterJobConfig)**
- Key canonicalisation pre-pass on staging: one internal id per MID / (MID,SID) /
  (MID,TID) inside the file, and an EXISTING dim keeps its stored key (matched by
  MID / SID-under-MID / TID-under-MID, whitespace-insensitive, AUTO_ rows excluded).
  A blank MerchantInternalId no longer creates a second dim_merchant and re-parents
  stores.
- GROUP BY = ON CONFLICT key on all three upserts (mid/sid/tid via MAX) — a
  trailing-space TID no longer aborts the whole upsert. TID now normalised like MID/SID.
- Status: SELECT no longer defaults to 'ACTIVE', so a blank status leaves the
  existing status alone; new rows get ACTIVE via a fix-up UPDATE.
- dim_bank_account OR-join rewritten as two LATERAL probes (the 7-minute pattern).
- skipped.noMid / skipped.terminalNoStore counts on the job context.
- Verified live (tenant 8): blank-internal-id re-upload updated merchant 3 in place,
  status kept 'Open'; 'Closed' applied where stated; 'T900' + 'T900 ' collapsed to
  one terminal; stores mapped onto existing 14821/14822 keys.

**Re-price (BulkMigrationService + FeeComputationService)**
- `computeFeesPreserving`: a row the current cards cannot price (NULL) keeps its
  previous fee/status/rule instead of being blanked; count reported as
  `preservedFeeRows` in /progress and in the UI.
- Only trading days are re-priced (no empty-day temp tables); `lastRepricedDay`
  in /progress; on failure the touched months' summaries are re-synced to fact
  and the phase says where it stopped.
- Fact partition ANALYZE resolved via pg_inherits (works under tenant-wise
  partitioning); MIN/MAX bounds sargable.
- Verified live: Aug-2026 re-price COMPLETED, lastRepricedDay 2026-08-26,
  summaries + rental columns unchanged afterwards.

**Server folder (FileUploadService)**
- One workbook scan per file (tenant read from the classification scan).
- A tenant whose MERCHANT file did not COMPLETE has its transaction/rental/DCC
  files SKIPPED_UPSTREAM_FAILURE; a job still running at the 6 h ceiling returns
  TIMED_OUT_STILL_RUNNING and the remaining files are SKIPPED_PREVIOUS_STILL_RUNNING
  (nothing launched on top of it).
- Size/mtime stability check (2 s) skips files still being written.
- Processed files move to <folder>/processed/<yyyyMMdd>/ (failed → failed/);
  `app.upload.archive-processed=false` to disable. Re-running the same path is
  now a no-op. Poll backoff 1s→5s→15s.
- Verified live: folder run archived both files; second run found nothing.
  Not exercised live: the upstream-failure skip and the timeout abort paths.
