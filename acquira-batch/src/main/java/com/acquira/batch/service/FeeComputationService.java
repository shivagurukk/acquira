package com.acquira.batch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The ONE fee engine pass — interchange + scheme fee + ecom fee + channel +
 * provenance/status columns, computed in two phases (resolve into a session
 * temp table, then a keyed UPDATE on the target table).
 *
 * Extracted VERBATIM from TransactionJobConfig.stagingToFactTasklet
 * (2026-08-28) so every path that writes fact rows — the upload/server-file
 * job, BackfillIngestionService, and BulkMigrationService — prices them with
 * the SAME SQL instead of drifting copies (backfill and bulk-migration
 * previously computed NO fees at all: backfilled/migrated days kept the raw
 * feed interchange and NULL scheme/ecom fees).
 *
 * factTable is either fact_transaction itself (legacy in-place pricing, e.g.
 * the APPEND upload path which must re-price pre-existing same-day rows of
 * other schemes) or a session temp table shaped LIKE fact_transaction (the
 * append-only paths, where fees are stamped BEFORE the single INSERT into
 * fact). Because the resolve phase uses CREATE TEMP TABLE, ALL statements in
 * one call must share a connection: callers either run inside a Spring Batch
 * tasklet transaction or wrap the call in a TransactionTemplate.
 *
 * The scope strings come from IngestScopes and follow its contract: rng* are
 * the sargable payment_date range clauses (with the matching alias), dateScope
 * is the exact-day IN-list.
 */
@Service
public class FeeComputationService {

    private static final Logger log = LoggerFactory.getLogger(FeeComputationService.class);

    private final JdbcTemplate jdbcTemplate;

    public FeeComputationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Runs the two-phase fee pass + gap report against factTable. Returns rows priced. */
    /** Rows the apply UPDATE touched, and how many of them kept a previous fee (preserving mode only). */
    public record FeeApplyResult(int rows, long preservedRows) {}

    public int computeFees(long tenantId, String factTable,
                           String rngFt, String rngF, String rngBare, String dateScope) {
        return computeFeesInternal(tenantId, factTable, rngFt, rngF, rngBare, dateScope, false).rows();
    }

    /**
     * Same pass as {@link #computeFees}, for IN-PLACE re-pricing of rows that
     * already carry fees: a row the current rate cards resolve to nothing for
     * (NO_RATE_FOUND / PLACEHOLDER_RATE → NULL) KEEPS its existing fee and
     * status instead of being overwritten with NULL. Rows that do resolve are
     * rewritten exactly as in the normal pass.
     */
    public FeeApplyResult computeFeesPreserving(long tenantId, String factTable,
                           String rngFt, String rngF, String rngBare, String dateScope) {
        return computeFeesInternal(tenantId, factTable, rngFt, rngF, rngBare, dateScope, true);
    }

