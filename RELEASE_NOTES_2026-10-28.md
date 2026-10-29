# Acquira release drop — 2026-10-28

Contents of this folder: every file in `E:\Acquira UAT\UAT\external\acquira` that is
not yet committed to git (branch `deploy/kubernetes-aws`, base commit `5a78ea4`),
copied with the repository paths kept. 83 files. The three local backup `.zip`
archives were left out.

Legend: **M** = modified tracked file, **N** = new file.
Every SQL under `db/migration` dated September or later is applied by hand with
psql; none is registered in application.properties.

---

## 1. Volume & Revenue Mix (new Executive screen) — 2026-10-28

The Volume & Revenue detail (SID, MID, Merchant and the full fee stack) with three
more key columns: Destination, MCC (with category) and Scheme. Every row is one
SID × MID × Destination × MCC × Scheme over the period. Reads `sum_daily_full`, so
the total ties out with Volume & Revenue for the same period and channel. No Net
Spread column (DCC/rental/FX income has no destination, MCC or scheme).

| | File | What it is |
|---|---|---|
| N | `acquira-core/.../controller/VolumeRevenueMixController.java` | `GET /api/business/volume-revenue-mix` — mode MTD/YTD/This Month/month, channel, search (name/MID/SID/destination/MCC/category/scheme), sort, paging, CSV export; cached and pre-warmed like the other executive reports. |
| N | `frontend/src/pages/executive/VolumeRevenueMix.jsx` | The page, same layout as Volume & Revenue: KPI band, table with SID / MID / Merchant / Destination / MCC / MCC Category / Scheme / Count / Volume / MSF / Interchange / Scheme Fee / ECOM Fee / Net Margin / Net Margin %, totals row, CSV export, USD toggle support. |
| N | `acquira-core/.../db/migration/V2026_10_28_03__volume_revenue_mix_menu.sql` | sys_menu row `/executive/volume-revenue-mix` (EXECUTIVE, order 10) and grants for Super Admin / Bank Admin. RbacCatalogSync inserts the same at boot; the script is for databases that are not restarted. |
| M | `acquira-core/src/main/resources/rbac/menu-catalog.json` | Catalog entry `executive.volume-revenue-mix` (icon PieChart, actions VIEW/EXPORT, grantView Bank Admin + Super Admin). |
| M | `frontend/src/App.jsx` | Lazy import and route for the Mix page. **Also** carries the Profitability Report route (section 3). |
| M | `frontend/src/components/Layout.jsx` | Mix path added to the Executive USD-toggle list. **Also** carries the daily-rate toggle changes (section 2). |

## 2. EGP → USD daily rates and Executive screens — 2026-10-28

Egypt's currency floats, so the executive USD toggle now converts every figure at
the bank's own USD rate for each business date (previous available day's when a
date has none), instead of one hard-coded rate. Pegged Gulf currencies are unchanged.
Design and decisions: `docs/PLAN_FX_DAILY_RATES_EGP_USD_2026-10-28.md`.

