package com.acquira.core.service.feeoptimizer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Puts every finding in front of the team that can actually fix it, with a
 * date by which it should have been dealt with.
 *
 * <h2>Why route on cause rather than on cost</h2>
 * The obvious mapping is by who pays — everything to Finance. That is also the
 * mapping under which nothing ever improves: an authorisation integrity
 * penalty sitting in a finance queue gets paid, noted, and charged again next
 * month. Routed to the team that owns the authorisation message, it gets
 * fixed at the switch and stops recurring. So the rules key on what CAUSED
 * the charge, which is usually written in the line's own description.
 *
 * <h2>What a rule matches on</h2>
 * Finding type, category, scheme and a text fragment — any of which may be
 * left open. {@code match_text} is checked against the event code AND the
 * finding's description, because which of the two carries the words
 * "processing integrity" differs by scheme and by invoice layout. Highest
 * priority wins; within a priority, the rule naming the most dimensions.
 *
 * <h2>What routing will not do</h2>
 * It never touches a finding somebody assigned by hand ({@code routed_by =
 * 'MANUAL'}), and it never re-routes one that already has a team. A nightly
 * re-scan quietly reshuffling other people's queues would make the queue
 * useless as a place to keep work.
 */
@Service
public class FindingRouter {

    private final JdbcTemplate jdbc;

    public FindingRouter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One rule, as held in {@code sfo_routing_rule}. */
    private record Rule(long ruleId, String findingType, String category, String scheme,
                        String matchText, String teamCode, Integer slaDays, int priority) {

        /** How tightly this rule names its target, to break a priority tie. */
        int specificity() {
            int s = 0;
            if (matchText   != null) s += 8;
            if (findingType != null) s += 4;
            if (category    != null) s += 2;
            if (scheme      != null) s += 1;
            return s;
        }
    }

    /**
     * Routes every unrouted finding for a tenant.
     *
     * @return how many were given a team
     */
    @Transactional
    public int route(long tenantId) {
        List<Map<String, Object>> pending = jdbc.queryForList(
                "SELECT opportunity_id, finding_type, category, scheme, event_code, " +
                "       description, detected_at " +
                "  FROM sfo_opportunity " +
                " WHERE tenant_id = ? AND team_code IS NULL", tenantId);
        if (pending.isEmpty()) return 0;

        List<Rule> rules = jdbc.query(
                "SELECT rule_id, finding_type, category, scheme, match_text, team_code, " +
                "       sla_days, priority " +
                "  FROM sfo_routing_rule WHERE tenant_id = ? AND is_active " +
                " ORDER BY priority DESC",
                (rs, i) -> new Rule(rs.getLong("rule_id"), rs.getString("finding_type"),
                        rs.getString("category"), rs.getString("scheme"),
                        rs.getString("match_text"), rs.getString("team_code"),
                        (Integer) rs.getObject("sla_days"), rs.getInt("priority")),
                tenantId);
        if (rules.isEmpty()) return 0;

        Map<String, Integer> teamSla = teamSlaDays(tenantId);

        int routed = 0;
        for (Map<String, Object> f : pending) {
            Rule best = pick(rules, f);
            if (best == null) continue;

            int sla = best.slaDays() != null ? best.slaDays()
                    : teamSla.getOrDefault(best.teamCode(), 14);
            LocalDate detected = f.get("detected_at") instanceof java.sql.Timestamp ts
                    ? ts.toLocalDateTime().toLocalDate()
                    : LocalDate.now();

            jdbc.update(
                    "UPDATE sfo_opportunity " +
                    "   SET team_code = ?, due_date = ?, routed_at = CURRENT_TIMESTAMP, " +
                    "       routed_by = 'RULE' " +
                    " WHERE tenant_id = ? AND opportunity_id = ? AND team_code IS NULL",
                    best.teamCode(), java.sql.Date.valueOf(detected.plusDays(sla)),
                    tenantId, f.get("opportunity_id"));
            routed++;
        }
        return routed;
    }

    /** The best rule for one finding, or null when nothing claims it. */
    private Rule pick(List<Rule> rules, Map<String, Object> f) {
        String type     = str(f.get("finding_type"));
        String category = str(f.get("category"));
        String scheme   = str(f.get("scheme"));
        // The cause is written in whichever of these the invoice used.
        String haystack = (str(f.get("event_code")) + " " + str(f.get("description")))
                .toLowerCase(Locale.ROOT);

        Rule best = null;
        for (Rule r : rules) {
            if (r.findingType() != null && !r.findingType().equals(type))     continue;
            if (r.category()    != null && !r.category().equals(category))    continue;
            if (r.scheme()      != null && !r.scheme().equals(scheme))        continue;
            if (r.matchText()   != null
                    && !haystack.contains(r.matchText().toLowerCase(Locale.ROOT))) continue;

            if (best == null
                    || r.priority() > best.priority()
                    || (r.priority() == best.priority() && r.specificity() > best.specificity())) {
                best = r;
            }
        }
        return best;
    }

    private Map<String, Integer> teamSlaDays(long tenantId) {
        Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT team_code, default_sla_days FROM sfo_team WHERE tenant_id = ?", tenantId)) {
            out.put(String.valueOf(r.get("team_code")),
                    ((Number) r.get("default_sla_days")).intValue());
        }
        return out;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
