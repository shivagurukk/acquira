package com.acquira.core.service.pricebuilder;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/**
 * Rate book for the Price Builder (/mis/price-builder).
 *
 * Loads the country behaviour, scheme rows, scheme fees, MCC catalog and cost
 * tables that a quote is priced against, and answers the one question the
 * engine keeps asking: <em>what does this scheme row cost for this MCC?</em>
 *
 * <h2>Sign convention</h2>
 * Every rate here is a SIGNED DECIMAL FRACTION where negative is a cost:
 * {@code -0.018} is 1.8% interchange, {@code -0.0015} is a 0.15% scheme fee.
 * Nothing in this class holds "1.8 meaning 1.8%". See the header of
 * {@code V2026_11_12_01__mis_price_builder.sql} — the source material mixed
 * the two and the 100x errors that followed are why this is stated twice.
 *
 * <h2>Resolution order</h2>
 * <ol>
 *   <li>{@code pb_special_rule} — per-transaction amounts, ticket splits and
 *       flat per-MCC overrides. Most specific match wins.</li>
 *   <li>{@code pb_mcc_cost} — exact per-MCC rate from an imported workbook.</li>
 *   <li>{@code pb_sector_cost} — sector fallback, so a database that has never
 *       had an import still prices a deal rather than quoting zero
 *       interchange, which would read as pure margin.</li>
 * </ol>
 *
 * The tables are small (hundreds of rows) and a quote is an interactive,
 * low-frequency action, so each request reads them fresh. That is deliberate:
 * a cached rate book that silently serves yesterday's interchange is a worse
 * failure than a few extra milliseconds.
 */
@Service
public class PriceBuilderRates {

    private final JdbcTemplate jdbc;

    public PriceBuilderRates(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Value types ────────────────────────────────────────────────────────

    /** Country behaviour: currency, USD rate and the commercial constants. */
    public record CountryConfig(
            String countryCode, String countryName, String currencyCode,
            double usdRate, double ecomIcUplift, double targetMargin,
            double intlMsfPos, double intlMsfEcom,
            double dccSchemeFeePos, double dccSchemeFeeEcom,
            double debitIntlTxnFee, boolean active) {

        /** Fixed international MSF for the channel, as a fraction. */
        public double intlMsf(String channel) {
            return isEcom(channel) ? intlMsfEcom : intlMsfPos;
        }

        /** DCC scheme fee for the channel, as a positive fraction. */
        public double dccSchemeFee(String channel) {
            return isEcom(channel) ? dccSchemeFeeEcom : dccSchemeFeePos;
        }
    }

    /** One priced row: a scheme x locality (x tier) the country quotes on. */
    public record SchemeRow(
            String schemeKey, String label, String scheme, String locality,
            String tier, String cardType, int displayOrder) {

        public boolean isLocal()         { return "LOCAL".equals(locality); }
        public boolean isInternational() { return "INTERNATIONAL".equals(locality); }
        public boolean isDebit()         { return "DEBIT".equals(cardType); }
        public boolean isCredit()        { return "CREDIT".equals(cardType); }

        /** Visa and Mastercard carry the e-commerce interchange uplift. */
        public boolean isUpliftScheme() {
            return "VISA".equals(scheme) || "MASTERCARD".equals(scheme);
        }
    }

    /** An MCC as the quote sees it. */
    public record Mcc(String mcc, String sector, String description, String mccType) {
        public boolean isPreferential() { return "PREFERENTIAL".equals(mccType); }
    }

    /** A rule that is not a flat percentage. */
    public record SpecialRule(
            String mcc, String scheme, String locality, String cardType,
            String channel, String mccType, String ruleType,
            Double threshold, Double smallRate, Double largeRate, Double flatRate,
            Double amountLocal, boolean amountIsUsd, int priority, String note) {

        public boolean isPerTxn()      { return "PER_TXN".equals(ruleType); }
        public boolean isTicketSplit() { return "TICKET_SPLIT".equals(ruleType); }
        public boolean isFlatPct()     { return "FLAT_PCT".equals(ruleType); }

        /**
         * How tightly this rule names its target. A rule that names the MCC
         * beats one that does not; after that, every non-null dimension is a
         * further qualification. Used only to break ties inside one priority
         * band, so an operator can override a seeded rule by raising priority
         * without having to out-specify it.
         */
        int specificity() {
            int s = 0;
            if (mcc      != null) s += 16;
            if (scheme   != null) s += 8;
            if (locality != null) s += 4;
            if (cardType != null) s += 2;
            if (channel  != null) s += 1;
            if (mccType  != null) s += 1;
            return s;
        }
    }

    /** The whole rate book for one country, read once per quote. */
    public record RateBook(
            CountryConfig config,
            List<SchemeRow> schemeRows,
            Map<String, Double> schemeFees,     // "SCHEME|LOCALITY" -> fraction
            Map<String, Double> mccCosts,       // "MCC|SCHEME|LOCALITY|TIER" -> fraction
            Map<String, Double> sectorCosts,    // "SECTOR|SCHEME|LOCALITY|TIER" -> fraction
            List<SpecialRule> specialRules) {

        public SchemeRow row(String schemeKey) {
            return schemeRows.stream()
                    .filter(r -> r.schemeKey().equals(schemeKey))
                    .findFirst().orElse(null);
        }

        /** Scheme fee for a row, as a negative fraction. 0 when unpriced. */
        public double schemeFee(SchemeRow row) {
            return schemeFees.getOrDefault(row.scheme() + "|" + row.locality(), 0.0);
        }

        /**
         * Base interchange for an MCC on a row: the imported per-MCC rate if
         * there is one, otherwise the sector fallback, otherwise zero.
         */
        public double baseInterchange(String mcc, String sector, SchemeRow row) {
            String tier = row.tier() == null ? "" : row.tier();
            Double exact = mccCosts.get(mcc + "|" + row.scheme() + "|" + row.locality() + "|" + tier);
            if (exact != null) return exact;
            Double bySector = sectorCosts.get(sector + "|" + row.scheme() + "|" + row.locality() + "|" + tier);
            return bySector != null ? bySector : 0.0;
        }

        /**
         * The special rule that governs this MCC on this row, or null.
         *
         * A rule matches when every dimension it names equals the row's, and
         * dimensions it leaves null match anything. Highest priority wins;
         * within a priority, the most specific.
         */
        public SpecialRule specialRule(Mcc mcc, SchemeRow row, String channel) {
            String ch = isEcom(channel) ? "ECOM" : "POS";
            return specialRules.stream()
                    .filter(r -> r.mcc()      == null || r.mcc().equals(mcc.mcc()))
                    .filter(r -> r.scheme()   == null || r.scheme().equals(row.scheme()))
                    .filter(r -> r.locality() == null || r.locality().equals(row.locality()))
                    .filter(r -> r.cardType() == null || r.cardType().equals(row.cardType()))
                    .filter(r -> r.channel()  == null || r.channel().equals(ch))
                    .filter(r -> r.mccType()  == null || r.mccType().equals(mcc.mccType()))
                    .max(Comparator.comparingInt(SpecialRule::priority)
                            .thenComparingInt(SpecialRule::specificity))
                    .orElse(null);
        }
    }

    // ── Loading ────────────────────────────────────────────────────────────

    /** Every country the tool offers, active ones first. */
    public List<Map<String, Object>> countries() {
        return jdbc.query(
                "SELECT country_code, country_name, currency_code, usd_rate, is_active " +
                "FROM pb_country_config ORDER BY display_order",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("countryCode",  rs.getString("country_code"));
                    m.put("countryName",  rs.getString("country_name"));
                    m.put("currencyCode", rs.getString("currency_code"));
                    m.put("usdRate",      rs.getBigDecimal("usd_rate"));
                    m.put("active",       rs.getBoolean("is_active"));
                    return m;
                });
    }