| | File | What it is |
|---|---|---|
| N | `acquira-core/.../db/migration/V2026_10_28_01__fx_rate_daily.sql` | Creates `fx_rate_daily` (currency, date, units per USD, source, audit columns) and the staging table `stg_fx_rate_raw`. |
| N | `acquira-core/.../db/migration/V2026_10_28_02__fx_rate_daily_egp_seed.sql` | 1,833 historical EGP rows from the bank's MPS export (2018-12-11 → 2026-10-27), idempotent. |
| N | `acquira-common/.../service/FxRateService.java` | Rate lookup: rate on the date, else the latest earlier one; period rate = volume-weighted rate of the days in the period; in-memory cache with invalidate. |
| N | `acquira-common/src/test/.../FxRateServiceTest.java` | Unit tests for the carry-forward and weighted-period rules. |
| N | `acquira-core/.../controller/FxRateController.java` | `GET /api/fx/series?from&to` — per-day rate series plus daily volume for the current tenant; flat series for pegged currencies; 204 when nothing is loaded. |
| M | `acquira-common/.../service/FxRates.java` | EGP constant removed (a constant would silently win over the daily table); AS_OF stamp now 2026-10-28 and only covers pegged currencies. |
| M | `acquira-common/.../model/IntegrationReport.java` | New report type `FX_RATE`. |
| M | `acquira-batch/.../service/IntegrationPullService.java` | FX_RATE pull: stages MPS rows (`MAR_BUY_RATE`, `MAR_RATE_DATE`), averages same-date rows, upserts into `fx_rate_daily` without touching MANUAL rows, no batch job. |
| M | `acquira-core/.../service/DigestEmailService.java` | Daily digest USD annotations use the rate of the digest's business date; footer names the rate and date used (or says USD is omitted). |
| N | `frontend/src/hooks/useFxSeries.js` | Page hook: loads the daily series for the dates a page shows and hands it to the formatters; no network for pegged or USD tenants. |
| M | `frontend/src/config/fxRates.js` | EGP removed from the pegged constants; stamp 2026-10-28. |
| M | `frontend/src/utils/formatters.js` | Display layer converts per date from the loaded series (volume-weighted for ranges), with a default trailing-window series from the Layout. |
| M | `frontend/src/components/Layout.jsx` | Toggle shows "USD · daily rates" for floating currencies, loads a 35-day default series, shows the latest rate and date beside the toggle. |
| M | `frontend/src/pages/Dashboard.jsx` | Executive Dashboard: converts each bucket at its own dates' rate, labels figures with the rate dates used. Also renames "PF Cost" to "PG Cost" (payment gateway cost) in the tooltip, table, CSV, rail metric and chart. |
| M | `frontend/src/pages/executive/DailyMerchantDashboard.jsx` | Loads the daily series for the visible month or selected days. |
| M | `frontend/src/pages/executive/NetSpreadDashboard.jsx` | Same as above. |
| M | `frontend/src/pages/executive/ProductSummary.jsx` | Loads the daily series for the selected month. |
| M | `frontend/src/pages/admin/IntegrationHub.jsx` | "FX Rates (daily USD rate)" report type in the Reports tab. |
| M | `frontend/src/test/displayCurrency.test.js` | Tests extended for date-aware conversion. |
| N | `docs/PLAN_FX_DAILY_RATES_EGP_USD_2026-10-28.md` | Design note: problem, data model, lookup rules, rate source (bank MPS table), feed setup. |

## 3. Profitability Report (Finance) — 2026-10-08

Monthly MID P&L for the finance team at Month × MID × Merchant Type × MCC Category ×
Destination × Scheme × Card Interchange, read from `fact_transaction` with the fee
engine's stored fees so it ties out with Net Spread and Product Summary.

| | File | What it is |
|---|---|---|
| N | `acquira-core/.../db/migration/V2026_10_08_01__profitability_report.sql` | Menu `/finance/profitability-report`, RBAC grants and the adjustment-rate table. |
| N | `acquira-common/.../repository/ProfitabilityReportRepository.java` | The month query and column definitions. |
| N | `acquira-core/.../controller/ProfitabilityReportController.java` | `GET` one month per call with filters, cached; adjustment-rate writes evict the cache. |
| N | `frontend/src/pages/finance/ProfitabilityReport.jsx` | The finance-layout page. |
| M | `frontend/src/App.jsx` | Route (shared with section 1). |

## 4. RBAC re-sync without restart — 2026-10-27

A tenant created in the admin UI, or a group edited under Admin › RBAC, had no
sidebar / got 403s until the next restart (Egypt onboarding). The catalog sync can now
be re-run on demand.

| | File | What it is |
|---|---|---|
| M | `acquira-core/.../service/RbacCatalogSync.java` | New `resyncTenants(tenantId)`: re-runs tenant modules, groups and legacy-grant mirroring, then bumps the permission cache. |
| M | `acquira-core/.../controller/AdminController.java` | Calls resync after creating a tenant. |
| M | `acquira-core/.../controller/BankController.java` | Calls resync after creating a tenant; also normalises the CMM minor-divisor field (section 5). |
| M | `acquira-core/.../controller/RbacController.java` | Calls resync after saving a group's menus. |
| N | `docs/deploy/rbac_resync_new_tenant_2026-10-27.sql` | Manual equivalent for a production database where the app has not been redeployed. |