    private FeeApplyResult computeFeesInternal(long tenantId, String factTable,
                           String rngFt, String rngF, String rngBare, String dateScope,
                           boolean preserveExisting) {
        final String factTarget = factTable;
            // PERF (2026-08-29c): REPLACE mode reads from a freshly-built temp table
            // (tmp_fact_batch) that already contains EXACTLY this load's rows, so the
            // `DATE(payment_date) IN (...)` scope filter is redundant there — and it is
            // actively harmful: Postgres cannot estimate the DATE() expression, so it
            // guessed ~98 rows for a 2.3M-row batch (measured) and therefore ran every
            // per-row LATERAL (scheme, channel, sector, interchange) as an un-memoized
            // nested loop 2.3M times — the ~7-minute fee resolve. Dropping the filter
            // for the temp-batch path restores an accurate row estimate, so the planner
            // memoizes the small-key laterals down to a handful of executions. APPEND
            // mode prices in place on fact_transaction (history present), so it KEEPS
            // the full scope filter. tenant_id is retained as a cheap guard.
            final boolean fromTempBatch = !"fact_transaction".equalsIgnoreCase(factTarget);
            final String factScopeWhere = fromTempBatch
                ? " ft.tenant_id = " + tenantId + " "
                : " ft.tenant_id = " + tenantId + " AND " + rngFt + "DATE(ft.payment_date) IN " + dateScope + " ";
            // FEE COMPUTATION (V2026_07_05_01): interchange + scheme fee are
            // computed by US, not trusted from the feed. Both off the
            // SETTLEMENT amount (store_base_currency_amount) — never the
            // cardholder amount. Rows without a matching rate row (e.g. an
            // unseeded tenant) keep the feed interchange value untouched, so
            // this can never break ingestion.
            //
            // Interchange: highest-priority matching interchange_rate_local
            // row (NULL column = wildcard). Scheme via ref_card_scheme by
            // CODE or NAME; tier via card_subtype (1=Standard else Premium);
            // channel via dim_terminal.type exact ECOM whitelist; MCC sector
            // via mcc_sector_map; ticket thresholds vs settlement amount;
            // debit cap via LEAST(). Scheme fee: dest x channel percentage.
            // =================================================================
            long tFee = System.currentTimeMillis();
            // =================================================================
            // SINGLE-PASS FEE COMPUTATION (PERF, 2026-07-06c)
            //
            // Previously interchange, scheme fee, and ecom fee were THREE separate
            // UPDATEs, each re-scanning the same fact rows for the date range and
            // re-joining dim_terminal / ref_card_scheme. Scheme fee even re-derived
            // the ECOM channel via a correlated dim_terminal subquery that the
            // interchange join had already computed. That's 3x the scan + redundant
            // joins.
            //
            // Now ONE UPDATE:
            //   - joins dim_store / dim_terminal / ref_card_scheme ONCE
            //   - derives `channel` (POS/ECOM) ONCE in the sub-select
            //   - one LATERAL for the interchange rate, one for the scheme rate
            //   - ecom_fee is a CASE on the shared channel (no extra pass/subquery)
            //
            // Correctness is identical to the three separate statements: same rate
            // resolution, same ABS(settlement) basis, same fallbacks. Rows with no
            // matching rate keep the feed interchange value and get scheme/ecom
            // 0/NULL exactly as before.
            //
            // PERF: filters on the RAW payment_date range (partition pruning +
            // index) AND the exact DATE(...) IN (...) set. Fees off SETTLEMENT
            // amount (store_base_currency_amount), never cardholder amount.
            // =================================================================
            // TWO-PHASE APPLY (2026-08-26): resolve first into a session temp table,
            // then UPDATE via a plain keyed join. As a single UPDATE-with-subquery the
            // planner is free to pick a join strategy that re-executes parts of the
            // rate-resolution subquery per outer row; measured live (UAT, BH re-ingest)
            // the resolution SELECT costs ~28s per 300k-row day, yet the combined
            // UPDATE ran for hours. Splitting pins the plan: the SELECT runs exactly
            // once (and CTAS may parallelize it), the UPDATE is a hash join on
            // (transaction_id, payment_date). Phase timings are logged separately so
            // resolve cost vs apply/write cost is visible in production logs.
            final String feeResolveSelect =
                "  SELECT ft.transaction_id, ft.payment_date, ch.channel, " +
                // REFUND RULE (2026-07-08, business-confirmed): refunds carry ZERO
                // interchange and ZERO scheme fee. Feed transaction_type = 'RFND'.
                // Ecom flat fee untouched.
                // interchange: refund => 0; else matched rate (+cap) else flat 1.85% fallback
                // INTERCHANGE (rewritten 2026-08-10).
                //
                // The old version ended `WHEN lr.interchange_pct IS NULL THEN
                // 0.018500 * amount` — any transaction that matched no rate row was
                // silently charged the UAE cross-border rate, in the tenant's own
                // currency, with a NULL (=0) scheme fee. It was indistinguishable
                // from a correctly priced row. That fallback is GONE: an unmatched
                // transaction now yields NULL and an explicit fee_resolution_status.
                //
                // FORMULA: cap bounds the PERCENTAGE component, then the flat fee is
                // added. Both live cases confirm that ordering —
                //   BENEFIT petrol : LEAST(0.6% x 45.750, 0.085) + 0    = 0.085
                //   BENEFIT intl   : LEAST(1.1% x 100.000, inf) + 0.100 = 1.200
                "    CASE WHEN rf.is_refund THEN 0 " +
                "         WHEN ft.destination IS NULL OR ch.channel IS NULL THEN NULL " +
                "         WHEN lr.id IS NULL OR lr.rate_status <> 'APPROVED' THEN NULL " +
                "         ELSE LEAST(lr.interchange_pct * ABS(COALESCE(ft.store_base_currency_amount,0)), " +
                "                    COALESCE(lr.cap_amount, 999999999999)) + COALESCE(lr.flat_fee,0) END AS computed_ic, " +
                // scheme fee: same discipline — an approved rate or nothing at all.
                // BH/EG scheme-fee grids are verbatim UAE copies (flagged PLACEHOLDER
                // in V2026_08_10_01), so they resolve to NULL + PLACEHOLDER_RATE until
                // real country figures are supplied rather than quietly billing UAE
                // economics to Bahraini and Egyptian merchants.
                "    CASE WHEN rf.is_refund THEN 0 " +
                "         WHEN ft.destination IS NULL OR ch.channel IS NULL THEN NULL " +
                "         WHEN sfr.id IS NULL OR sfr.rate_status <> 'APPROVED' THEN NULL " +
                "         ELSE (sfr.fee_pct * ABS(COALESCE(ft.store_base_currency_amount,0))) " +
                "              + COALESCE(sfr.flat_fee,0) END AS computed_scheme, " +
                // ---- provenance + resolution status -------------------------------
                "    lr.id AS ic_rule_id, sfr.id AS sf_rule_id, " +
                "    CASE WHEN lr.rate_status = 'APPROVED' THEN lr.interchange_pct END AS ic_pct, " +
                "    CASE WHEN lr.rate_status = 'APPROVED' THEN lr.flat_fee END AS ic_flat, " +
                "    CASE WHEN lr.rate_status = 'APPROVED' THEN lr.cap_amount END AS ic_cap, " +
                "    CASE WHEN rf.is_refund                      THEN 'RESOLVED' " +
                "         WHEN ft.destination IS NULL            THEN 'UNMAPPED_DESTINATION' " +
                "         WHEN ch.channel IS NULL                THEN 'UNMAPPED_CHANNEL' " +
                "         WHEN lr.id IS NULL                     THEN 'NO_RATE_FOUND' " +
                "         WHEN lr.rate_status <> 'APPROVED'      THEN 'PLACEHOLDER_RATE' " +
                // The scheme token did not resolve to a known network, so pricing came
                // from the country's any-scheme row. Legitimate (this is how Amex and
                // unmapped tokens have always priced) but it must be visible, not
                // silently indistinguishable from a scheme-specific match.
                "         WHEN rcs.group_name IS NULL            THEN 'RESOLVED_SCHEME_WILDCARD' " +
                "         ELSE 'RESOLVED' END AS status, " +
                "    CASE WHEN rf.is_refund                       THEN 'RESOLVED' " +
                "         WHEN ft.destination IS NULL OR ch.channel IS NULL THEN 'UNRESOLVED' " +
                "         WHEN sfr.id IS NULL                     THEN 'NO_RATE_FOUND' " +
                "         WHEN sfr.rate_status <> 'APPROVED'      THEN 'PLACEHOLDER_RATE' " +
                "         ELSE 'RESOLVED' END AS sf_status, " +
                // ecom flat fee (V2026_07_31_06, per-gateway since V2026_08_28_02):
                // per-country config (ecom_flat_fee) resolved by home_country_code,
                // NOT a hardcoded 0.18. On ECOM channel use the resolved fee (COALESCE
                // to 0 when a country has no configured row); NULL off ECOM. A
                // gateway_type row (matched on the raw terminal type, e.g. BENEFIT PG /
                // MPGS / PAY ON) beats the country's is_default row (business rule
                // 2026-08-28: an ECOM txn with no gateway-specific row prices at the
                // default gateway's fee — MPGS), which beats the NULL-gateway
                // last resort, so each payment gateway's PG fee is configurable
                // separately.
                "    CASE WHEN ch.channel = 'ECOM' THEN COALESCE(eff.fee_amount, 0) ELSE NULL END AS computed_ecom " +
                // REPLACE mode resolves from tmp_fact_batch (identical shape); the
                // rate-resolution logic is byte-identical either way.
                "  FROM " + factTarget + " ft " +
                // COUNTRY RESOLUTION (V2026_07_31_02, Phase 2 multi-region): a rate
                // card is COUNTRY-LEVEL, not tenant-level. Resolve the transaction's
                // country from its tenant's home_country_code; every rate LATERAL
                // below then matches country_code = this value (default 'AE' if a
                // tenant has no home country set, preserving legacy UAE behaviour).
                "  LEFT JOIN tenant tn ON tn.tenant_id = ft.tenant_id " +
                "  LEFT JOIN dim_store ds ON ds.store_id = ft.store_id AND ds.tenant_id = ft.tenant_id " +
                "  LEFT JOIN dim_terminal dt ON dt.terminal_id = ft.terminal_id AND dt.tenant_id = ft.tenant_id " +
                // SCHEME RESOLUTION FIX (2026-07-07): space-insensitive match so feed
                // variants like 'MASTER CARD' resolve to ref_card_scheme 'MasterCard'.
                // Without this, ~42% of rows (MASTER CARD) got group_name NULL -> wrong
                // interchange AND zero scheme fee. Strips spaces on BOTH sides.
                // SCHEME RESOLUTION, two-tier (fixed 2026-08-10). The product code is
                // tried FIRST because it carries the Premium/Standard tier signal, then
                // the network name is tried as a fallback.
                //
                // The previous single-expression join used
                //   COALESCE(NULLIF(card_product_code,''), card_scheme)
                // which falls back only when the product code is EMPTY — never when it
                // is present but unrecognised. A feed that puts a generic word like
                // 'DEBIT' or 'CREDIT' in its Card Type column therefore resolved
                // group_name = NULL for EVERY row, so scheme-specific pricing became
                // unreachable and everything silently took the country's any-scheme
                // wildcard. Verified on real Bahraini and Egyptian ingestion: BENEFIT
                // and Meeza transactions were being priced at the generic 1.75%
                // instead of their own rate cards. UAE is unaffected — its product
                // codes (VIPM/MCPM/MCDB...) still match on the first tier.
                "  CROSS JOIN LATERAL (SELECT REPLACE(UPPER(TRIM(COALESCE(ft.card_product_code,''))),' ','') AS v) pc " +
                "  CROSS JOIN LATERAL (SELECT REPLACE(UPPER(TRIM(COALESCE(ft.card_scheme,''))),' ','') AS v) sc " +
                "  LEFT JOIN LATERAL ( " +
                "    SELECT r.*, CASE WHEN pc.v <> '' AND (REPLACE(UPPER(TRIM(r.code)),' ','') = pc.v " +
                "                       OR REPLACE(UPPER(TRIM(r.name)),' ','') = pc.v) THEN 1 ELSE 0 END AS by_product " +
                "    FROM ref_card_scheme r " +
                "    WHERE (pc.v <> '' AND (REPLACE(UPPER(TRIM(r.code)),' ','') = pc.v " +
                "                        OR REPLACE(UPPER(TRIM(r.name)),' ','') = pc.v)) " +
                "       OR (sc.v <> '' AND (REPLACE(UPPER(TRIM(r.code)),' ','') = sc.v " +
                "                        OR REPLACE(UPPER(TRIM(r.name)),' ','') = sc.v)) " +
                "    ORDER BY by_product DESC, r.id ASC LIMIT 1 " +
                "  ) rcs ON TRUE " +
                // derive channel ONCE, reused by both rate LATERALs and the ecom CASE
                // CHANNEL RESOLUTION (config-driven since 2026-08-10). This used to be
                // a hardcoded four-string UAE whitelist with an implicit `ELSE 'POS'`,
                // so ANY other processor's e-commerce silently priced as POS — cheaper
                // interchange and a cheaper scheme fee, i.e. an error that flatters the
                // P&L and never trips an alarm. Now: exact terminal-type match, then
                // the country's '*' wildcard. AE seeds '*' -> POS so its behaviour is
                // unchanged; BH/EG have no wildcard, so an unrecognised terminal type
                // surfaces as UNMAPPED_CHANNEL until the real feed values are mapped.
                "  CROSS JOIN LATERAL ( " +
                "    SELECT COALESCE( " +
                "      (SELECT t1.channel FROM terminal_channel_map t1 " +
                "         WHERE t1.country_code = COALESCE(tn.home_country_code,'AE') " +
                "           AND (t1.tenant_id IS NULL OR t1.tenant_id = ft.tenant_id) " +
                "           AND t1.raw_type = UPPER(TRIM(COALESCE(dt.type,''))) " +
                "         ORDER BY (t1.tenant_id IS NOT NULL) DESC LIMIT 1), " +
                "      (SELECT t2.channel FROM terminal_channel_map t2 " +
                "         WHERE t2.country_code = COALESCE(tn.home_country_code,'AE') " +
                "           AND (t2.tenant_id IS NULL OR t2.tenant_id = ft.tenant_id) " +
                "           AND t2.raw_type = '*' " +
                "         ORDER BY (t2.tenant_id IS NOT NULL) DESC LIMIT 1) " +
                "    ) AS channel " +
                "  ) ch " +
                // derive refund flag ONCE, reused by both computed_ic and computed_scheme.
                // MUST match the volume-signing set used in stagingToFact (2026-07-18) —
                // previously this checked only 'RFND', so a row typed 'REFUND' was signed
                // as negative volume yet still charged interchange + scheme fee.
                // 2026-08-24: stagingToFact now normalizes BH descriptive tokens to
                // canonical REFUND, so 'RFND'/'REFUND' alone would suffice for NEW loads;
                // the descriptive tokens stay in this set (space-stripped compare) so
                // fact rows written BEFORE the normalization, or bulk-migrated verbatim,
                // still take the refund rule on a fee recompute.
                "  CROSS JOIN LATERAL (SELECT (REPLACE(UPPER(TRIM(COALESCE(ft.transaction_type,''))),' ','') IN " +
                "    ('RFND','REFUND','REFUNDREVERSAL','REFUNDVOID','SALEREVERSAL','SALEVOID')) AS is_refund) rf " +
                // derive mcc sector ONCE (was a correlated subquery inside the LATERAL).
                // COUNTRY-LEVEL (V2026_07_31_02): match the tenant's country card;
                // tenant_id IS NULL is the country default, a non-null tenant_id is a
                // per-tenant override which wins via the (tenant_id IS NOT NULL) DESC
                // tiebreak. LATERAL+LIMIT 1 so an override never multiplies rows.
                "  LEFT JOIN LATERAL ( " +
                "    SELECT m.sector FROM mcc_sector_map m " +
                "    WHERE m.country_code = COALESCE(tn.home_country_code,'AE') " +
                "      AND (m.tenant_id IS NULL OR m.tenant_id = ft.tenant_id) " +
                "      AND m.mcc = ds.mcc " +
                "    ORDER BY (m.tenant_id IS NOT NULL) DESC LIMIT 1 " +
                "  ) msm ON TRUE " +
                // BIN-BASED TIER (2026-08-29, V2026_08_29_03): the BH feed carries no
                // card product code, so Standard/Premium/Elite resolves from the scheme
                // BIN file (leading 6 PAN digits -> ref_bin_range -> product code ->
                // ref_bin_product_tier bucket).
                // PERF (2026-08-29b): this used to be a per-row correlated LATERAL —
                // ~30s/1M rows of index probes because a 1M-row BH month has millions
                // of rows but only a few thousand DISTINCT 6-digit BINs. Resolution is
                // now pre-computed ONCE per distinct BIN into the tmp_bin_tier session
                // table (built just before the CTAS below, BH-only), so here it is a
                // plain hash-join on bin6. Empty for non-BH tenants -> bint.card_tier
                // NULL -> the tier CASE falls through exactly as before.
                "  LEFT JOIN tmp_bin_tier bint ON bint.bin6 = LEFT(ft.card_number,6) " +
                // PERF (2026-07-14): lateral split into MCC-keyed + wildcard branches so
                // the planner drives each via an index instead of scanning all ~365
                // candidate rows per transaction (was 9.4M heap blocks / ~270s per window).
                // Branch 1 uses idx_interchange_rate_local_mcc (tenant_id, mcc);
                // Branch 2 uses idx_interchange_rate_local_generic (partial, mcc IS NULL).
                // Same candidate set, same priority pick - semantics unchanged.
                "  LEFT JOIN LATERAL ( " +
                "    SELECT ilr.id, ilr.interchange_pct, ilr.flat_fee, ilr.cap_amount, ilr.rate_status FROM ( " +
                // COUNTRY-LEVEL lookup (V2026_07_31_02): match country_code =
                // tenant's home country (not tenant_id) so all tenants in a country
                // share its card; tenant_id IS NULL = country default, non-null =
                // per-tenant override (preferred in the ORDER BY below).
                "      SELECT i.* FROM interchange_rate_local i " +
                "      WHERE i.country_code = COALESCE(tn.home_country_code,'AE') " +
                "        AND (i.tenant_id IS NULL OR i.tenant_id = ft.tenant_id) " +
                "        AND i.dest = UPPER(TRIM(COALESCE(ft.destination,''))) " +
                "        AND i.mcc = ds.mcc " +
                "      UNION ALL " +
                "      SELECT i.* FROM interchange_rate_local i " +
                "      WHERE i.country_code = COALESCE(tn.home_country_code,'AE') " +
                "        AND (i.tenant_id IS NULL OR i.tenant_id = ft.tenant_id) " +
                "        AND i.dest = UPPER(TRIM(COALESCE(ft.destination,''))) " +
                "        AND i.mcc IS NULL " +
                "    ) ilr " +
                "    WHERE (ilr.channel IS NULL OR ilr.channel = ch.channel) " +
                "      AND (ilr.scheme_group IS NULL OR ilr.scheme_group = COALESCE(rcs.group_name,'')) " +
                // CARD-TYPE FOR PRICING (2026-07-07, business-confirmed): credit-prepaid
                // products (rcs.card_type=3, i.e. MCCP) are PRICED as CREDIT (-> Premium
                // tier below), NOT at the debit/prepaid rate. ft.card_type stays 'PREPAID'
                // for reporting/splits; only this rate lookup remaps. Debit-prepaid
                // (rcs.card_type=4, MCDP) stays on the local debit rate via 'DEBIT'.
                // COMMERCIAL (2026-08-29): when the BIN classifies the card as a
                // commercial product (card_class='COMMERCIAL'), price it as
                // card_type='COMMERCIAL' — its own rate band (bint.card_tier =
                // Comm200/210/215/220) — instead of the consumer credit ladder.
                // Same pc.v='' gate as the tier BIN branch: a feed-supplied product
                // code still wins. Consumer BINs keep the credit/prepaid path.
                "      AND (ilr.card_type IS NULL OR ilr.card_type = CASE " +
                "             WHEN pc.v = '' AND bint.card_class = 'COMMERCIAL' THEN 'COMMERCIAL' " +
                "             WHEN rcs.card_type = 3 THEN 'CREDIT' " +
                "             ELSE UPPER(TRIM(COALESCE(ft.card_type,''))) END) " +
                // TIER (2026-07-07, business-confirmed mapping): ONLY explicit Standard
                // products (card_subtype=1: MCSD/VISD) resolve Standard. EVERYTHING else
                // - AMEX/JCB/UPI/VICR/MCCR/MCCP/MCPM/VIPM/VICP, generic VISA/MCRD, and
                // unmatched codes - resolves Premium. (JCB/UPI still hit their priority-11
                // flat 1.75 rows, which are tier-wildcard, so tier is moot for them.)
                // TIER: explicit Standard feed products (MCSD/VISD) stay Standard; then
                // the BIN-resolved bucket (Standard/Premium/Elite, BH-gated above) wins;
                // everything else remains the legacy Premium fallback.
                // The `pc.v = ''` guard keeps the ORIGINAL gate now that the BIN tier
                // comes from a shared table (a row WITH a feed product code must still
                // resolve tier from rcs, not from its BIN).
                "      AND (ilr.tier IS NULL OR ilr.tier = CASE WHEN rcs.card_subtype = 1 THEN 'Standard' " +
                "                                               WHEN pc.v = '' AND bint.card_tier IS NOT NULL THEN bint.card_tier " +
                "                                               ELSE 'Premium' END) " +
                // MCC-KEYED RATE CARD (2026-07-07): mcc match/wildcard now enforced by the
                // UNION ALL branches above (most-specific still wins via priority DESC).
                "      AND (ilr.mcc_sector IS NULL OR ilr.mcc_sector = msm.sector) " +
                "      AND (ilr.min_ticket IS NULL OR ABS(COALESCE(ft.store_base_currency_amount,0)) >= ilr.min_ticket) " +
                "      AND (ilr.max_ticket IS NULL OR ABS(COALESCE(ft.store_base_currency_amount,0)) <  ilr.max_ticket) " +
                // EFFECTIVE DATING (2026-08-10): resolve against the rate that was in
                // force on the PAYMENT date, not today's. Without this, re-ingesting a
                // historical month reprices it at current rates. Needed imminently
                // because the Egypt Meeza figure is an explicit interim rate.
                "      AND (ilr.effective_from IS NULL OR ilr.effective_from <= DATE(ft.payment_date)) " +
                "      AND (ilr.effective_to   IS NULL OR ilr.effective_to   >= DATE(ft.payment_date)) " +
                // An APPROVED row always beats a PLACEHOLDER one; a placeholder is only
                // ever returned so the status column can say WHY nothing priced.
                // Then: tenant override over country default, then priority, then id.
                "    ORDER BY (ilr.rate_status = 'APPROVED') DESC, (ilr.tenant_id IS NOT NULL) DESC, " +
                "             ilr.priority DESC, ilr.id ASC LIMIT 1 " +
                "  ) lr ON TRUE " +
                // SCHEME FEE: match dest x channel; prefer scheme-specific row, then the
                // scheme_group IS NULL wildcard (seeded 2026-07-07) so EVERY scheme -
                // incl. Amex / MASTER CARD / unmapped - gets a rate instead of 0.
                "  LEFT JOIN LATERAL ( " +
                // COUNTRY-LEVEL (V2026_07_31_02): match the tenant's country card.
                // Prefer a per-tenant override (tenant_id NOT NULL), then a
                // scheme-specific row over the scheme_group IS NULL wildcard.
                "    SELECT s.id, s.fee_pct, s.flat_fee, s.rate_status FROM scheme_fee_rate s " +
                "    WHERE s.country_code = COALESCE(tn.home_country_code,'AE') " +
                "      AND (s.tenant_id IS NULL OR s.tenant_id = ft.tenant_id) " +
                "      AND s.dest = UPPER(TRIM(COALESCE(ft.destination,''))) " +
                "      AND s.channel = ch.channel " +
                "      AND (s.scheme_group IS NULL OR s.scheme_group = COALESCE(rcs.group_name,'')) " +
                "      AND (s.effective_from IS NULL OR s.effective_from <= DATE(ft.payment_date)) " +
                "      AND (s.effective_to   IS NULL OR s.effective_to   >= DATE(ft.payment_date)) " +
                "    ORDER BY (s.rate_status = 'APPROVED') DESC, (s.tenant_id IS NOT NULL) DESC, " +
                "             (s.scheme_group IS NOT NULL) DESC LIMIT 1 " +
                "  ) sfr ON TRUE " +
                // ECOM FLAT FEE (V2026_07_31_06, per-gateway since V2026_08_28_02):
                // resolve the per-country flat fee the same country-level way. A row
                // names a specific gateway (gateway_type = raw terminal type, the
                // token terminal_channel_map matches on), is the flagged default
                // (is_default: the gateway every unlisted ECOM terminal type prices
                // as — MPGS per the 2026-08-28 business rule), or is the NULL-gateway
                // last resort (AE's legacy single row). Precedence: tenant override
                // first (as for every other rate table), then exact gateway match,
                // then default, then last resort. The exact-match ORDER BY term is
                // guarded IS NOT NULL AND: a bare equality is NULL for the
                // NULL-gateway row and DESC sorts NULLs FIRST in Postgres, which
                // would put the last resort above a real match.
                // No matching row => eff.fee_amount NULL => COALESCE'd to 0 above.
                "  LEFT JOIN LATERAL ( " +
                "    SELECT e.fee_amount FROM ecom_flat_fee e " +
                "    WHERE e.country_code = COALESCE(tn.home_country_code,'AE') " +
                "      AND (e.tenant_id IS NULL OR e.tenant_id = ft.tenant_id) " +
                "      AND (e.gateway_type IS NULL OR e.is_default " +
                "           OR e.gateway_type = UPPER(TRIM(COALESCE(dt.type,'')))) " +
                "    ORDER BY (e.tenant_id IS NOT NULL) DESC, " +
                "             (e.gateway_type IS NOT NULL AND e.gateway_type = UPPER(TRIM(COALESCE(dt.type,'')))) DESC, " +
                "             e.is_default DESC LIMIT 1 " +
                "  ) eff ON TRUE " +
                // CREATE TABLE AS is a utility statement, so no bind parameters:
                // tenantId is inlined (a Long from job parameters, never user text).
                // Scope filter is mode-dependent (see factScopeWhere above).
                "  WHERE " + factScopeWhere;

            // BIN-TIER PRE-RESOLUTION (2026-08-29b). The fee-resolve SELECT above
            // joins tmp_bin_tier (bin6 -> Standard/Premium/Elite) as a plain hash
            // join. Build it here, ONCE per DISTINCT 6-digit BIN in scope, instead
            // of the old per-row range lateral: a 1M-row BH month has only a few
            // thousand distinct BINs, so this turns ~1M range probes into a few
            // thousand. Populated only for BH (the sole BIN-tiered rate card); the
            // table always exists so the join target is present for every tenant
            // (empty -> NULL tier -> unchanged pricing elsewhere).
            jdbcTemplate.execute("DROP TABLE IF EXISTS tmp_bin_tier");
            jdbcTemplate.execute(
                "CREATE TEMP TABLE tmp_bin_tier (bin6 VARCHAR(6) PRIMARY KEY, card_tier VARCHAR(10), card_class VARCHAR(12))");
            // Tier-from-BIN is driven by the SAME tenant setting as card-type-from-BIN:
            // card_type_source = 'BIN' means "derive card attributes from the scheme BIN
            // file"; 'FILE' means "trust the feed's product code" (rcs resolves the tier
            // instead, and tmp_bin_tier stays empty). Previously this was hardcoded to
            // home_country_code='BH'; using the setting removes the hardcode and lets any
            // BIN-sourced tenant tier by BIN (it still needs that country's tier rows to
            // have an effect, so non-BH BIN tenants degrade gracefully to the base rate).
            String cardTypeSource = null;
            try {
                cardTypeSource = jdbcTemplate.queryForObject(
                    "SELECT card_type_source FROM tenant WHERE tenant_id = ?", String.class, tenantId);
            } catch (Exception ignore) { /* tenant row missing -> treat as FILE */ }
            if ("BIN".equalsIgnoreCase(cardTypeSource)) {
                long tBin = System.currentTimeMillis();
                int binRows = jdbcTemplate.update(
                    "INSERT INTO tmp_bin_tier (bin6, card_tier, card_class) " +
                    "SELECT b.bin6, bt.card_tier, bt.card_class FROM ( " +
                    // Distinct 6-digit BINs actually present in this load, each with
                    // the scheme derived straight from card_scheme (product code is
                    // blank for BH, so ref_card_scheme would resolve the same group).
                    "   SELECT LEFT(ft.card_number,6) AS bin6, " +
                    "          MAX(CASE WHEN UPPER(REPLACE(COALESCE(ft.card_scheme,''),' ','')) LIKE 'MASTER%' THEN 'MASTERCARD' " +
                    "                   WHEN UPPER(REPLACE(COALESCE(ft.card_scheme,''),' ','')) LIKE 'VISA%'   THEN 'VISA' END) AS scheme " +
                    "   FROM " + factTarget + " ft " +
                    "   WHERE " + factScopeWhere +
                    "     AND ft.card_number ~ '^[0-9]{6}' " +
                    "     AND COALESCE(NULLIF(TRIM(ft.card_product_code),''),'') = '' " +
                    "   GROUP BY LEFT(ft.card_number,6) " +
                    ") b " +
                    "JOIN LATERAL ( " +
                    "   SELECT bpt.card_tier, bpt.card_class FROM ( " +
                    "     SELECT rbr.product_code, rbr.range_low, rbr.range_high FROM ref_bin_range rbr " +
                    "     WHERE rbr.scheme = b.scheme " +
                    "       AND rbr.range_low <= b.bin6 || '999' " +
                    "     ORDER BY rbr.range_low DESC LIMIT 8 " +
                    "   ) cand " +
                    "   JOIN ref_bin_product_tier bpt ON bpt.product_code = cand.product_code " +
                    "   WHERE cand.range_high >= b.bin6 || '000' " +
                    "   ORDER BY cand.range_low DESC LIMIT 1 " +
                    ") bt ON TRUE " +
                    "WHERE b.scheme IS NOT NULL");
                jdbcTemplate.execute("ANALYZE tmp_bin_tier");
                log.info(String.format("BIN-tier pre-resolution: %d distinct BIN(s) in %.1fs",
                    binRows, (System.currentTimeMillis() - tBin) / 1000.0));
            }

            // No ON COMMIT DROP: works whether or not this tasklet's statements share
            // a transaction. Explicitly dropped below; the IF EXISTS guard also clears
            // a leftover from a previous job on the same pooled connection.
            jdbcTemplate.execute("DROP TABLE IF EXISTS tmp_fee_resolve");
            jdbcTemplate.execute("CREATE TEMP TABLE tmp_fee_resolve AS " + feeResolveSelect);
            jdbcTemplate.execute("ANALYZE tmp_fee_resolve");
            long tFeeApply = System.currentTimeMillis();
            log.info(String.format("Fee resolution (phase 1, temp table): %.1fs",
                (tFeeApply - tFee) / 1000.0));
            long preserved = 0;
            if (preserveExisting) {
                Long kept = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + factTarget + " f JOIN tmp_fee_resolve r " +
                    "ON f.transaction_id = r.transaction_id AND f.payment_date = r.payment_date " +
                    "WHERE f.tenant_id = ? AND " + rngF +
                    "((r.computed_ic IS NULL AND f.interchange_fee IS NOT NULL) " +
                    " OR (r.computed_scheme IS NULL AND f.scheme_fee IS NOT NULL))",
                    Long.class, tenantId);
                preserved = kept == null ? 0 : kept;
            }
            // Preserving mode: an unresolved interchange keeps the row's existing
            // fee AND its existing status/rule (so the row still says how it was
            // priced), likewise for the scheme fee. Ecom fee keeps its old value
            // when the new pass has none. Resolved rows are rewritten as usual.
            String setClause = preserveExisting
                ? "  interchange_fee = COALESCE(r.computed_ic, f.interchange_fee), " +
                  "  scheme_fee      = COALESCE(r.computed_scheme, f.scheme_fee), " +
                  "  ecom_fee        = COALESCE(r.computed_ecom, f.ecom_fee), " +
                  "  channel                  = COALESCE(r.channel, f.channel), " +
                  "  fee_resolution_status    = CASE WHEN r.computed_ic IS NULL AND f.interchange_fee IS NOT NULL THEN f.fee_resolution_status ELSE r.status END, " +
                  "  scheme_fee_status        = CASE WHEN r.computed_scheme IS NULL AND f.scheme_fee IS NOT NULL THEN f.scheme_fee_status ELSE r.sf_status END, " +
                  "  interchange_rule_id      = CASE WHEN r.computed_ic IS NULL AND f.interchange_fee IS NOT NULL THEN f.interchange_rule_id ELSE r.ic_rule_id END, " +
                  "  scheme_fee_rule_id       = CASE WHEN r.computed_scheme IS NULL AND f.scheme_fee IS NOT NULL THEN f.scheme_fee_rule_id ELSE r.sf_rule_id END, " +
                  "  interchange_pct_applied  = CASE WHEN r.computed_ic IS NULL AND f.interchange_fee IS NOT NULL THEN f.interchange_pct_applied ELSE r.ic_pct END, " +
                  "  interchange_flat_applied = CASE WHEN r.computed_ic IS NULL AND f.interchange_fee IS NOT NULL THEN f.interchange_flat_applied ELSE r.ic_flat END, " +
                  "  interchange_cap_applied  = CASE WHEN r.computed_ic IS NULL AND f.interchange_fee IS NOT NULL THEN f.interchange_cap_applied ELSE r.ic_cap END "
                : "  interchange_fee = r.computed_ic, " +
                  "  scheme_fee      = r.computed_scheme, " +
                  "  ecom_fee        = r.computed_ecom, " +
                  "  channel                  = r.channel, " +
                  "  fee_resolution_status    = r.status, " +
                  "  scheme_fee_status        = r.sf_status, " +
                  "  interchange_rule_id      = r.ic_rule_id, " +
                  "  scheme_fee_rule_id       = r.sf_rule_id, " +
                  "  interchange_pct_applied  = r.ic_pct, " +
                  "  interchange_flat_applied = r.ic_flat, " +
                  "  interchange_cap_applied  = r.ic_cap ";
            int feeRows = jdbcTemplate.update(
                "UPDATE " + factTarget + " f SET " + setClause +
                "FROM tmp_fee_resolve r " +
                // PERF (2026-08-26): the outer f needs its own sargable payment_date
                // range. The join equality f.payment_date = r.payment_date does NOT
                // prune partitions at plan time (r is not a constant), so without it a
                // hash/merge join scans EVERY partition of fact_transaction for the
                // tenant — seen live in UAT as a single fee UPDATE running 2h45m+.
                "WHERE f.tenant_id = ? AND " + rngF +
                "f.transaction_id = r.transaction_id AND f.payment_date = r.payment_date",
                tenantId);
            jdbcTemplate.execute("DROP TABLE IF EXISTS tmp_fee_resolve");
            jdbcTemplate.execute("DROP TABLE IF EXISTS tmp_bin_tier");
            log.info(String.format("Fee apply (phase 2, keyed UPDATE): %.1fs",
                (System.currentTimeMillis() - tFeeApply) / 1000.0));
            log.info(String.format("Fee computation (single-pass): %d rows in %.1fs",
                feeRows, (System.currentTimeMillis() - tFee) / 1000.0));

