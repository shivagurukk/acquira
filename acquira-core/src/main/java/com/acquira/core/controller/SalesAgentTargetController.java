package com.acquira.core.controller;

import com.acquira.common.model.SalesAgentProfile;
import com.acquira.common.model.SalesAgentTarget;
import com.acquira.common.model.SalesTeamMapping;
import com.acquira.common.model.SalesUserAssignment;
import com.acquira.common.repository.SalesAgentProfileRepository;
import com.acquira.common.repository.SalesAgentTargetRepository;
import com.acquira.common.repository.SalesTeamMappingRepository;
import com.acquira.common.repository.SalesUserAssignmentRepository;
import com.acquira.core.service.SalesTargetResolver;
import com.acquira.core.service.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Admin CRUD for per-agent sales targets.
 *
 * <p><b>Entered yearly, stored monthly.</b> The caller supplies one annual figure
 * per agent plus a phasing strategy; this controller writes the twelve monthly
 * rows. Nothing else in the system needs to know how a year was phased —
 * {@link SalesTargetResolver} only ever reads months. That is what lets an MTD
 * view prorate correctly without any caller re-deriving a split.
 *
 * <p><b>Ships empty.</b> There is no seed and no backfill from the deprecated
 * {@code sales_agent_profile.monthly_target}. Until an admin saves here, the
 * Executive Sales Pulse page shows "—" in every Target cell and classifies
 * momentum purely from ingested merchant history.
 *
 * <p><b>Multi-tenant, defence in depth.</b> Three independent layers:
 * <ol>
 *   <li>Postgres RLS on {@code sales_agent_target}.</li>
 *   <li>Every query is scoped by the tenant from {@link TenantService}, which
 *       reads the filter-validated {@code TenantContext} — never the raw
 *       {@code X-Tenant-Id} header, which is attacker-controlled.</li>
 *   <li>{@link #assertAgentBelongsToTenant} rejects a write naming an agent that
 *       is not this tenant's, so a crafted {@code salesUserId} cannot plant a row
 *       that another tenant would later read.</li>
 * </ol>
 *
 * <p>Guarded at class level to ADMIN / SUPER_ADMIN: targets are a management
 * control, and a rep must not be able to lower their own number.
 */
@RestController
@RequestMapping("/api/sales/targets")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class SalesAgentTargetController {

    /** Measures a target may be set against. Mirrors the Pulse page's metric options. */
    private static final Set<String> VALID_METRICS =
            Set.of("NET_REVENUE", "BASE_VOLUME", "VOLUME", "MSF", "TXNS");

    private static final Set<String> VALID_PHASING = Set.of("EQUAL", "MANUAL");

    /** How far from today a target year may sit. Blocks fat-finger years like 20226. */
    private static final int YEAR_RADIUS = 5;

    private final SalesAgentTargetRepository targetRepository;
    private final SalesAgentProfileRepository profileRepository;
    private final SalesUserAssignmentRepository assignmentRepository;
    private final SalesTeamMappingRepository teamMappingRepository;
    private final TenantService tenantService;
    private final CurrencyMeta currencyMeta;

    private Long getTenantId() {
        Long t = tenantService.getCurrentTenantId();
        if (t == null) throw new IllegalStateException("No tenant context");
        return t;
    }

    private String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : null;
    }

    // ═══════════════════════════════════════════════════════════
    //  READ — the admin grid for one year
    // ═══════════════════════════════════════════════════════════

    /**
     * Every agent the tenant knows about, each with their twelve monthly targets
     * for {@code year} and the annual total.
     *
     * <p>Agents with NO targets are returned with twelve nulls rather than being
     * omitted — the admin page renders an empty, editable row for them, which is
     * the normal state before anyone has entered anything.
     */
    @GetMapping("/{year}")
    public ResponseEntity<?> getYear(@PathVariable int year,
                                     @RequestParam(defaultValue = SalesTargetResolver.DEFAULT_METRIC) String metric) {
        Long tenantId = getTenantId();
        String err = validateYear(year);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        if (!VALID_METRICS.contains(metric)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown metric: " + metric));
        }

        // salesUserId -> monthKey -> value, for this metric only.
        Map<String, Map<Integer, BigDecimal>> byAgent = new HashMap<>();
        Map<String, String> sourceByAgent = new HashMap<>();
        for (SalesAgentTarget t : targetRepository.findYear(tenantId, year * 100 + 1, year * 100 + 12)) {
            if (!metric.equals(t.getMetricType())) continue;
            byAgent.computeIfAbsent(t.getSalesUserId(), k -> new HashMap<>())
                   .put(t.getMonthKey(), t.getTargetValue());
            sourceByAgent.put(t.getSalesUserId(), t.getSource());
        }

        // Team lead per agent, so the page can group and bulk-apply by team.
        Map<String, Long> agentToTeam = new HashMap<>();
        for (SalesUserAssignment a : assignmentRepository.findAllByTenantId(tenantId)) {
            agentToTeam.put(a.getSalesUserId(), a.getTeamLeadId());
        }
        Map<Long, String> teamNames = new HashMap<>();
        for (SalesTeamMapping t : teamMappingRepository.findAllByTenantId(tenantId)) {
            teamNames.put(t.getId(), t.getTeamLeadName());
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (SalesAgentProfile p : profileRepository.findAllByTenantId(tenantId)) {
            Map<Integer, BigDecimal> months = byAgent.getOrDefault(p.getSalesUserId(), Map.of());

            List<BigDecimal> monthly = new ArrayList<>(12);
            BigDecimal annual = BigDecimal.ZERO;
            boolean any = false;
            for (int m = 1; m <= 12; m++) {
                BigDecimal v = months.get(year * 100 + m);
                monthly.add(v);
                if (v != null) { annual = annual.add(v); any = true; }
            }

            Long teamId = agentToTeam.get(p.getSalesUserId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("salesUserId", p.getSalesUserId());
            row.put("displayName", p.getDisplayName() != null && !p.getDisplayName().isBlank()
                    ? p.getDisplayName() : p.getSalesUserId());
            row.put("salesEmail", p.getSalesEmail());
            row.put("status", p.getStatus());
            row.put("teamLeadId", teamId);
            row.put("teamLeadName", teamId != null ? teamNames.get(teamId) : null);
            row.put("months", monthly);                       // 12 entries, nulls allowed
            row.put("annualTarget", any ? annual : null);     // null = nothing configured
            row.put("source", sourceByAgent.get(p.getSalesUserId()));
            // Surfaced so the admin can see what the agent's number would fall back
            // to today. Read-only here; edit it on the Agent Directory screen.
            row.put("legacyMonthlyTarget", p.getMonthlyTarget());
            rows.add(row);
        }
        rows.sort(Comparator.comparing(r -> String.valueOf(r.get("displayName")), String.CASE_INSENSITIVE_ORDER));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", year);
        out.put("metric", metric);
        out.put("agents", rows);
        out.put("configured", targetRepository.existsByTenantId(tenantId));
        return ResponseEntity.ok(currencyMeta.attach(out, tenantId));
    }

    // ═══════════════════════════════════════════════════════════
    //  WRITE — one agent's year
    // ═══════════════════════════════════════════════════════════

    /**
     * Sets one agent's whole year.
     *
     * <pre>{@code
     * { "year": 2026, "salesUserId": "SE001", "annualTarget": 960000,
     *   "phasing": "EQUAL", "metric": "NET_REVENUE" }
     * }</pre>
     *
     * With {@code phasing: "MANUAL"}, pass {@code months} — twelve values, nulls
     * allowed for months with no target — and {@code annualTarget} is ignored.
     *
     * <p>The year is replaced, not merged: the twelve existing rows are deleted
     * and rewritten in one transaction. Merging would leave stale months behind
     * when a year is re-phased, and a half-updated year is worse than either
     * outcome.
     */
    @PostMapping("/yearly")
    @Transactional
    public ResponseEntity<?> saveYear(@RequestBody Map<String, Object> payload) {
        Long tenantId = getTenantId();
        try {
            int written = applyOne(tenantId, payload);
            return ResponseEntity.ok(Map.of("saved", true, "monthsWritten", written));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Same payload shape, applied to many agents in ONE transaction — the "set the
     * whole team to 800k" path. All-or-nothing on purpose: a partial bulk apply
     * leaves an admin unable to tell which half landed.
     */
    @PostMapping("/bulk")
    @Transactional
    public ResponseEntity<?> saveBulk(@RequestBody List<Map<String, Object>> payloads) {
        Long tenantId = getTenantId();
        if (payloads == null || payloads.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No targets supplied"));
        }
        try {
            int agents = 0, months = 0;
            for (Map<String, Object> p : payloads) {
                months += applyOne(tenantId, p);
                agents++;
            }
            return ResponseEntity.ok(Map.of("saved", true, "agentsUpdated", agents, "monthsWritten", months));
        } catch (IllegalArgumentException e) {
            // @Transactional + a thrown RuntimeException rolls the whole batch back.
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** Clears one agent's year back to "no target configured". */
    @DeleteMapping("/{year}/{salesUserId}")
    @Transactional
    public ResponseEntity<?> clearYear(@PathVariable int year, @PathVariable String salesUserId,
                                       @RequestParam(defaultValue = SalesTargetResolver.DEFAULT_METRIC) String metric) {
        Long tenantId = getTenantId();
        String err = validateYear(year);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        if (!VALID_METRICS.contains(metric)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown metric: " + metric));
        }
        assertAgentBelongsToTenant(tenantId, salesUserId);
        int deleted = targetRepository.deleteYearForAgent(
                tenantId, salesUserId, metric, year * 100 + 1, year * 100 + 12);
        return ResponseEntity.ok(Map.of("cleared", true, "monthsDeleted", deleted));
    }

    // ═══════════════════════════════════════════════════════════
    //  INTERNALS
    // ═══════════════════════════════════════════════════════════

    /** Validates and writes one agent's year. Returns the number of months written. */
    private int applyOne(Long tenantId, Map<String, Object> payload) {
        int year = intOf(payload.get("year"), "year");
        String err = validateYear(year);
        if (err != null) throw new IllegalArgumentException(err);

        String salesUserId = str(payload.get("salesUserId"));
        if (salesUserId == null || salesUserId.isBlank()) {
            throw new IllegalArgumentException("salesUserId is required");
        }
        assertAgentBelongsToTenant(tenantId, salesUserId);

        String metric = str(payload.get("metric"));
        if (metric == null || metric.isBlank()) metric = SalesTargetResolver.DEFAULT_METRIC;
        if (!VALID_METRICS.contains(metric)) throw new IllegalArgumentException("Unknown metric: " + metric);

        String phasing = str(payload.get("phasing"));
        if (phasing == null || phasing.isBlank()) phasing = "EQUAL";
        if (!VALID_PHASING.contains(phasing)) throw new IllegalArgumentException("Unknown phasing: " + phasing);

        BigDecimal[] months = "MANUAL".equals(phasing)
                ? manualMonths(payload.get("months"))
                : equalSplit(decimal(payload.get("annualTarget"), "annualTarget"));

        // Replace, don't merge — see the javadoc on saveYear.
        targetRepository.deleteYearForAgent(tenantId, salesUserId, metric, year * 100 + 1, year * 100 + 12);

        String user = currentUser();
        List<SalesAgentTarget> toSave = new ArrayList<>(12);
        int written = 0;
        for (int m = 1; m <= 12; m++) {
            BigDecimal v = months[m - 1];
            if (v == null) continue;             // a month left blank stays unset
            SalesAgentTarget t = new SalesAgentTarget();
            t.setTenantId(tenantId);
            t.setSalesUserId(salesUserId);
            t.setMonthKey(year * 100 + m);
            t.setTargetValue(v);
            t.setMetricType(metric);
            t.setSource(phasing);
            t.setCreatedBy(user);
            toSave.add(t);
            written++;
        }
        targetRepository.saveAll(toSave);
        return written;
    }

    /**
     * Splits an annual figure into twelve months. The remainder from the division
     * lands on December, so the twelve months sum EXACTLY to the annual number the
     * admin typed — a target that doesn't add up to itself destroys trust in every
     * number on the page.
     */
    private BigDecimal[] equalSplit(BigDecimal annual) {
        BigDecimal[] out = new BigDecimal[12];
        if (annual == null || annual.signum() <= 0) {
            throw new IllegalArgumentException("annualTarget must be greater than zero");
        }
        BigDecimal per = annual.divide(BigDecimal.valueOf(12), 4, RoundingMode.DOWN);
        BigDecimal running = BigDecimal.ZERO;
        for (int i = 0; i < 11; i++) {
            out[i] = per;
            running = running.add(per);
        }
        out[11] = annual.subtract(running);
        return out;
    }

    /** Twelve hand-entered months; nulls and blanks mean "no target for that month". */
    private BigDecimal[] manualMonths(Object raw) {
        if (!(raw instanceof List<?> list) || list.size() != 12) {
            throw new IllegalArgumentException("months must be an array of exactly 12 values");
        }
        BigDecimal[] out = new BigDecimal[12];
        for (int i = 0; i < 12; i++) {
            Object v = list.get(i);
            if (v == null || (v instanceof String s && s.isBlank())) continue;
            BigDecimal d = decimal(v, "months[" + i + "]");
            if (d.signum() < 0) throw new IllegalArgumentException("months[" + i + "] must not be negative");
            out[i] = d;
        }
        return out;
    }

    /**
     * A write may only name an agent that exists under the CALLER's tenant.
     * Without this, a crafted salesUserId would write a row that another tenant's
     * resolver could later pick up — RLS does not prevent inserting a row with
     * your own tenant_id and someone else's agent code.
     */
    private void assertAgentBelongsToTenant(Long tenantId, String salesUserId) {
        if (profileRepository.findByTenantIdAndSalesUserId(tenantId, salesUserId).isEmpty()) {
            throw new IllegalArgumentException("Unknown sales agent for this tenant: " + salesUserId);
        }
    }

    private String validateYear(int year) {
        int now = LocalDate.now().getYear();
        if (year < now - YEAR_RADIUS || year > now + YEAR_RADIUS) {
            return "year must be within " + YEAR_RADIUS + " years of " + now;
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int intOf(Object o, String field) {
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " must be a number");
        }
    }

    private static BigDecimal decimal(Object o, String field) {
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " must be a number");
        }
    }
}