## 5. Egypt onboarding: rate cards, scheme fees, channel map, CMM divisor, Jaywan — 2026-10-25 → 27

| | File | What it is |
|---|---|---|
| N | `.../V2026_10_25_01__tenant_cmm_minor_divisor.sql` | `tenant.cmm_minor_divisor` with check constraint: Egypt's feed carries EGP with 3 implied decimals although EGP displays with 2. (External copy was garbled; restored from the internal copy on 2026-10-28.) |
| M | `acquira-common/.../model/Tenant.java` | `cmmMinorDivisor` field. |
| M | `acquira-batch/.../job/TransactionJobConfig.java` | Applies the tenant divisor override to base-currency amounts; adds Jaywan to scheme normalisation. |
| M | `acquira-batch/.../service/BackfillIngestionService.java` | Same divisor override on the backfill path. |
| M | `frontend/src/pages/TenantManagement.jsx` | "Implied decimals" selector for CMM tenants. |
| N | `.../V2026_10_26_01__jaywan_rate_card_ae.sql` | Jaywan (UAE domestic scheme, co-badged Mastercard): scheme reference and UAE rate rows. |
| N | `.../V2026_10_26_01__visa_eg_irf_guide_align.sql` | Egypt Visa domestic interchange aligned to the Egypt Domestic IRF Guide v2.8. |
| N | `.../V2026_10_26_02__mc_eg_manual_align.sql` | Egypt Mastercard domestic interchange aligned to the MEA Interchange Manual. |
| N | `.../V2026_10_26_03__eg_terminal_channel_map.sql` | Egypt terminal type → channel map (fixes UNMAPPED_CHANNEL on all EG rows). |
| N | `.../V2026_10_26_04__eg_scheme_fee_uae_grid.sql` | Egypt scheme fees seeded from the original UAE grid. |
| N | `.../V2026_10_26_05__eg_meeza_interchange_reassert.sql` | Re-asserts Meeza interchange at 0.50%. |
| N | `.../V2026_10_26_06__eg_meeza_scheme_fee.sql` | Meeza scheme fee 0.05% (superseded by 10_27_02). |
| N | `.../V2026_10_26_07__eg_intl_and_fallback_rows.sql` | Egypt international and any-scheme fallback interchange rows. |
| N | `.../V2026_10_27_01__eg_domestic_scheme_fee_017.sql` | Egypt domestic scheme fee POS 0.17% / ECOM 0.20%. |
| N | `.../V2026_10_27_02__eg_meeza_scheme_fee_006.sql` | Meeza scheme fee 0.06% everywhere. |
| N | `.../V2026_09_25_01__integration_report_minor_units_inherit.sql` | `integration_report.amounts_minor_units` inherits the tenant format by default. |
| N | `docs/deploy/EG_VISA_MC_RATE_CARD_2026-10-26.sql` | Combined psql script of the 10_26 rate-card migrations. |
| N | `docs/deploy/EG_SCHEME_FEE_2026-10-27.sql` | Combined psql script of the 10_27 scheme-fee migrations. |
| N | `docs/deploy/diag_eg_fee_status_2026-10-26.sql` | Read-only diagnostic of fee-resolution status for a repriced day. |
| N | `docs/deploy/rollback_mc_uae_oct2026_scheme_fee.sql` | Rollback of the Mastercard UAE 1 Oct 2026 changes (V2026_10_05_01/02). |

## 6. Merchant de-duplication, tenant-1 recovery, insight PDF and email fixes — 2026-10-05 → 13