            // FEE RESOLUTION REPORT. The whole point of removing the 1.85% fallback is
            // that a pricing gap must be LOUD. Every non-RESOLVED status is a
            // configuration gap that leaves money uncosted, so surface it per run
            // instead of leaving it to be discovered in a month-end reconciliation.
            try {
                java.util.List<java.util.Map<String, Object>> byStatus = jdbcTemplate.queryForList(
                    "SELECT fee_resolution_status AS st, COUNT(*) AS n FROM " + factTarget + " " +
                    "WHERE tenant_id = ? AND " + rngBare + "DATE(payment_date) IN " + dateScope +
                    " GROUP BY 1 ORDER BY 2 DESC", tenantId);
                long unresolved = 0;
                StringBuilder sb = new StringBuilder();
                for (java.util.Map<String, Object> row : byStatus) {
                    String st = String.valueOf(row.get("st"));
                    long n = ((Number) row.get("n")).longValue();
                    sb.append(st).append('=').append(n).append(' ');
                    if (!"RESOLVED".equals(st) && !"RESOLVED_SCHEME_WILDCARD".equals(st)) {
                        unresolved += n;
                    }
                }
                if (unresolved > 0) {
                    log.warn("FEE RESOLUTION GAPS for tenant {}: {} row(s) not priced -> {}",
                        tenantId, unresolved, sb.toString().trim());
                    log.warn("  Unmapped destination tokens seen: {}", jdbcTemplate.queryForList(
                        // BUGFIX: this and the status rollup above interpolated the
                        // ft-aliased range into queries that select FROM an UNALIASED
                        // fact_transaction, so Postgres raised "missing FROM-clause
                        // entry for table ft" and the whole fee-resolution report was
                        // swallowed by the non-fatal catch below — the pricing-gap
                        // warning this exists to raise has never actually fired.
                        "SELECT DISTINCT destination_raw FROM " + factTarget + " WHERE tenant_id = ? " +
                        "AND fee_resolution_status = 'UNMAPPED_DESTINATION' AND " + rngBare +
                        "DATE(payment_date) IN " + dateScope + " LIMIT 20", String.class, tenantId));
                } else {
                    log.info("Fee resolution: {}", sb.toString().trim());
                }
            } catch (Exception e) {
                log.warn("Fee resolution report failed (non-fatal): {}", e.getMessage());
            }
            if (preserved > 0) {
                log.warn("Re-price: {} row(s) kept their previous fee (no approved rate in the current cards)", preserved);
            }
        return new FeeApplyResult(feeRows, preserved);
    }
}
