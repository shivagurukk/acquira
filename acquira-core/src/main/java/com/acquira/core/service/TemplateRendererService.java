package com.acquira.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders email templates by replacing {{variable}} placeholders
 * with actual merchant/transaction data.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TemplateRendererService {

    private final JdbcTemplate jdbcTemplate;
    private final com.acquira.common.service.CurrencyResolver currencyResolver;

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    /**
     * Available merge variables and their descriptions.
     */
    public static final Map<String, String> AVAILABLE_VARIABLES = new LinkedHashMap<>() {{
        put("merchant_name", "Merchant name");
        put("mid", "Merchant ID");
        put("contact_name", "Primary contact person");
        put("contact_email", "Primary contact email");
        put("month", "Statement month (e.g. January 2026)");
        put("year", "Year");
        put("month_code", "Month code (e.g. 2026-01)");
        put("currency", "Tenant base currency code (e.g. BHD) — prefix monetary variables with this");
        put("total_volume", "Total transaction volume for period (in {{currency}})");
        put("total_count", "Total transaction count for period");
        put("total_msf", "Total MSF revenue for period (in {{currency}})");
        put("merchant_status", "Merchant status (Active/Inactive)");
        put("city", "Merchant city");
        put("onboarding_date", "Date of onboarding");
        put("days_since_last_txn", "Days since last transaction");
        put("tenant_name", "Bank/Acquirer name");
        put("sender_name", "Sender display name");
        put("store_count", "Number of stores");
        put("terminal_count", "Number of terminals");
    }};

    /**
     * Render a template subject or body by replacing placeholders.
     */
    public String render(String template, Map<String, String> variables) {
        if (template == null) return "";
        Matcher matcher = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String value = variables.getOrDefault(varName, "");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Build variable map for a specific merchant.
     */
    public Map<String, String> buildVariablesForMerchant(Long merchantId, Long tenantId, String monthCode) {
        Map<String, String> vars = new HashMap<>();

        // Currency FIRST — every monetary variable below is formatted at this
        // tenant's minor-unit precision (BHD 3, EGP/AED 2). formatNumber() used to
        // hardcode "%,.2f", and no {{currency}} variable was offered at all, so an
        // email template author had no way to say WHICH currency {{total_volume}}
        // was in. Fails loud rather than emitting an unlabelled amount.
        var ccyInfo = currencyResolver.forTenant(tenantId);
        if (ccyInfo == null || ccyInfo.code() == null || ccyInfo.code().isBlank()) {
            log.error("[email-template] currency unresolved for tenant {} — refusing to render unlabelled amounts", tenantId);
            throw new IllegalStateException("Currency could not be resolved for tenant " + tenantId);
        }
        final int ccyDecimals = ccyInfo.decimals();
        vars.put("currency", ccyInfo.code());

        // Merchant info
        try {
            Map<String, Object> merchant = jdbcTemplate.queryForMap(
                "SELECT name, mid, status, city, created_date FROM dim_merchant WHERE merchant_id = ? AND tenant_id = ?",
                merchantId, tenantId);
            vars.put("merchant_name", str(merchant.get("name")));
            vars.put("mid", str(merchant.get("mid")));
            vars.put("merchant_status", str(merchant.get("status")));
            vars.put("city", str(merchant.get("city")));
            vars.put("onboarding_date", str(merchant.get("created_date")));
        } catch (Exception e) {
            log.warn("Could not fetch merchant {} for template rendering: {}", merchantId, e.getMessage());
        }

        // Contact info
        try {
            Map<String, Object> contact = jdbcTemplate.queryForMap(
                "SELECT contact_name, email FROM merchant_contact WHERE merchant_id = ? AND is_primary = true LIMIT 1",
                merchantId);
            vars.put("contact_name", str(contact.get("contact_name")));
            vars.put("contact_email", str(contact.get("email")));
        } catch (Exception e) {
            vars.put("contact_name", vars.getOrDefault("merchant_name", "Merchant"));
            vars.put("contact_email", "");
        }

        // Store/terminal count
        try {
            Integer storeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dim_store WHERE merchant_id = ? AND tenant_id = ?",
                Integer.class, merchantId, tenantId);
            vars.put("store_count", String.valueOf(storeCount != null ? storeCount : 0));
        } catch (Exception e) { vars.put("store_count", "0"); }

        try {
            Integer termCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dim_terminal t JOIN dim_store s ON t.store_id = s.store_id WHERE s.merchant_id = ? AND t.tenant_id = ?",
                Integer.class, merchantId, tenantId);
            vars.put("terminal_count", String.valueOf(termCount != null ? termCount : 0));
        } catch (Exception e) { vars.put("terminal_count", "0"); }

        // Transaction aggregates for month
        if (monthCode != null && !monthCode.isEmpty()) {
            try {
                Map<String, Object> txnStats = jdbcTemplate.queryForMap(
                    "SELECT COALESCE(SUM(store_base_currency_amount),0) AS vol, COUNT(*) AS cnt, COALESCE(SUM(msf),0) AS msf_total " +
                    "FROM fact_transaction WHERE merchant_id = ? AND tenant_id = ? AND TO_CHAR(payment_date,'YYYY-MM') = ?",
                    merchantId, tenantId, monthCode);
                vars.put("total_volume", formatMoney(txnStats.get("vol"), ccyDecimals));
                vars.put("total_count", str(txnStats.get("cnt")));
                vars.put("total_msf", formatMoney(txnStats.get("msf_total"), ccyDecimals));
            } catch (Exception e) {
                vars.put("total_volume", formatMoney(0, ccyDecimals));
                vars.put("total_count", "0");
                vars.put("total_msf", formatMoney(0, ccyDecimals));
            }
        }

        // Days since last transaction
        try {
            Integer days = jdbcTemplate.queryForObject(
                "SELECT EXTRACT(DAY FROM NOW() - MAX(payment_date))::INTEGER FROM fact_transaction WHERE merchant_id = ? AND tenant_id = ?",
                Integer.class, merchantId, tenantId);
            vars.put("days_since_last_txn", days != null ? String.valueOf(days) : "N/A");
        } catch (Exception e) { vars.put("days_since_last_txn", "N/A"); }

        // Period
        if (monthCode != null) {
            vars.put("month_code", monthCode);
            try {
                java.time.YearMonth ym = java.time.YearMonth.parse(monthCode);
                vars.put("month", ym.getMonth().toString().substring(0, 1) + ym.getMonth().toString().substring(1).toLowerCase() + " " + ym.getYear());
                vars.put("year", String.valueOf(ym.getYear()));
            } catch (Exception e) {
                vars.put("month", monthCode);
                vars.put("year", "");
            }
        }

        // Tenant
        try {
            String tenantName = jdbcTemplate.queryForObject(
                "SELECT tenant_name FROM tenant WHERE tenant_id = ?", String.class, tenantId);
            vars.put("tenant_name", tenantName != null ? tenantName : "");
        } catch (Exception e) { vars.put("tenant_name", ""); }

        vars.put("sender_name", vars.getOrDefault("tenant_name", "Acquira Analytics"));

        return vars;
    }

    /**
     * Build sample variables for template preview.
     */
    public Map<String, String> buildSampleVariables() {
        Map<String, String> vars = new HashMap<>();
        vars.put("merchant_name", "Acme Corp");
        vars.put("mid", "MID001234");
        vars.put("contact_name", "John Smith");
        vars.put("contact_email", "john@acme.com");
        vars.put("month", "January 2026");
        vars.put("year", "2026");
        vars.put("month_code", "2026-01");
        vars.put("currency", "BHD");
        // Preview sample deliberately uses a 3-decimal currency so a template
        // author immediately sees that precision is currency-driven, not fixed 2dp.
        vars.put("total_volume", "125,430.505");
        vars.put("total_count", "3,247");
        vars.put("total_msf", "2,508.610");
        vars.put("merchant_status", "Active");
        vars.put("city", "Dubai");
        vars.put("onboarding_date", "2024-03-15");
        vars.put("days_since_last_txn", "2");
        vars.put("tenant_name", "Example Bank");
        vars.put("sender_name", "Example Bank");
        vars.put("store_count", "5");
        vars.put("terminal_count", "12");
        return vars;
    }

    private String str(Object val) {
        return val != null ? val.toString() : "";
    }

    /**
     * Currency-aware money formatter for merge variables.
     * Replaces the hardcoded {@code "%,.2f"}, which truncated BHD's third digit.
     */
    private String formatMoney(Object val, int decimals) {
        if (val == null) return String.format("%,." + decimals + "f", 0.0);
        try {
            double d = ((Number) val).doubleValue();
            return String.format("%,." + decimals + "f", d);
        } catch (Exception e) { return val.toString(); }
    }
}