| | File | What it is |
|---|---|---|
| M | `acquira-batch/.../job/MerchantMasterJobConfig.java` | Merges AUTO_ placeholder merchants into the real master row after a master upload and rebuilds their summaries. |
| M | `acquira-batch/.../service/MerchantDedupService.java` | SID rule: a placeholder whose stores' SIDs all belong to one master merchant folds into it. |
| M | `acquira-common/.../service/MonthlyMetricsRebuilder.java` | Skips merchant IDs with daily summaries but no dim_merchant row instead of failing the nightly job. |
| M | `acquira-common/.../repository/IntegrationRunLogRepository.java` | JPQL status comparison as a literal. |
| N | `scripts/fix_cross_tenant_duplicate_merchants.sql` | Find and fix merchants duplicated across tenants. |
| N | `scripts/diag_tenant1_after_dedup.sql`, `_part2.sql`, `_part3.sql` | Read-only diagnostics of tenant-1 report damage after the cleanup. |
| N | `scripts/fix_tenant1_restore_merchant_ids.sql` | Moves re-pulled dim_merchant rows back onto their original IDs (run only after the diagnostics pass). |
| N | `scripts/runbook_tenant1_backfill.md` | Step-by-step tenant-1 recovery by backfill. |
| M | `acquira-common/.../service/MerchantInsightService.java` | Currency-aware average spend (BHD 3 dp), BigDecimal band volumes, Jaywan display name. |
| M | `acquira-common/.../dto/MerchantInsightsDTO.java` | `weekendLabel` / `weekendDayCount` and a 4th chart value slot. |
| N | `acquira-common/src/test/.../MerchantInsightServiceBandsTest.java` | Ticket-size bands keep per-country labels. |
| M | `acquira-pdf/.../controller/PdfController.java` | Active-only option: skip merchants with no transactions in the statement month. |
| M | `acquira-pdf/.../pages/p01c-exec-summary.html` | Tenant weekend label; "nothing declined" message. |
| M | `acquira-pdf/.../pages/p01e-mom-compare.html` | "No headline metric declined" message. |
| M | `acquira-pdf/.../pages/p07-growth.html` | Partial-quarter badge reads the months count from value4. |
| M | `acquira-pdf/.../pages/p09-customer-intel.html` | 6+ visit tier always rendered. |
| M | `acquira-pdf/.../pages/p13-glossary.html` | Weekend definition; DCC wording. |
| N | `acquira-pdf/src/test/.../InsightPdfTemplatesBahrainTest.java` | Renders the pages that were wrong on the Bahrain 2026-09 report. |
| M | `acquira-common/.../service/ReportEmailTemplateService.java` | Per-market (BH / UAE) support mailbox and call centre in the built-in report email. |
| N | `acquira-common/src/test/.../ReportEmailTemplateServiceMarketTest.java` | Tests for the market wording. |
| M | `acquira-core/.../controller/EmailCampaignController.java` | Built-in template preview uses the tenant's market. |
| M | `frontend/src/pages/business/MerchantReportManager.jsx` | Active-only toggle for statement runs. |
| M | `acquira-core/.../controller/BusinessAnalyticsController.java` | `GET /merchant-suggest` type-ahead for the executive search boxes. |
| N | `frontend/src/components/MerchantSuggest.jsx` | The type-ahead component. |
| N | `scripts/qmr_uae_visa_mastercard.sql` | Quarterly Merchant Report figures for the UAE tenant. |
| N | `scripts/uae_monthly_card_mix_report.sql` | UAE monthly card-mix report from sum_daily_full. |

## 7. Local tooling (do not deploy)

| | File | What it is |
|---|---|---|
| M | `.claude/launch.json` | Local dev-server launch configurations (frontend-8091, backend-jar, BMS mock/portal). |

---

### Deploy order for the database (this drop)

1. Section 5 Egypt scripts if not yet applied on the target: `docs/deploy/EG_VISA_MC_RATE_CARD_2026-10-26.sql`, then `docs/deploy/EG_SCHEME_FEE_2026-10-27.sql`, plus `V2026_10_25_01` and `V2026_09_25_01`.
2. `V2026_10_08_01__profitability_report.sql`.
3. `V2026_10_28_01__fx_rate_daily.sql`, then `V2026_10_28_02__fx_rate_daily_egp_seed.sql`.
4. `V2026_10_28_03__volume_revenue_mix_menu.sql` (or restart the core service).
5. After deploy, create the Integration Hub report of type FX Rates on the Egypt MPS connection and have a Super Admin approve its SQL.
