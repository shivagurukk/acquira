package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.service.CurrencyResolver;
import com.acquira.common.service.CurrencyResolver.CurrencyInfo;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * ONE place that turns "the caller's tenant" into the compact currency block the
 * money-bearing APIs advertise:
 *
 * <pre>{@code  "currency": { "code": "BHD", "symbol": "BHD", "decimals": 3, "resolved": true } }</pre>
 *
 * <p><b>Why this exists.</b> Before it, exactly one DTO in the codebase
 * ({@code MerchantInsightsDTO}) carried a currency. Every other money-bearing
 * response — KPI cards, trends, forecasts, leaderboards, profitability — shipped
 * bare numbers, so the frontend inferred the currency from whatever tenant
 * happened to be selected at login. That inference is wrong the moment a user
 * switches tenants without a reload, and it is catastrophically wrong across
 * currencies with different precision: Bahrain's 3-decimal BHD rendered through
 * a 2-decimal assumption silently drops fils.
 *
 * <p><b>Never guesses.</b> {@link CurrencyResolver} throws when a tenant's
 * currency is unresolvable; this class catches that and emits an explicit
 * {@code "resolved": false} block with null code/symbol/decimals rather than
 * substituting AED/BHD/USD. A client that sees {@code resolved:false} must show
 * an amount without a currency (or an error) — it must not fall back to a
 * default, which is precisely the bug this block exists to end.
 *
 * <p>Kept in the controller package deliberately: it is a presentation concern,
 * and it is the single point of contact between the controllers and
 * {@code CurrencyResolver}.
 */
@Component
public class CurrencyMeta {

    /** Response key under which the block is published. */
    public static final String KEY = "currency";

    private final CurrencyResolver currencyResolver;

    public CurrencyMeta(CurrencyResolver currencyResolver) {
        this.currencyResolver = currencyResolver;
    }

    /** Currency block for the tenant on the current request. */
    public Map<String, Object> block() {
        return block(TenantContext.getCurrentTenant());
    }

    /** Currency block for an explicit tenant. Never throws. */
    public Map<String, Object> block(Long tenantId) {
        Map<String, Object> m = new LinkedHashMap<>();
        Optional<CurrencyInfo> info = resolve(tenantId);
        if (info.isEmpty()) {
            // Explicit unresolved marker — see class javadoc. No default currency.
            m.put("code", null);
            m.put("symbol", null);
            m.put("decimals", null);
            m.put("resolved", false);
            return m;
        }
        CurrencyInfo i = info.get();
        m.put("code", i.code());
        m.put("symbol", i.symbol());
        m.put("decimals", i.decimals());
        m.put("resolved", true);
        return m;
    }

    /**
     * Adds the currency block to an existing mutable response map and returns it,
     * so call sites stay a one-liner: {@code return ResponseEntity.ok(currencyMeta.attach(body));}
     *
     * <p>Purely additive — no existing key is touched, so clients that do not know
     * about {@code currency} keep working unchanged.
     */
    public <T extends Map<String, Object>> T attach(T body) {
        if (body != null) {
            body.put(KEY, block());
        }
        return body;
    }

    /** As {@link #attach(Map)} but for an explicit tenant. */
    public <T extends Map<String, Object>> T attach(T body, Long tenantId) {
        if (body != null) {
            body.put(KEY, block(tenantId));
        }
        return body;
    }

    /** Resolved currency for a tenant, or empty. Never throws. */
    public Optional<CurrencyInfo> resolve(Long tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        return currencyResolver.forTenantQuietly(tenantId);
    }

    /** Resolved currency for the current request's tenant, or empty. */
    public Optional<CurrencyInfo> resolve() {
        return resolve(TenantContext.getCurrentTenant());
    }

    /**
     * Decimal places to render money at for a tenant, for export/formatting paths.
     * Falls back to {@code fallback} ONLY when the caller has explicitly decided
     * what an unresolved currency should do — the fallback is a visible argument
     * at the call site, never a hidden default in here.
     */
    public int decimalsOr(Long tenantId, int fallback) {
        return resolve(tenantId).map(CurrencyInfo::decimals).orElse(fallback);
    }

    /** Currency code for a tenant, or {@code null} when unresolvable. */
    public String codeOrNull(Long tenantId) {
        return resolve(tenantId).map(CurrencyInfo::code).orElse(null);
    }

    /**
     * Render an amount at a given scale for CSV/text export.
     *
     * <p>Uses {@link BigDecimal} with HALF_UP rather than {@code String.format("%.2f", ...)}:
     * the hardcoded {@code %.2f} in the export paths truncated BHD's third decimal,
     * turning 450.755 into "450.76" (and, worse, made a 3-decimal figure look like a
     * 2-decimal one). Returns an empty string for null so a missing amount is
     * visibly blank instead of a fabricated 0.00.
     */
    public static String formatAmount(Object amount, int decimals) {
        if (amount == null) {
            return "";
        }
        BigDecimal bd;
        if (amount instanceof BigDecimal b) {
            bd = b;
        } else if (amount instanceof Number n) {
            bd = BigDecimal.valueOf(n.doubleValue());
        } else {
            try {
                bd = new BigDecimal(amount.toString());
            } catch (NumberFormatException e) {
                return "";
            }
        }
        return bd.setScale(Math.max(0, decimals), RoundingMode.HALF_UP).toPlainString();
    }
}
