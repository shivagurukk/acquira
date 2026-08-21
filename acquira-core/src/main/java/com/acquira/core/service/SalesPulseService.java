package com.acquira.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Executive Sales Pulse — turns ingested merchant history into executive signals.
 *
 * <p>The page's whole value is the last step: not "here are the numbers" but
 * "here is who is improving, who is declining, and where to look". This class
 * owns that derivation; the controller only assembles and serialises.
 *
 * <h3>What "sales" means here</h3>
 * NET MARGIN — {@code total_msf - total_interchange - total_scheme_fee}, summed
 * from {@code sum_daily_merchant}. That is deliberately the same expression the
 * Sales Leaderboard and Sales Portfolio screens rank on: if this page used a
 * different measure, an executive would see three pages disagreeing about who the
 * top performer is. Volume travels alongside as secondary context.
 *
 * <p>Nothing here reads a target. Actuals come exclusively from ingested merchant
 * data, so the page is fully functional with zero targets configured — which is
 * the state it ships in. Targets are layered on afterwards by the controller via
 * {@link SalesTargetResolver}, and influence only the Target column and (when
 * present) one ATTENTION rule.
 *
 * <h3>Momentum grain: complete calendar months, always</h3>
 * Momentum reads the last N COMPLETE months ending at the tenant's latest
 * {@code business_date}, regardless of the filter period the user picked. Two
 * reasons:
 * <ul>
 *   <li>An in-progress month is a partial month. Comparing 12 days against 30-day
 *       months would flag the entire salesforce as declining, every month, until
 *       roughly the 25th.</li>
 *   <li>Momentum derived from arbitrary custom ranges is noise — "last 6 comparable
 *       periods" is only meaningful when the periods are the same length.</li>
 * </ul>
 * The filter period drives the headline figures; momentum is a separate, stable
 * read. The window actually used is published as {@code momentumWindow} so the UI
 * can state it rather than implying the numbers are about the selected range.
 *
 * <h3>Cost</h3>
 * Two aggregate queries total for the whole organisation — one for the selected
 * period and its comparison, one for the monthly series — regardless of how many
 * reps, teams or leads exist. Everything else is in-memory. Do not turn any of
 * this into a query per agent.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SalesPulseService {

    private final JdbcTemplate jdbcTemplate;
    private final SalesPulseProperties props;

    /** Same net-margin expression the leaderboard and portfolio screens use. */
    private static final String NET_EXPR =
        "COALESCE(sdm.total_msf,0) - COALESCE(sdm.total_interchange,0) - COALESCE(sdm.total_scheme_fee,0)";

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    // ── Momentum states, in escalating order of executive concern ──────────────
    public static final String NEW          = "NEW";
    public static final String ACCELERATING = "ACCELERATING";
    public static final String STRONG       = "STRONG";
    public static final String STABLE       = "STABLE";
    public static final String SLOWING      = "SLOWING";
    public static final String ATTENTION    = "ATTENTION";

    /** The two states that count towards "Needs Attention" on the summary card. */
    public static final Set<String> NEEDS_ATTENTION = Set.of(SLOWING, ATTENTION);

    // ── Signals ────────────────────────────────────────────────────────────────
    public static final String SIG_TOP_PERFORMER    = "TOP_PERFORMER";
    public static final String SIG_FASTEST_IMPROVING= "FASTEST_IMPROVING";
    public static final String SIG_MOST_CONSISTENT  = "MOST_CONSISTENT";
    public static final String SIG_BIGGEST_DECLINE  = "BIGGEST_DECLINE";
    public static final String SIG_BELOW_AVERAGE    = "BELOW_PERSONAL_AVERAGE";
    public static final String SIG_CONSEC_DECLINE   = "CONSECUTIVE_DECLINE";
    public static final String SIG_TEAM_DEPENDENCY  = "TEAM_DEPENDENCY";

    /** Which signal wins the single "Executive Signal" cell, most urgent first. */
    private static final List<String> SIGNAL_PRIORITY = List.of(
        SIG_BIGGEST_DECLINE, SIG_CONSEC_DECLINE, SIG_BELOW_AVERAGE,
        SIG_TOP_PERFORMER, SIG_FASTEST_IMPROVING, SIG_MOST_CONSISTENT, SIG_TEAM_DEPENDENCY);

    // ═══════════════════════════════════════════════════════════
    //  DATA ACCESS
    // ═══════════════════════════════════════════════════════════

    /** One agent's sales in the selected window and in its comparison window. */
    public record WindowSales(double sales, double prevSales, double volume, double txns, long merchants) {}

    /**
     * Current + previous period for every agent, in ONE scan. Both windows are
     * aggregated with FILTER clauses over a single date range, the same technique
     * {@code LeaderboardService} uses.
     */
    public Map<String, WindowSales> windowSales(Long tenantId, String from, String to,
                                                String prevFrom, String prevTo, boolean hasPrev) {
        String sql =
            "WITH bounds AS (SELECT ?::date AS cf, ?::date AS ct, ?::date AS pf, ?::date AS pt)"
          + " SELECT m.sales_user_id AS agent,"
          + "   COALESCE(SUM(" + NET_EXPR + ") FILTER (WHERE sdm.business_date BETWEEN b.cf AND b.ct), 0) AS sales,"
          + "   COALESCE(SUM(" + NET_EXPR + ") FILTER (WHERE sdm.business_date BETWEEN b.pf AND b.pt), 0) AS prev_sales,"
          + "   COALESCE(SUM(sdm.total_base_volume) FILTER (WHERE sdm.business_date BETWEEN b.cf AND b.ct), 0) AS volume,"
          + "   COALESCE(SUM(sdm.total_txns) FILTER (WHERE sdm.business_date BETWEEN b.cf AND b.ct), 0) AS txns,"
          + "   COUNT(DISTINCT sdm.merchant_id) FILTER (WHERE sdm.business_date BETWEEN b.cf AND b.ct) AS merchants"
          + " FROM sum_daily_merchant sdm"
          + " CROSS JOIN bounds b"
          + " JOIN dim_merchant m ON m.merchant_id = sdm.merchant_id AND m.tenant_id = sdm.tenant_id"
          + " WHERE sdm.tenant_id = ?"
          + "   AND sdm.business_date BETWEEN LEAST(b.cf, b.pf) AND GREATEST(b.ct, b.pt)"
          + "   AND m.sales_user_id IS NOT NULL AND m.sales_user_id <> ''"
          + " GROUP BY m.sales_user_id";

        Map<String, WindowSales> out = new HashMap<>();
        for (Map<String, Object> r : jdbcTemplate.queryForList(sql, from, to, prevFrom, prevTo, tenantId)) {
            out.put((String) r.get("agent"), new WindowSales(
                num(r.get("sales")),
                hasPrev ? num(r.get("prev_sales")) : 0.0,
                num(r.get("volume")),
                num(r.get("txns")),
                (long) num(r.get("merchants"))));
        }
        return out;
    }

    /** The complete-month window momentum is computed over. */
    public record MomentumWindow(YearMonth first, YearMonth last) {
        public String firstLabel() { return first.toString(); }
        public String lastLabel()  { return last.toString(); }
    }

    /**
     * The last N COMPLETE months at or before the anchor.
     *
     * <p>If the anchor is not the final day of its month, that month is still
     * accumulating and is excluded — see the class javadoc on why a partial month
     * poisons every comparison.
     */
    public MomentumWindow momentumWindow(LocalDate anchor) {
        YearMonth anchorMonth = YearMonth.from(anchor);
        YearMonth last = anchor.equals(anchorMonth.atEndOfMonth()) ? anchorMonth : anchorMonth.minusMonths(1);
        return new MomentumWindow(last.minusMonths(Math.max(1, props.getMomentumMonths()) - 1L), last);
    }

    /**
     * Monthly sales per agent across the momentum window, in ONE scan.
     *
     * <p>Months with no rows are filled with zero, but only from an agent's FIRST
     * month with data onward. Back-filling zeroes before a rep started would
     * manufacture a fake ramp; leaving a genuine drop-to-nothing as a gap would
     * hide a real decline. Both matter, and they are different cases.
     */
    public Map<String, List<Double>> monthlySeries(Long tenantId, MomentumWindow w) {
        List<YearMonth> months = new ArrayList<>();
        for (YearMonth m = w.first(); !m.isAfter(w.last()); m = m.plusMonths(1)) months.add(m);

        String sql =
            "SELECT m.sales_user_id AS agent, TO_CHAR(sdm.business_date, 'YYYY-MM') AS month,"
          + "   COALESCE(SUM(" + NET_EXPR + "), 0) AS sales"
          + " FROM sum_daily_merchant sdm"
          + " JOIN dim_merchant m ON m.merchant_id = sdm.merchant_id AND m.tenant_id = sdm.tenant_id"
          + " WHERE sdm.tenant_id = ? AND sdm.business_date BETWEEN ?::date AND ?::date"
          + "   AND m.sales_user_id IS NOT NULL AND m.sales_user_id <> ''"
          + " GROUP BY m.sales_user_id, TO_CHAR(sdm.business_date, 'YYYY-MM')";

        Map<String, Map<String, Double>> raw = new HashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, tenantId,
                w.first().atDay(1).toString(), w.last().atEndOfMonth().toString());
        for (Map<String, Object> r : rows) {
            raw.computeIfAbsent((String) r.get("agent"), k -> new HashMap<>())
               .put((String) r.get("month"), num(r.get("sales")));
        }

        Map<String, List<Double>> out = new HashMap<>();
        for (Map.Entry<String, Map<String, Double>> e : raw.entrySet()) {
            Map<String, Double> byMonth = e.getValue();
            List<Double> series = new ArrayList<>();
            boolean started = false;
            for (YearMonth m : months) {
                Double v = byMonth.get(m.format(MONTH_FMT));
                if (v == null && !started) continue;   // before this agent's first data
                started = true;
                series.add(v == null ? 0.0 : v);
            }
            out.put(e.getKey(), series);
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════
    //  MOMENTUM
    // ═══════════════════════════════════════════════════════════

    /** Everything derived from one agent's monthly history. */
    public record Momentum(String state, Double growthPct, Double recentAverage,
                           int consecutiveDeclines, int consecutiveGrowth, List<Double> series) {}

    /**
     * Classifies one agent's direction from their monthly series.
     *
     * <p><b>Evaluation order is load-bearing.</b> ATTENTION is tested before
     * SLOWING and before the positive states, because the spec's conditions
     * overlap: an agent can be 15% up on last month while sitting at half their
     * six-month average, and the executive needs to see the second fact. Testing
     * ACCELERATING first would hide it.
     *
     * @param targetAttainmentPct attainment when a target exists, else null. Null
     *        NEVER worsens the classification — an agent without a target is
     *        judged purely on their own history, per the product rule that a
     *        missing target must not read as underperformance.
     */
    public Momentum classify(List<Double> series, Double targetAttainmentPct) {
        if (series == null || series.size() < Math.max(2, props.getMinMonthsForMomentum())) {
            return new Momentum(NEW, null, null, 0, 0, series == null ? List.of() : series);
        }

        int n = series.size();
        double current = series.get(n - 1);
        double previous = series.get(n - 2);

        // "Recent average" excludes the current month — the point is to compare
        // this month AGAINST the recent norm, and including it drags the norm
        // toward the very value being tested.
        List<Double> prior = series.subList(0, n - 1);
        double recentAverage = prior.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        Double growthPct = previous > 0
                ? round1((current - previous) / previous * 100.0)
                : null;   // growth from zero is undefined, not infinite

        int declines = 0;
        for (int i = n - 1; i > 0; i--) {
            if (series.get(i) < series.get(i - 1)) declines++; else break;
        }
        int growths = 0;
        for (int i = n - 1; i > 0; i--) {
            if (series.get(i) > series.get(i - 1)) growths++; else break;
        }

        String state = decide(current, recentAverage, growthPct, declines, growths, targetAttainmentPct);
        return new Momentum(state, growthPct, round1(recentAverage), declines, growths, series);
    }

    private String decide(double current, double recentAverage, Double growthPct,
                          int declines, int growths, Double attainmentPct) {

        // ── ATTENTION: significantly below own norm, a sustained slide, or (only
        //    when a target exists) badly short of it.
        boolean farBelowNorm = recentAverage > 0 && current < recentAverage * props.getAttentionRatio();
        boolean longSlide = declines >= props.getAttentionDeclineStreak();
        boolean badlyShortOfTarget = attainmentPct != null && attainmentPct < props.getAttentionAttainmentPct();
        if (farBelowNorm || longSlide || badlyShortOfTarget) return ATTENTION;

        // ── ACCELERATING: a strong jump that is part of an actual upward run, not
        //    a one-month spike off a bad month.
        if (growthPct != null && growthPct >= props.getAcceleratingGrowthPct() && growths >= 2) {
            return ACCELERATING;
        }

        // ── SLOWING: a material drop, or a shorter decline streak.
        if ((growthPct != null && growthPct < props.getSlowingGrowthPct())
                || declines >= props.getSlowingDeclineStreak()) {
            return SLOWING;
        }

        // ── STRONG: consistently above their own recent average.
        if (recentAverage > 0 && current >= recentAverage * props.getStrongRatio()) return STRONG;

        // ── STABLE: inside the band around their norm — and the default, because
        //    "no strong signal" is the honest answer for most people most months.
        return STABLE;
    }

    // ═══════════════════════════════════════════════════════════
    //  TEAM DEPENDENCY
    // ═══════════════════════════════════════════════════════════

    public record Dependency(String status, String topContributor, Double topSharePct) {}

    /**
     * Flags a team whose sales rest on one person. This is a risk signal, not a
     * performance one: a team at 58% from a single rep is one resignation away
     * from a hole, however good the headline number looks.
     */
    public Dependency dependency(List<Map<String, Object>> members, double teamSales) {
        if (teamSales <= 0 || members.isEmpty()) return new Dependency("NORMAL", null, null);

        Map<String, Object> top = null;
        double topSales = 0;
        for (Map<String, Object> m : members) {
            double s = num(m.get("sales"));
            if (top == null || s > topSales) { top = m; topSales = s; }
        }
        double share = topSales / teamSales * 100.0;
        String status = share > props.getHighDependencyPct() ? "HIGH"
                      : share > props.getModerateDependencyPct() ? "MODERATE"
                      : "NORMAL";
        return new Dependency(status, String.valueOf(top.get("name")), round1(share));
    }

    // ═══════════════════════════════════════════════════════════
    //  SIGNALS
    // ═══════════════════════════════════════════════════════════

    /**
     * Assigns org-wide superlatives and per-agent flags, mutating each row's
     * {@code signals} list and setting a single {@code signal} for the table cell.
     *
     * <p>Superlatives are computed across the FILTERED population — if the user is
     * looking at one team, "Top Performer" means top of that team, which is what
     * they are asking about.
     */
    public void applySignals(List<Map<String, Object>> agents) {
        if (agents.isEmpty()) return;

        Map<String, Object> topPerformer = null, fastest = null, biggestDecline = null, consistent = null;
        double topSales = 0, fastestGrowth = 0, worstGrowth = 0, lowestCv = Double.MAX_VALUE;

        // "Most consistent" must also be doing well — low variance around a poor
        // number is not a compliment. Gate on the population's median sales.
        double medianSales = median(agents.stream().map(a -> num(a.get("sales"))).sorted().toList());

        for (Map<String, Object> a : agents) {
            double sales = num(a.get("sales"));
            Object growthRaw = a.get("growthPct");
            Double growth = growthRaw instanceof Number g ? g.doubleValue() : null;

            if (sales > 0 && (topPerformer == null || sales > topSales)) { topPerformer = a; topSales = sales; }
            if (growth != null && growth > 0 && growth > fastestGrowth) { fastest = a; fastestGrowth = growth; }
            if (growth != null && growth < 0 && growth < worstGrowth) { biggestDecline = a; worstGrowth = growth; }

            @SuppressWarnings("unchecked")
            List<Double> series = (List<Double>) a.get("series");
            if (series != null && series.size() >= 4 && sales >= medianSales) {
                Double cv = coefficientOfVariation(series);
                if (cv != null && cv < lowestCv) { lowestCv = cv; consistent = a; }
            }
        }

        if (topPerformer != null)    addSignal(topPerformer, SIG_TOP_PERFORMER);
        if (fastest != null)         addSignal(fastest, SIG_FASTEST_IMPROVING);
        if (biggestDecline != null)  addSignal(biggestDecline, SIG_BIGGEST_DECLINE);
        if (consistent != null)      addSignal(consistent, SIG_MOST_CONSISTENT);

        for (Map<String, Object> a : agents) {
            double sales = num(a.get("sales"));
            Object avgRaw = a.get("recentAverage");
            if (avgRaw instanceof Number avg && avg.doubleValue() > 0
                    && sales < avg.doubleValue() * props.getBelowAverageRatio()) {
                addSignal(a, SIG_BELOW_AVERAGE);
            }
            if (num(a.get("consecutiveDeclines")) >= props.getSlowingDeclineStreak()) {
                addSignal(a, SIG_CONSEC_DECLINE);
            }
            if (num(a.get("teamContribution")) > props.getHighDependencyPct()) {
                addSignal(a, SIG_TEAM_DEPENDENCY);
            }
            a.put("signal", primarySignal(a));
        }
    }

    @SuppressWarnings("unchecked")
    private void addSignal(Map<String, Object> agent, String signal) {
        ((List<String>) agent.computeIfAbsent("signals", k -> new ArrayList<String>())).add(signal);
    }

    @SuppressWarnings("unchecked")
    private String primarySignal(Map<String, Object> agent) {
        List<String> signals = (List<String>) agent.get("signals");
        if (signals == null || signals.isEmpty()) return null;
        for (String s : SIGNAL_PRIORITY) if (signals.contains(s)) return s;
        return signals.get(0);
    }

    // ═══════════════════════════════════════════════════════════
    //  EXECUTIVE INSIGHT
    // ═══════════════════════════════════════════════════════════

    /**
     * The one-or-two sentence summary under the cards.
     *
     * <p>Every clause is generated from a computed figure — there is no template
     * that can assert something the data does not support. When there is nothing
     * to compare against, the sentence says so rather than inventing a direction.
     * Capped at two sentences by construction, not by truncation.
     */
    public String insight(Double growthPct, String topTeamName,
                          int needsAttentionCount, int longDeclineCount) {
        StringBuilder sb = new StringBuilder();

        if (growthPct == null) {
            sb.append("Sales for the selected period are shown without a comparison, as there is no equivalent previous period in the data.");
        } else {
            String dir = growthPct > 0.5 ? "up" : growthPct < -0.5 ? "down" : "broadly flat";
            if ("broadly flat".equals(dir)) {
                sb.append("Sales are broadly flat against the previous period");
            } else {
                sb.append("Sales are ").append(dir).append(' ')
                  .append(fmtPct(Math.abs(growthPct))).append(" compared with the previous period");
            }
            if (topTeamName != null) {
                sb.append(", and ").append(topTeamName).append("'s team is leading performance");
            }
            sb.append('.');
        }

        if (needsAttentionCount > 0) {
            sb.append(' ').append(count(needsAttentionCount, "sales executive", "sales executives"))
              .append(needsAttentionCount == 1 ? " is " : " are ")
              .append("declining against their recent average");
            if (longDeclineCount > 0) {
                sb.append(", including ").append(numberWord(longDeclineCount).toLowerCase(Locale.ROOT))
                  .append(longDeclineCount == 1 ? " who has" : " who have")
                  .append(" declined for ").append(props.getAttentionDeclineStreak())
                  .append(" consecutive months");
            }
            sb.append('.');
        } else if (growthPct != null) {
            sb.append(" No sales executive is currently below their recent average.");
        }

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════

    /**
     * Relative standard deviation. Scale-free, so a rep doing 2M and a rep doing
     * 50K can be compared on steadiness — which a raw standard deviation cannot do.
     */
    static Double coefficientOfVariation(List<Double> series) {
        if (series == null || series.size() < 2) return null;
        double mean = series.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        if (mean <= 0) return null;
        double variance = series.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / series.size();
        return Math.sqrt(variance) / mean;
    }

    static double median(List<Double> sorted) {
        if (sorted.isEmpty()) return 0;
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    /** Percentage change, null when the base is zero — undefined, not infinite. */
    public static Double changePct(double current, double previous) {
        if (previous == 0) return null;
        return round1((current - previous) / Math.abs(previous) * 100.0);
    }

    public static Double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static String fmtPct(double v) {
        return (Math.round(v * 10.0) / 10.0) + "%";
    }

    private static String count(int n, String singular, String plural) {
        return numberWord(n) + " " + (n == 1 ? singular : plural);
    }

    /** Small numbers read as words in prose; anything larger stays numeric. */
    private static String numberWord(int n) {
        return switch (n) {
            case 1 -> "One"; case 2 -> "Two"; case 3 -> "Three"; case 4 -> "Four";
            case 5 -> "Five"; case 6 -> "Six"; case 7 -> "Seven"; case 8 -> "Eight";
            case 9 -> "Nine"; case 10 -> "Ten"; default -> String.valueOf(n);
        };
    }
}
