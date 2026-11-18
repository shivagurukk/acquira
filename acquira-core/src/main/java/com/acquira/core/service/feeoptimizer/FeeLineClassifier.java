package com.acquira.core.service.feeoptimizer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decides which category a billing line belongs to, and says how sure it is.
 *
 * <h2>Resolution order — most specific first</h2>
 * <ol>
 *   <li><b>Override</b> — this tenant has said what this code means. Final,
 *       confidence 100.</li>
 *   <li><b>Dictionary</b> — the code has been seen before and settled, either
 *       globally or for this tenant.</li>
 *   <li><b>Pattern</b> — the description reads like a known kind of charge.
 *       Confidence comes from the pattern, always below 100.</li>
 *   <li><b>Nothing</b> — {@code OTHER} at confidence 0, said plainly rather
 *       than guessed.</li>
 * </ol>
 *
 * <h2>Learning</h2>
 * The first time a code is seen it is written into the dictionary with the
 * pattern's verdict, flagged {@code learned}. That turns a one-off guess into
 * something a person can correct once and have stay corrected — and makes the
 * dictionary grow from the invoices actually received rather than from a list
 * someone would have had to transcribe up front.
 *
 * A row a human has edited ({@code learned = FALSE}) is never overwritten by
 * a later guess.
 */
@Service
public class FeeLineClassifier {

    public static final List<String> CATEGORIES = List.of(
            "AUTHORISATION", "CLEARING", "CROSS_BORDER",
            "ASSESSMENTS", "FIXED_FEES", "PENALTIES", "OTHER");

    private final JdbcTemplate jdbc;

    public FeeLineClassifier(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A verdict: the category, how sure, and which rule decided it. */
    public record Verdict(String category, double confidence, String source) { }

    /**
     * The rule book for one tenant and scheme, read once per import rather
     * than per line — a 2,000-line invoice would otherwise be 6,000 queries.
     */
    public record RuleBook(
            Map<String, String> overrides,          // code -> category
            Map<String, Verdict> dictionary,        // code -> verdict
            List<Pattern> patterns) { }

    /** One description pattern. */
    public record Pattern(String matchText, String category, double confidence, int priority) { }

    /** Loads everything needed to classify an invoice. */
    public RuleBook ruleBook(long tenantId, String scheme) {
        Map<String, String> overrides = new java.util.LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT event_code, category FROM sfo_classification_override " +
                "WHERE tenant_id = ? AND scheme = ?", tenantId, scheme)) {
            overrides.put(key(r.get("event_code")), String.valueOf(r.get("category")));
        }

        Map<String, Verdict> dictionary = new java.util.LinkedHashMap<>();
        // The tenant's own row outranks the global one, so global is read
        // first and overwritten rather than the other way round.
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT event_code, category, confidence, tenant_id FROM sfo_event_dictionary " +
                "WHERE scheme = ? AND (tenant_id IS NULL OR tenant_id = ?) " +
                "ORDER BY tenant_id NULLS FIRST", scheme, tenantId)) {
            dictionary.put(key(r.get("event_code")),
                    new Verdict(String.valueOf(r.get("category")),
                            num(r.get("confidence")), "DICTIONARY"));
        }

        List<Pattern> patterns = jdbc.query(
                "SELECT match_text, category, confidence, priority FROM sfo_classification_pattern " +
                "WHERE scheme IS NULL OR scheme = ? ORDER BY priority DESC, confidence DESC",
                (rs, i) -> new Pattern(rs.getString("match_text"), rs.getString("category"),
                        rs.getDouble("confidence"), rs.getInt("priority")),
                scheme);

        return new RuleBook(overrides, dictionary, patterns);
    }

    /** Classifies one line against an already-loaded rule book. */
    public Verdict classify(RuleBook book, String eventCode, String description) {
        String code = key(eventCode);

        if (!code.isEmpty()) {
            String override = book.overrides().get(code);
            if (override != null) return new Verdict(override, 100, "OVERRIDE");

            Verdict known = book.dictionary().get(code);
            if (known != null) return known;
        }

        String haystack = ((description == null ? "" : description) + " "
                        + (eventCode == null ? "" : eventCode)).toLowerCase(Locale.ROOT);
        for (Pattern p : book.patterns()) {
            if (haystack.contains(p.matchText())) {
                return new Verdict(p.category(), p.confidence(), "PATTERN");
            }
        }
        return new Verdict("OTHER", 0, "NONE");
    }

    /**
     * Records a code we had to guess at, so it can be corrected once.
     *
     * Only writes a guess — an override or an existing dictionary entry has
     * already decided the matter, and a row a human has edited is protected by
     * the {@code learned} flag in the WHERE clause.
     */
    public void learn(long tenantId, String scheme, String eventCode,
                      String description, Verdict verdict) {
        if (eventCode == null || eventCode.isBlank()) return;
        if (!"PATTERN".equals(verdict.source()) && !"NONE".equals(verdict.source())) return;

        int updated = jdbc.update(
                "UPDATE sfo_event_dictionary " +
                "   SET category = ?, confidence = ?, description = COALESCE(description, ?), " +
                "       updated_at = CURRENT_TIMESTAMP " +
                " WHERE tenant_id = ? AND scheme = ? AND event_code = ? AND learned = TRUE",
                verdict.category(), verdict.confidence(), description,
                tenantId, scheme, eventCode);

        if (updated == 0) {
            try {
                jdbc.update(
                        "INSERT INTO sfo_event_dictionary " +
                        "(tenant_id, scheme, event_code, description, category, confidence, learned) " +
                        "VALUES (?,?,?,?,?,?,TRUE)",
                        tenantId, scheme, eventCode, description,
                        verdict.category(), verdict.confidence());
            } catch (Exception ignored) {
                // A concurrent import inserted the same code first. The row
                // exists with the same verdict either way, so there is nothing
                // to recover from.
            }
        }
    }

    private static String key(Object v) {
        return v == null ? "" : String.valueOf(v).trim().toUpperCase(Locale.ROOT);
    }

    private static double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : 0;
    }
}
