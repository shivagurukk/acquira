package com.acquira.batch.service;

/**
 * The ONE staging(stg_trnx_raw) -> fact INSERT ... SELECT — dimension
 * resolution with the fan-out guard, txn-type + scheme + destination
 * normalization, signed refund volume/MSF, and the DOMESTIC txn-currency
 * fallback.
 *
 * Extracted VERBATIM from TransactionJobConfig.stagingToFactTasklet
 * (2026-08-28) so BackfillIngestionService writes fact rows with the SAME SQL
 * as the upload/server-file job. Backfill previously carried a drifted mirror:
 * no destination normalization or destination_raw, no txn-currency fallback,
 * UNSIGNED refund volumes (ABS where the job stores refunds negative), and no
 * issuer_country — so backfilled days silently disagreed with uploaded days.
 *
 * @param target        table the rows land in — fact_transaction, or a session
 *                      temp table shaped LIKE it (append-only paths)
 * @param extraStgWhere extra predicate(s) on the staging rows, e.g.
 *                      " AND DATE(stg.payment_date) = ?" for backfill's
 *                      one-day scope; "" for the upload job (whole staging)
 *
 * Bind parameters of the returned SQL: tenantId, then whatever extraStgWhere
 * adds, in order.
 */
public final class IngestSql {

    private IngestSql() {}

