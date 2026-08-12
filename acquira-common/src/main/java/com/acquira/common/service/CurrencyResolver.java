package com.acquira.common.service;

import com.acquira.common.model.RefCountry;
import com.acquira.common.model.Tenant;
import com.acquira.common.repository.RefCountryRepository;
import com.acquira.common.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * THE single authority for "what currency is this tenant, and to how many
 * decimal places?".
 *
 * <p>Before this class, currency precision lived in exactly one place —
 * {@code ref_country.decimal_notation_value} — and was read by ingestion code
 * only. Every display, export, email and PDF path hardcoded its own scale (2,
 * or in the PDF's case 0), so Bahrain's fils were invisible everywhere outside
 * the warehouse and Egypt's piastres were dropped from every report.
 *
 * <p><b>Fail loud, never guess.</b> A tenant whose country/currency cannot be
 * resolved throws {@link CurrencyConfigurationException} rather than silently
 * inheriting AED (the old behaviour, which made a misconfigured Egypt tenant
 * both look and price like a UAE one). Display paths that must degrade
 * gracefully should call {@link #forTenantQuietly(Long)} and handle the empty
 * case explicitly — the point is that the fallback becomes a visible decision
 * at the call site instead of a hidden default three layers down.
 */
@Service
public class CurrencyResolver {

    private static final Logger log = LoggerFactory.getLogger(CurrencyResolver.class);

    /** Resolved currency identity for one tenant. */
    public record CurrencyInfo(String code, String symbol, int decimals, int divisor) {
        /** Convenience for BigDecimal.setScale(...) call sites. */
        public int scale() {
            return decimals;
        }
    }

    /** Thrown when a tenant's country/currency configuration is missing or contradictory. */
    public static class CurrencyConfigurationException extends IllegalStateException {
        public CurrencyConfigurationException(String message) {
            super(message);
        }
    }

    private final TenantRepository tenantRepository;
    private final RefCountryRepository refCountryRepository;

    // Small hot cache — resolution runs per report row in some paths. Cleared by
    // invalidate() whenever tenant or ref_country config changes, so a rate/country
    // correction never needs a JVM restart to take effect (the mistake the
    // ingest-side REF_CACHE made).
    private final ConcurrentHashMap<Long, CurrencyInfo> cache = new ConcurrentHashMap<>();

    public CurrencyResolver(TenantRepository tenantRepository, RefCountryRepository refCountryRepository) {
        this.tenantRepository = tenantRepository;
        this.refCountryRepository = refCountryRepository;
    }

    /**
     * Minor-unit divisor to decimal places: 1000 -> 3 (BHD), 100 -> 2 (AED/EGP),
     * 10 -> 1, 1 -> 0 (JPY). Anything else is rejected rather than rounded to a
     * guess, because a wrong scale silently destroys money.
     */
    public static int decimalsForDivisor(Integer divisor) {
        if (divisor == null) {
            throw new CurrencyConfigurationException("ref_country.decimal_notation_value is NULL");
        }
        switch (divisor) {
            case 1:    return 0;
            case 10:   return 1;
            case 100:  return 2;
            case 1000: return 3;
            case 10000: return 4;
            default:
                throw new CurrencyConfigurationException(
                        "ref_country.decimal_notation_value=" + divisor + " is not a power of ten (1/10/100/1000)");
        }
    }

    /** Resolve, or throw. Use on ingestion/fee paths where a wrong scale corrupts data. */
    public CurrencyInfo forTenant(Long tenantId) {
        if (tenantId == null) {
            throw new CurrencyConfigurationException("Cannot resolve currency: tenantId is null");
        }
        CurrencyInfo cached = cache.get(tenantId);
        if (cached != null) {
            return cached;
        }
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new CurrencyConfigurationException("Tenant " + tenantId + " not found"));

        String countryCode = trimToNull(tenant.getHomeCountryCode());
        if (countryCode == null) {
            throw new CurrencyConfigurationException(
                    "Tenant " + tenantId + " (" + tenant.getBankShortCode() + ") has no home_country_code — "
                            + "set the Jurisdiction in Tenant Management. Refusing to assume 'AE'.");
        }
        RefCountry country = refCountryRepository.findById(countryCode)
                .orElseThrow(() -> new CurrencyConfigurationException(
                        "Tenant " + tenantId + " home_country_code='" + countryCode + "' has no ref_country row"));

        int divisor = country.getDecimalNotationValue() == null ? 0 : country.getDecimalNotationValue();
        if (divisor <= 0) {
            throw new CurrencyConfigurationException(
                    "ref_country['" + countryCode + "'].decimal_notation_value is missing — cannot determine "
                            + "decimal places for " + country.getCurrencyCode());
        }
        int decimals = decimalsForDivisor(divisor);

        String countryCurrency = trimToNull(country.getCurrencyCode());
        String tenantCurrency = trimToNull(tenant.getBaseCurrency());
        String code;
        if (tenantCurrency == null) {
            // Tolerated: the country row is authoritative and we say so loudly.
            log.warn("Tenant {} ({}) has no base_currency; adopting {} from ref_country['{}']",
                    tenantId, tenant.getBankShortCode(), countryCurrency, countryCode);
            code = countryCurrency;
        } else if (countryCurrency != null && !tenantCurrency.equalsIgnoreCase(countryCurrency)) {
            // The exact failure mode that made a Bahrain tenant price off the UAE
            // card for months: country says one thing, currency says another.
            throw new CurrencyConfigurationException(
                    "Tenant " + tenantId + " (" + tenant.getBankShortCode() + ") is misconfigured: "
                            + "home_country_code='" + countryCode + "' implies " + countryCurrency
                            + " but base_currency='" + tenantCurrency + "'. Fix the tenant before ingesting or reporting.");
        } else {
            code = tenantCurrency;
        }

        String symbol = trimToNull(tenant.getCurrencySymbol());
        if (symbol == null) {
            symbol = trimToNull(country.getCurrencySymbol());
        }
        if (symbol == null) {
            symbol = code;
        }

        CurrencyInfo info = new CurrencyInfo(code, symbol, decimals, divisor);
        cache.put(tenantId, info);
        return info;
    }

    /** Resolve without throwing. The caller must decide what an empty result means. */
    public Optional<CurrencyInfo> forTenantQuietly(Long tenantId) {
        try {
            return Optional.of(forTenant(tenantId));
        } catch (RuntimeException e) {
            log.error("Currency unresolved for tenant {}: {}", tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Decimal places for a tenant's settlement currency. Throws when unresolvable. */
    public int decimalsFor(Long tenantId) {
        return forTenant(tenantId).decimals();
    }

    /** Populate the transient display field so the tenant row can be serialized to clients. */
    public Tenant withCurrencyDecimals(Tenant tenant) {
        if (tenant != null) {
            forTenantQuietly(tenant.getTenantId())
                    .ifPresent(info -> tenant.setCurrencyDecimals(info.decimals()));
        }
        return tenant;
    }

    public void invalidate(Long tenantId) {
        cache.remove(tenantId);
    }

    public void invalidateAll() {
        cache.clear();
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
