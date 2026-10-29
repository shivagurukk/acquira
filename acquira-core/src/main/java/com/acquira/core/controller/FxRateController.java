package com.acquira.core.controller;

import com.acquira.common.config.TenantContext;
import com.acquira.common.service.CurrencyResolver;
import com.acquira.common.service.FxRateService;
import com.acquira.common.service.FxRates;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * USD conversion rates for the executive display-currency toggle.
 *
 * <p>The frontend converts money client-side; this endpoint gives it the
 * tenant currency's rate PER DAY for a date range (carry-forward applied) plus
 * the tenant's daily volume, so a page can convert each bucket at the
 * volume-weighted rate of exactly the days it covers, and label the figures
 * with the rate dates actually used. Pegged currencies return a flat series.
 *
 * <p>Read-only; rates are written by the Integration Hub FX_RATE pull and the
 * seed migration. Authenticated like every /api route (SecurityConfig).
 */
@RestController
@RequestMapping("/api/fx")
public class FxRateController {

    private static final int MAX_DAYS = 800; // ~2 years + a margin: a LAST_YEAR + prior-year comparison

    private final FxRateService fxRateService;
    private final CurrencyResolver currencyResolver;

    public FxRateController(FxRateService fxRateService, CurrencyResolver currencyResolver) {
        this.fxRateService = fxRateService;
        this.currencyResolver = currencyResolver;
    }

    /**
     * GET /api/fx/series?from=YYYY-MM-DD&to=YYYY-MM-DD
     *
     * <pre>
     * { base: "EGP", quote: "USD", floating: true,
     *   days: [{ date, rateDate, unitsPerUsd, usdPerUnit, staleDays, volume }],
     *   effective: { usdPerUnit, unitsPerUsd, method, daysInRange, daysExact, daysCarried, daysMissing, maxStaleDays, lastRateDate },
     *   latest: { rateDate, unitsPerUsd, usdPerUnit, source } }
     * </pre>
     * 204 when the tenant currency cannot be converted (USD tenant, or a floating
     * currency with no rates loaded) — the toggle then stays hidden.
     */
    @GetMapping("/series")
    public ResponseEntity<Map<String, Object>> series(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.status(403).build();
        if (from.isAfter(to)) return ResponseEntity.badRequest().build();
        if (to.toEpochDay() - from.toEpochDay() > MAX_DAYS) return ResponseEntity.badRequest().build();

        String ccy = currencyResolver.forTenantQuietly(tenantId).map(CurrencyResolver.CurrencyInfo::code).orElse(null);
        if (ccy == null || !fxRateService.canConvert(ccy)) return ResponseEntity.noContent().build();

        boolean floating = fxRateService.isFloating(ccy);
        List<FxRateService.DayRate> days = fxRateService.series(ccy, from, to);
        FxRateService.Effective eff = fxRateService.effective(tenantId, ccy, from, to).orElse(null);
        if (days.isEmpty() || eff == null) return ResponseEntity.noContent().build();

        // Volume weights ride along so the client can weight ANY sub-range
        // (a week bucket, a month bucket) without a round trip per bucket.
        Map<LocalDate, BigDecimal> vol = volumeByDay(tenantId, from, to);

        List<Map<String, Object>> dayRows = new ArrayList<>(days.size());
        for (FxRateService.DayRate d : days) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", d.date().toString());
            m.put("rateDate", d.rateDate().toString());
            m.put("unitsPerUsd", d.unitsPerUsd());
            m.put("usdPerUnit", d.usdPerUnit());
            m.put("staleDays", d.staleDays());
            m.put("source", d.source());
            m.put("volume", vol.getOrDefault(d.date(), BigDecimal.ZERO));
            dayRows.add(m);
        }

        Map<String, Object> effective = new LinkedHashMap<>();
        effective.put("usdPerUnit", eff.usdPerUnit());
        effective.put("unitsPerUsd", eff.unitsPerUsd());
        effective.put("method", eff.method());
        effective.put("from", eff.from().toString());
        effective.put("to", eff.to().toString());
        effective.put("daysInRange", eff.daysInRange());
        effective.put("daysExact", eff.daysExact());
        effective.put("daysCarried", eff.daysCarried());
        effective.put("daysMissing", eff.daysMissing());
        effective.put("maxStaleDays", eff.maxStaleDays());
        effective.put("lastRateDate", eff.lastRateDate() == null ? null : eff.lastRateDate().toString());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("base", ccy);
        out.put("quote", "USD");
        out.put("floating", floating);
        out.put("days", dayRows);
        out.put("effective", effective);
        if (floating) {
            fxRateService.latest(ccy).ifPresent(l -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("rateDate", l.rateDate().toString());
                m.put("unitsPerUsd", l.unitsPerUsd());
                m.put("usdPerUnit", l.usdPerUnit());
                m.put("source", l.source());
                out.put("latest", m);
            });
        } else {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("usdPerUnit", FxRates.usdPerUnit(ccy));
            m.put("source", "PEG");
            m.put("asOf", FxRates.AS_OF);
            out.put("latest", m);
        }
        return ResponseEntity.ok(out);
    }

    private Map<LocalDate, BigDecimal> volumeByDay(Long tenantId, LocalDate from, LocalDate to) {
        // Same sum_daily_bank weights the service uses for its effective rate.
        return fxRateService.volumeByDay(tenantId, from, to);
    }
}