    public CountryConfig config(String countryCode) {
        List<CountryConfig> found = jdbc.query(
                "SELECT country_code, country_name, currency_code, usd_rate, ecom_ic_uplift, " +
                "       target_margin, intl_msf_pos, intl_msf_ecom, dcc_scheme_fee_pos, " +
                "       dcc_scheme_fee_ecom, debit_intl_txn_fee, is_active " +
                "FROM pb_country_config WHERE country_code = ?",
                (rs, i) -> new CountryConfig(
                        rs.getString("country_code"),
                        rs.getString("country_name"),
                        rs.getString("currency_code"),
                        rs.getDouble("usd_rate"),
                        rs.getDouble("ecom_ic_uplift"),
                        rs.getDouble("target_margin"),
                        rs.getDouble("intl_msf_pos"),
                        rs.getDouble("intl_msf_ecom"),
                        rs.getDouble("dcc_scheme_fee_pos"),
                        rs.getDouble("dcc_scheme_fee_ecom"),
                        rs.getDouble("debit_intl_txn_fee"),
                        rs.getBoolean("is_active")),
                countryCode);
        if (found.isEmpty())
            throw new IllegalArgumentException("Unknown country: " + countryCode);
        return found.get(0);
    }

    public List<SchemeRow> schemeRows(String countryCode) {
        return jdbc.query(
                "SELECT scheme_key, label, scheme, locality, tier, card_type, display_order " +
                "FROM pb_scheme_row WHERE country_code = ? ORDER BY display_order",
                (rs, i) -> new SchemeRow(
                        rs.getString("scheme_key"), rs.getString("label"),
                        rs.getString("scheme"), rs.getString("locality"),
                        rs.getString("tier"), rs.getString("card_type"),
                        rs.getInt("display_order")),
                countryCode);
    }

    public List<Mcc> mccs(String countryCode) {
        return jdbc.query(
                "SELECT mcc, sector, description, mcc_type FROM pb_mcc " +
                "WHERE country_code = ? ORDER BY mcc",
                (rs, i) -> new Mcc(rs.getString("mcc"), rs.getString("sector"),
                        rs.getString("description"), rs.getString("mcc_type")),
                countryCode);
    }

