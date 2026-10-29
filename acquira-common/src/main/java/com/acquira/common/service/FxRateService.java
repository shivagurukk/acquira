package com.acquira.common.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Date-aware USD conversion for floating currencies (EGP today).
 *
 * <p>Rates live in {@code fx_rate_daily}, one row per currency per date, fed by
 * the Integration Hub FX_RATE pull from the bank's MPS and by the one-off seed
 * (V2026_10_28_02). The pegged Gulf currencies are deliberately NOT in the
 * table — {@link FxRates} keeps their constants and this service defers to it.
 *
 * <p>Lookup rule (business decision 2026-10-28): the rate for a date is the row
 * ON that date, else the latest row BEFORE it ("if that day is not there use
 * previous day's"). Carry-forward is unbounded, but every result says which
 * rate date it used and how many days stale it is, so a gap is visible to the
 * screen and the export instead of being papered over.
 *
 * <p>A period figure in USD is never "total × one rate": callers either convert
 * day by day ({@link #series}) or use the volume-weighted effective rate of the
 * exact date range ({@link #effective}), which is what one rate applied to a
 * period total must be for the volume figure to come out exactly right.
 *
 * <p>Cache: the table is small (~250 rows per currency per year). Each currency's
 * rows are loaded once and refreshed after {@link #CACHE_TTL_MS}; the pull job
 * also calls {@link #invalidate()} in-process. Core and batch run in one JVM
 * (acquira-core depends on acquira-batch), so that is immediate in practice.
 */
@Service
public class FxRateService {

    private static final Logger log = LoggerFactory.getLogger(FxRateService.class);
    private static final long CACHE_TTL_MS = 5 * 60_000L;
    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);

    /** One stored row. */
    public record StoredRate(LocalDate rateDate, BigDecimal unitsPerUsd, String source) {
        public BigDecimal usdPerUnit() {
            return BigDecimal.ONE.divide(unitsPerUsd, 12, RoundingMode.HALF_UP);
        }
    }

    /**
     * The rate applied to one calendar day. {@code rateDate} is the row actually
     * used; {@code staleDays} is {@code date - rateDate} (0 = exact day).
     */
    public record DayRate(LocalDate date, LocalDate rateDate, BigDecimal unitsPerUsd,
                          BigDecimal usdPerUnit, String source, int staleDays) {
        public boolean carried() { return staleDays > 0; }
    }

    /** Rate provenance for a date range, for footers and the USD toggle badge. */
    public record Effective(String base, LocalDate from, LocalDate to,
                            BigDecimal usdPerUnit, BigDecimal unitsPerUsd,
                            String method, int daysInRange, int daysExact, int daysCarried, int daysMissing,
                            int maxStaleDays, LocalDate lastRateDate) {}

    private final JdbcTemplate jdbc;
    private final Map<String, NavigableMap<LocalDate, StoredRate>> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> loadedAt = new ConcurrentHashMap<>();

    public FxRateService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Classification ────────────────────────────────────────────────────

    /** True when this currency is converted from the dated table (not a peg, not USD). */
    public boolean isFloating(String ccy) {
        String code = norm(ccy);
        if (code == null || "USD".equals(code)) return false;
        if (FxRates.usdPerUnit(code) != null) return false;
        return !rows(code).isEmpty();
    }

    /** True when USD conversion is possible at all for this currency (peg or table). */
    public boolean canConvert(String ccy) {
        String code = norm(ccy);
        if (code == null || "USD".equals(code)) return false;
        return FxRates.usdPerUnit(code) != null || !rows(code).isEmpty();
    }

    // ── Lookups ───────────────────────────────────────────────────────────

    /** USD-per-unit for one date (peg constant, or dated row with carry-forward). Empty = cannot convert. */
    public Optional<DayRate> rateOn(String ccy, LocalDate date) {
        String code = norm(ccy);
        if (code == null || date == null || "USD".equals(code)) return Optional.empty();
        BigDecimal peg = FxRates.usdPerUnit(code);
        if (peg != null) {
            return Optional.of(new DayRate(date, date, BigDecimal.ONE.divide(peg, 8, RoundingMode.HALF_UP), peg, "PEG", 0));
        }
        return dayRate(rows(code), date);
    }

    /** Convert one amount at the rate of its business date; empty when no rate. */
    public Optional<BigDecimal> toUsd(BigDecimal amount, String ccy, LocalDate date) {
        if (amount == null) return Optional.empty();
        return rateOn(ccy, date).map(r -> amount.multiply(r.usdPerUnit()).setScale(2, RoundingMode.HALF_UP));
    }

    /** Latest stored row for a floating currency (for the toggle badge). */
    public Optional<StoredRate> latest(String ccy) {
        String code = norm(ccy);
        if (code == null) return Optional.empty();
        NavigableMap<LocalDate, StoredRate> m = rows(code);
        return m.isEmpty() ? Optional.empty() : Optional.of(m.lastEntry().getValue());
    }

    /** One {@link DayRate} per calendar day in [from, to], carry-forward applied; days with no earlier row are omitted. */
    public List<DayRate> series(String ccy, LocalDate from, LocalDate to) {
        String code = norm(ccy);
        List<DayRate> out = new ArrayList<>();
        if (code == null || from == null || to == null || from.isAfter(to)) return out;
        BigDecimal peg = FxRates.usdPerUnit(code);
        NavigableMap<LocalDate, StoredRate> m = peg == null ? rows(code) : null;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (peg != null) {
                out.add(new DayRate(d, d, BigDecimal.ONE.divide(peg, 8, RoundingMode.HALF_UP), peg, "PEG", 0));
            } else {
                dayRate(m, d).ifPresent(out::add);
            }
        }
        return out;
    }

    /**
     * Volume-weighted effective rate for a tenant over a date range, weighted by
     * {@code sum_daily_bank.total_volume} per business date. Falls back to the
     * simple average of the daily rates when the tenant has no volume in the
     * range (so the badge still shows a sensible number on an empty month).
     */
    public Optional<Effective> effective(Long tenantId, String ccy, LocalDate from, LocalDate to) {
        String code = norm(ccy);
        if (code == null || from == null || to == null || from.isAfter(to) || "USD".equals(code)) return Optional.empty();
        List<DayRate> days = series(code, from, to);
        if (days.isEmpty()) return Optional.empty();

        Map<LocalDate, BigDecimal> volumeByDay = tenantId == null ? Map.of() : volumeByDay(tenantId, from, to);
        return Optional.of(weighted(code, from, to, days, volumeByDay));
    }

    /**
     * Pure weighting step, package-visible for unit tests. {@code method} is
     * VOLUME_WEIGHTED when any weight is positive, else SIMPLE_AVERAGE.
     */
    static Effective weighted(String code, LocalDate from, LocalDate to,
                              List<DayRate> days, Map<LocalDate, BigDecimal> volumeByDay) {
        BigDecimal wSum = BigDecimal.ZERO, wRate = BigDecimal.ZERO;
        BigDecimal sRate = BigDecimal.ZERO;
        int exact = 0, carried = 0, maxStale = 0;
        LocalDate lastRateDate = null;
        for (DayRate d : days) {
            BigDecimal w = volumeByDay.getOrDefault(d.date(), BigDecimal.ZERO);
            if (w.signum() > 0) {
                wSum = wSum.add(w);
                wRate = wRate.add(w.multiply(d.usdPerUnit()));
            }
            sRate = sRate.add(d.usdPerUnit());
            if (d.carried()) carried++; else exact++;
            maxStale = Math.max(maxStale, d.staleDays());
            if (lastRateDate == null || d.rateDate().isAfter(lastRateDate)) lastRateDate = d.rateDate();
        }
        String method;
        BigDecimal usdPerUnit;
        if (wSum.signum() > 0) {
            method = "VOLUME_WEIGHTED";
            usdPerUnit = wRate.divide(wSum, MC);
        } else {
            method = "SIMPLE_AVERAGE";
            usdPerUnit = sRate.divide(BigDecimal.valueOf(days.size()), MC);
        }
        int daysInRange = (int) (to.toEpochDay() - from.toEpochDay()) + 1;
        BigDecimal unitsPerUsd = BigDecimal.ONE.divide(usdPerUnit, 6, RoundingMode.HALF_UP);
        return new Effective(code, from, to, usdPerUnit.setScale(12, RoundingMode.HALF_UP), unitsPerUsd, method,
                daysInRange, exact, carried, daysInRange - days.size(), maxStale, lastRateDate);
    }

    /** Carry-forward lookup on a sorted map. Package-visible for tests. */
    static Optional<DayRate> dayRate(NavigableMap<LocalDate, StoredRate> m, LocalDate date) {
        if (m == null || m.isEmpty()) return Optional.empty();
        Map.Entry<LocalDate, StoredRate> e = m.floorEntry(date);
        if (e == null) return Optional.empty();
        StoredRate r = e.getValue();
        int stale = (int) (date.toEpochDay() - r.rateDate().toEpochDay());
        return Optional.of(new DayRate(date, r.rateDate(), r.unitsPerUsd(), r.usdPerUnit(), r.source(), stale));
    }

    // ── Cache / IO ────────────────────────────────────────────────────────

    public void invalidate() {
        cache.clear();
        loadedAt.clear();
    }

    private NavigableMap<LocalDate, StoredRate> rows(String code) {
        Long at = loadedAt.get(code);
        NavigableMap<LocalDate, StoredRate> m = cache.get(code);
        if (m != null && at != null && System.currentTimeMillis() - at < CACHE_TTL_MS) return m;
        TreeMap<LocalDate, StoredRate> fresh = new TreeMap<>();
        try {
            jdbc.query("SELECT rate_date, units_per_usd, source FROM fx_rate_daily WHERE currency_code = ? ORDER BY rate_date",
                    rs -> {
                        LocalDate d = rs.getDate(1).toLocalDate();
                        fresh.put(d, new StoredRate(d, rs.getBigDecimal(2), rs.getString(3)));
                    }, code);
        } catch (RuntimeException e) {
            // Table missing (migration not applied yet) must not take the executive
            // screens down — it just means "no USD for floating currencies".
            log.warn("fx_rate_daily unavailable for {}: {}", code, e.getMessage());
            return m != null ? m : fresh;
        }
        cache.put(code, fresh);
        loadedAt.put(code, System.currentTimeMillis());
        return fresh;
    }

    /** Tenant daily volume weights (sum_daily_bank.total_volume) for [from, to]. */
    public Map<LocalDate, BigDecimal> volumeByDay(Long tenantId, LocalDate from, LocalDate to) {
        Map<LocalDate, BigDecimal> out = new java.util.HashMap<>();
        try {
            jdbc.query("SELECT business_date, COALESCE(total_volume, 0) FROM sum_daily_bank "
                            + "WHERE tenant_id = ? AND business_date BETWEEN ? AND ?",
                    rs -> { out.put(rs.getDate(1).toLocalDate(), rs.getBigDecimal(2)); },
                    tenantId, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
        } catch (RuntimeException e) {
            log.warn("sum_daily_bank weights unavailable for tenant {}: {}", tenantId, e.getMessage());
        }
        return out;
    }

    private static String norm(String ccy) {
        if (ccy == null) return null;
        String t = ccy.trim().toUpperCase();
        return t.isEmpty() ? null : t;
    }
}