    public static String stagingToFactInsertSql(String target, String extraStgWhere) {
        return "INSERT INTO " + target + " (tenant_id, merchant_id, store_id, terminal_id, " +
                "arn, rrn_number, card_number, auth_code, payment_date, transaction_date, batch_number, " +
                "transaction_type, card_scheme, card_type, card_product_code, dcc, txn_currency, txn_currency_amount, " +
                "store_base_currency, store_base_currency_amount, msf, vat, total_amount_settled, interchange_fee, " +
                "destination, destination_raw, issuer_country) " +
                "SELECT stg.tenant_id, " +
                "COALESCE(s.merchant_id, m.merchant_id, s2.merchant_id) AS merchant_id, " +
                "COALESCE(s.store_id, s2.store_id) AS store_id, t.terminal_id, " +
                "stg.arn, stg.rrn_number, stg.card_number, stg.auth_code, " +
                "stg.payment_date, stg.transaction_date, stg.batch_number, tt.norm_type, " +
                // SIGNED VOLUME (2026-07-18, reverses 2026-07-08 option B): refunds (RFND)
                // are stored NEGATIVE so fact + all summaries net refunds out of volume,
                // matching the raw feed / MIS reconciliation basis. Sign is forced from
                // transaction_type (not trusted from the feed): purchases +ABS, refunds -ABS.
                //
                // SIGNED MSF (2026-08-07): MSF follows the SAME sign rule as volume.
                // Verified against July 2026 (10,180,989 rows): file signed sum
                // 16,566,159.6713 == finance == fact netted; the old ABS basis ran
                // exactly 2x refund MSF higher (16,583,044.4293). Refund fees must
                // net out to reconcile with the raw feed / finance pivot.
                // vat/interchange remain ABS; total_amount_settled stays raw SIGNED.
                // TXN CURRENCY FALLBACK (2026-08-14, user rule): feeds mask the PAN
                // as first-6-clear + masked + last-4-clear, so only a 6-digit BIN is
                // extractable and BIN -> issuer-country -> cardholder-currency cannot
                // resolve yet. For rows the feed leaves blank AND that map to
                // DOMESTIC, the cardholder is local by definition, so take the
                // tenant's home currency (base_currency, else the home country's
                // ref_country currency). Blank INTERNATIONAL rows stay NULL — a
                // guessed foreign currency would poison the by-country rollups.
                // A feed-supplied currency is never overridden.
                "sch.norm_scheme, stg.card_type, stg.card_product_code, stg.dcc, " +
                "COALESCE(NULLIF(TRIM(stg.txn_currency),''), " +
                "  CASE WHEN dtm.dest = 'DOMESTIC' THEN NULLIF(TRIM(COALESCE(tn.base_currency, rchome.currency_code)),'') END), " +
                "CASE WHEN tt.is_refund " +
                "     THEN -ABS(stg.txn_currency_amount) ELSE ABS(stg.txn_currency_amount) END, " +
                "stg.store_base_currency, " +
                "CASE WHEN tt.is_refund " +
                "     THEN -ABS(stg.store_base_currency_amount) ELSE ABS(stg.store_base_currency_amount) END, " +
                "CASE WHEN tt.is_refund " +
                "     THEN -ABS(stg.msf) ELSE ABS(stg.msf) END, " +
                "ABS(stg.vat), stg.total_amount_settled, ABS(stg.interchange_fee), " +
                // DESTINATION NORMALIZATION (2026-08-10). The feed's own vocabulary is
                // mapped to the engine's canonical DOMESTIC/INTERNATIONAL exactly once,
                // here, so fact + fee engine + every rollup all see the same value.
                // Previously the raw token was copied verbatim and the fee engine
                // exact-matched it, so a Bahraini or Egyptian feed saying 'LOCAL'
                // matched no rate row and silently took a 1.85% UAE fallback.
                // An UNMAPPED token deliberately lands as NULL rather than being
                // guessed as INTERNATIONAL — the fee engine reports it as
                // UNMAPPED_DESTINATION and prices nothing. destination_raw always
                // keeps the original token for audit and for mapping gaps analysis.
                "dtm.dest, NULLIF(TRIM(stg.destination),''), stg.issuer_country " +
                "FROM stg_trnx_raw stg " +
                "LEFT JOIN tenant tn ON tn.tenant_id = stg.tenant_id " +
                // home-currency source for the DOMESTIC txn_currency fallback above;
                // country_code is ref_country's key, so this can never fan out rows.
                "LEFT JOIN ref_country rchome ON rchome.country_code = COALESCE(tn.home_country_code,'AE') " +
                "LEFT JOIN LATERAL ( " +
                "  SELECT d.dest FROM destination_token_map d " +
                "  WHERE d.country_code = COALESCE(tn.home_country_code,'AE') " +
                "    AND (d.tenant_id IS NULL OR d.tenant_id = stg.tenant_id) " +
                "    AND d.raw_token = UPPER(TRIM(COALESCE(stg.destination,''))) " +
                "  ORDER BY (d.tenant_id IS NOT NULL) DESC LIMIT 1 " +
                ") dtm ON TRUE " +
                // TXN-TYPE NORMALIZATION (2026-08-24, BH tenant go-live; user-confirmed):
                // descriptive feed types are mapped to the engine's canonical
                // PURCHASE/REFUND exactly ONCE, here — the destination-normalization
                // precedent above — so signing, the fee engine's refund rule and every
                // TRANSACTION_TYPE split all see one vocabulary.
                //   Purchase, Pre-Authorization Completion       -> PURCHASE
                //   Refund, Refund Reversal, Refund Void,
                //   Sale Reversal, Sale Void                     -> REFUND
                //   Pre-authorization                            -> row EXCLUDED (not a
                //     settled movement; see the WHERE filter + reconciliation count)
                // Tokens are compared space-stripped/case-folded; anything else (UAE
                // 'RFND'/'SALE' etc.) passes through untouched. is_refund drives the
                // volume/MSF sign and MUST stay a superset of the fee engine's rf set.
                // 2026-08-28: plain 'Purchase'/'Refund' previously fell through the
                // ELSE and kept the feed's casing, so one day carried both 'Purchase'
                // and 'PURCHASE' and every TRANSACTION_TYPE split showed twin rows.
                "CROSS JOIN LATERAL (SELECT REPLACE(UPPER(TRIM(COALESCE(stg.transaction_type,''))),' ','') AS v) ttr " +
                "CROSS JOIN LATERAL (SELECT " +
                "  (ttr.v IN ('RFND','REFUND','REFUNDREVERSAL','REFUNDVOID','SALEREVERSAL','SALEVOID')) AS is_refund, " +
                "  CASE WHEN ttr.v IN ('REFUND','REFUNDREVERSAL','REFUNDVOID','SALEREVERSAL','SALEVOID') THEN 'REFUND' " +
                "       WHEN ttr.v IN ('PURCHASE','PRE-AUTHORIZATIONCOMPLETION','PREAUTHORIZATIONCOMPLETION','PRE-AUTHCOMPLETION','PREAUTHCOMPLETION') THEN 'PURCHASE' " +
                "       ELSE stg.transaction_type END AS norm_type) tt " +
                // SCHEME NORMALIZATION (2026-08-24, BH): the feed's 'No Interchange'
                // token is the Benefit QR product — store it under its real scheme name
                // so dashboards group it correctly and the fee engine resolves the
                // 'Benefit QR' rate card (Bahrain local rates, V2026_08_24_01) instead
                // of the BH any-scheme wildcard. Keep in sync with the APPEND-mode
                // uploadSchemes mapping above.
                "CROSS JOIN LATERAL (SELECT CASE WHEN REPLACE(UPPER(TRIM(COALESCE(stg.card_scheme,''))),' ','') = 'NOINTERCHANGE' " +
                "       THEN 'Benefit QR' ELSE stg.card_scheme END AS norm_scheme) sch " +
                // FAN-OUT GUARD (2026-08-25): sid / mid / tid are NOT unique per tenant
                // (only internal_id is), so a plain LEFT JOIN on these keys can match
                // several dimension rows and DUPLICATE the transaction — exactly what the
                // row-count reconciliation below fails on. Each dimension is resolved
                // through a LATERAL … LIMIT 1 so at most one row is ever attached, keeping
                // fact 1:1 with staging. Deterministic ORDER BY (lowest surrogate id;
                // terminal prefers the row whose store matches the resolved store) makes
                // the pick stable across re-runs. Index-supported: (tenant_id,sid),
                // (tenant_id,mid), (tenant_id,tid).
                "LEFT JOIN LATERAL (SELECT ds.store_id, ds.merchant_id FROM dim_store ds " +
                "  WHERE ds.tenant_id = stg.tenant_id AND ds.sid = NULLIF(TRIM(stg.sid), '') " +
                "  ORDER BY ds.store_id LIMIT 1) s ON TRUE " +
                "LEFT JOIN LATERAL (SELECT dm.merchant_id FROM dim_merchant dm " +
                "  WHERE dm.tenant_id = stg.tenant_id AND dm.mid = NULLIF(TRIM(stg.mid), '') " +
                "  ORDER BY dm.merchant_id LIMIT 1) m ON TRUE " +
                "LEFT JOIN LATERAL (SELECT dt.terminal_id, dt.store_id FROM dim_terminal dt " +
                "  WHERE dt.tenant_id = stg.tenant_id AND dt.tid = NULLIF(TRIM(stg.tid), '') " +
                "    AND (s.store_id IS NULL OR dt.store_id = s.store_id) " +
                "  ORDER BY (dt.store_id = s.store_id) DESC NULLS LAST, dt.terminal_id LIMIT 1) t ON TRUE " +
                "LEFT JOIN LATERAL (SELECT ds2.store_id, ds2.merchant_id FROM dim_store ds2 " +
                "  WHERE ds2.tenant_id = stg.tenant_id AND ds2.store_id = t.store_id LIMIT 1) s2 ON TRUE " +
                // Pre-authorizations are holds, not settled money movement — excluded
                // from fact entirely (BH feed; user-confirmed 2026-08-24). Completion
                // rows carry the settlement and are mapped to PURCHASE above.
                "WHERE stg.tenant_id = ? AND stg.payment_date IS NOT NULL " +
                "AND ttr.v NOT IN ('PRE-AUTHORIZATION','PREAUTHORIZATION','PRE-AUTH','PREAUTH')" +
                extraStgWhere;
    }
}