    public Mcc mcc(String countryCode, String mcc) {
        List<Mcc> found = jdbc.query(
                "SELECT mcc, sector, description, mcc_type FROM pb_mcc " +
                "WHERE country_code = ? AND mcc = ?",
                (rs, i) -> new Mcc(rs.getString("mcc"), rs.getString("sector"),
                        rs.getString("description"), rs.getString("mcc_type")),
                countryCode, mcc);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * The ticket splits a country prices on, so the screen knows whether to
     * ask for a transaction-size distribution — and for which card type.
     *
     * A row with a null {@code mcc} applies to every MCC; a row that names one
     * applies only there. Without this the screen would have to hardcode "the
     * UAE asks about 5,000 dirhams", which is exactly the knowledge the rule
     * table exists to hold.
     */
    public List<Map<String, Object>> ticketSplits(String countryCode) {
        return jdbc.query(
                "SELECT DISTINCT card_type, mcc, threshold FROM pb_special_rule " +
                "WHERE country_code = ? AND rule_type = 'TICKET_SPLIT' " +
                "ORDER BY card_type, mcc NULLS FIRST",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("cardType",  rs.getString("card_type"));
                    m.put("mcc",       rs.getString("mcc"));
                    m.put("threshold", rs.getBigDecimal("threshold"));
                    return m;
                },
                countryCode);
    }

    public List<Map<String, Object>> gateways() {
        return jdbc.query(
                "SELECT code, label, cost_per_txn FROM pb_payment_gateway " +
                "WHERE is_active ORDER BY label",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code",       rs.getString("code"));
                    m.put("label",      rs.getString("label"));
                    m.put("costPerTxn", rs.getBigDecimal("cost_per_txn"));
                    return m;
                });
    }

    /** Reads everything a quote needs for one country in five small queries. */
    public RateBook rateBook(String countryCode) {
        CountryConfig config = config(countryCode);

        Map<String, Double> fees = keyedRates(
                "SELECT scheme || '|' || locality AS k, fee_pct AS v " +
                "FROM pb_scheme_fee WHERE country_code = ?", countryCode);

        Map<String, Double> mccCosts = keyedRates(
                "SELECT mcc || '|' || scheme || '|' || locality || '|' || COALESCE(tier,'') AS k, " +
                "       ic_pct AS v FROM pb_mcc_cost WHERE country_code = ?", countryCode);

        Map<String, Double> sectorCosts = keyedRates(
                "SELECT sector || '|' || scheme || '|' || locality || '|' || COALESCE(tier,'') AS k, " +
                "       ic_pct AS v FROM pb_sector_cost WHERE country_code = ?", countryCode);

        List<SpecialRule> rules = jdbc.query(
                "SELECT mcc, scheme, locality, card_type, channel, mcc_type, rule_type, " +
                "       threshold, small_rate, large_rate, flat_rate, amount_local, " +
                "       amount_is_usd, priority, note " +
                "FROM pb_special_rule WHERE country_code = ?",
                (rs, i) -> new SpecialRule(
                        rs.getString("mcc"), rs.getString("scheme"), rs.getString("locality"),
                        rs.getString("card_type"), rs.getString("channel"), rs.getString("mcc_type"),
                        rs.getString("rule_type"),
                        nullableDouble(rs.getBigDecimal("threshold")),
                        nullableDouble(rs.getBigDecimal("small_rate")),
                        nullableDouble(rs.getBigDecimal("large_rate")),
                        nullableDouble(rs.getBigDecimal("flat_rate")),
                        nullableDouble(rs.getBigDecimal("amount_local")),
                        rs.getBoolean("amount_is_usd"),
                        rs.getInt("priority"), rs.getString("note")),
                countryCode);

        return new RateBook(config, schemeRows(countryCode), fees, mccCosts,
                new LinkedHashMap<>(sectorCosts), new ArrayList<>(rules));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static Double nullableDouble(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }

    /**
     * Runs a two-column {@code (k, v)} rate query into a lookup map.
     *
     * The key is built in SQL rather than Java so the three cost tables, which
     * have different column sets, all come back through one code path — and so
     * a key can never be assembled two different ways on the write and read
     * sides of the same map.
     */
    private Map<String, Double> keyedRates(String sql, Object... args) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(sql, args)) {
            Object v = row.get("v");
            if (v != null) out.put(String.valueOf(row.get("k")), ((Number) v).doubleValue());
        }
        return out;
    }

    /** One place decides what counts as the e-commerce channel. */
    public static boolean isEcom(String channel) {
        if (channel == null) return false;
        String c = channel.trim().toUpperCase();
        return c.startsWith("ECOM") || c.equals("E-COMMERCE") || c.equals("ECOMMERCE");
    }

    /** Canonical channel token, so callers never have to spell it twice. */
    public static String channel(String raw) {
        return isEcom(raw) ? "ECOM" : "POS";
    }
}
