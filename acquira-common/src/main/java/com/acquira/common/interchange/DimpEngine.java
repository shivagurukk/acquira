package com.acquira.common.interchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;

/**
 * Runs the 24 Mastercard DIMP "Acquirer Clearing 1240" edits against parsed IPM
 * presentments — every edit, from the clearing file alone.
 *
 * Each edit inspects a clearing-side field (a DE, a DE 43 detail, or a PDS
 * subelement) and is one of:
 *   FILE    — fully evaluated from the clearing message.
 *   PARTIAL — clearing-side presence/format checked; DE 43 subfield or PDS
 *             detail simplified.
 *   AUTH    — a MATCH edit: the authorization comparison is not performed (no
 *             auth feed), so we validate and surface the CLEARING value instead.
 *
 * A violation (category DIMP, WARN) is raised when the clearing-side value is
 * missing or malformed; the observed value is carried in the violation so the
 * screen shows "what is there". DE 43 subfield depth is intentionally left light.
 */
@Component
public class DimpEngine {

    private static final Logger log = LoggerFactory.getLogger(DimpEngine.class);
    private static final String RESOURCE = "interchange/dimp-edits.json";

    /** Placeholder high-risk MCC set (Edit 21) until the parameter-table list is wired. */
    private static final Set<String> HIGH_RISK_MCC = Set.of(
            "5967", "5966", "5962", "7995", "5993", "6051", "4829", "5122", "5912", "7273");

    /** editNumber -> {clearing field label, status, check type}. */
    private record Impl(String field, DimpEdit.Status status, String check) {}
    private static final Map<String, Impl> IMPL = new LinkedHashMap<>();
    static {
        IMPL.put("2",  new Impl("DE 42 (Acceptor ID)",              DimpEdit.Status.FILE,    "present"));
        IMPL.put("3",  new Impl("DE 43 (Acceptor Name/Location)",   DimpEdit.Status.FILE,    "de43"));
        IMPL.put("4",  new Impl("DE 43 country / postal",           DimpEdit.Status.PARTIAL, "present"));
        IMPL.put("5",  new Impl("DE 43 street address",             DimpEdit.Status.PARTIAL, "present"));
        IMPL.put("6",  new Impl("DE 43 city",                       DimpEdit.Status.PARTIAL, "present"));
        IMPL.put("7",  new Impl("DE 22 (POS Entry Mode)",           DimpEdit.Status.FILE,    "present"));
        IMPL.put("8",  new Impl("DE 63 Trace ID",                   DimpEdit.Status.AUTH,    "present"));
        IMPL.put("9",  new Impl("DE 43 postal code",                DimpEdit.Status.AUTH,    "present"));
        IMPL.put("10", new Impl("DE 22 (POS Entry Mode)",           DimpEdit.Status.AUTH,    "present"));
        IMPL.put("11", new Impl("PDS 0208 (Payment Facilitator)",   DimpEdit.Status.FILE,    "pf"));
        IMPL.put("13", new Impl("DE 26 (MCC)",                      DimpEdit.Status.AUTH,    "mcc"));
        IMPL.put("14", new Impl("DE 43 DBA name",                   DimpEdit.Status.AUTH,    "present"));
        IMPL.put("16", new Impl("DE 22 terminal capability",        DimpEdit.Status.AUTH,    "present"));
        IMPL.put("17", new Impl("PDS 0185 (AAV)",                   DimpEdit.Status.FILE,    "aav"));
        IMPL.put("18", new Impl("3DS Directory Server Trans ID",    DimpEdit.Status.PARTIAL, "info"));
        IMPL.put("20", new Impl("DE 63 Trace ID + DE 2",            DimpEdit.Status.AUTH,    "present"));
        IMPL.put("21", new Impl("DE 26 (MCC), high-risk",           DimpEdit.Status.AUTH,    "highrisk"));
        IMPL.put("22", new Impl("DE 49 currency (DCC)",             DimpEdit.Status.PARTIAL, "info"));
        IMPL.put("24", new Impl("DE 43 country / gov PDS",          DimpEdit.Status.PARTIAL, "info"));
        IMPL.put("25", new Impl("DE 63 Trace ID (return)",          DimpEdit.Status.AUTH,    "present"));
        IMPL.put("26", new Impl("PDS acceptor contact",             DimpEdit.Status.PARTIAL, "info"));
        IMPL.put("27", new Impl("Transaction Link ID (TLID)",       DimpEdit.Status.AUTH,    "info"));
        IMPL.put("28", new Impl("PDS 0027 (Flex Code, Brazil)",     DimpEdit.Status.FILE,    "flex"));
        IMPL.put("29", new Impl("DE 22 mPOS device",               DimpEdit.Status.AUTH,    "present"));
    }

    private final Map<String, DimpEdit> byNumber = new LinkedHashMap<>();

    public DimpEngine() { load(); }

    private void load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);
            for (JsonNode e : root.path("edits")) {
                DimpEdit d = new DimpEdit();
                d.editNumber = e.path("editNumber").asText(null);
                d.name = e.path("name").asText(null);
                d.title = e.path("title").asText(null);
                d.billingCode = e.path("billingCode").asText(null);
                d.description = e.path("description").asText(null);
                if (d.editNumber == null) continue;
                Impl impl = IMPL.get(d.editNumber);
                if (impl != null) { d.status = impl.status; d.field = impl.field; }
                byNumber.put(d.editNumber, d);
            }
            log.info("DimpEngine loaded {} edits ({} FILE, {} PARTIAL, {} AUTH)", byNumber.size(),
                    countStatus(DimpEdit.Status.FILE), countStatus(DimpEdit.Status.PARTIAL),
                    countStatus(DimpEdit.Status.AUTH));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load " + RESOURCE, ex);
        }
    }

    private long countStatus(DimpEdit.Status s) {
        return byNumber.values().stream().filter(e -> e.status == s).count();
    }

    /** The full catalogue (for the dashboard / reference view). */
    public Collection<DimpEdit> catalogue() { return byNumber.values(); }

    /** Run every edit's clearing-side check against one 1240 presentment. */
    public List<Violation> evaluate(IpmMessage m) {
        List<Violation> out = new ArrayList<>();
        if (!"1240".equals(m.mti) || !m.fullyDecoded) return out;

        Map<String, String> pds = PdsParser.parse(m.get(48));
        String de63 = m.get(63);

        for (Map.Entry<String, Impl> en : IMPL.entrySet()) {
            String num = en.getKey();
            Impl impl = en.getValue();
            String value = extract(impl.field, m, pds, de63);
            String reason;
            switch (impl.check) {
                case "flex": {   // Edit 28 — Brazil acceptor must populate PDS 0027 (Flex Code)
                    value = pds.get("0027");
                    reason = "BRA".equalsIgnoreCase(acceptorCountry(m.get(43))) && blank(value)
                            ? "Brazil transaction is missing PDS 0027 (Flex Code)" : null;
                    break;
                }
                case "pf": {     // Edit 11 — if PDS 0208 (Payment Facilitator) present it must carry data
                    value = pds.get("0208");
                    reason = value != null && value.trim().isEmpty()
                            ? "PDS 0208 (Payment Facilitator / Seller data) is present but empty" : null;
                    break;
                }
                default:
                    reason = check(impl.check, value);
            }
            if (reason != null) add(out, m, num, reason, value);
        }
        return out;
    }

    private boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    /** Acceptor country = the trailing 3-letter token of DE 43 (subfield 6). */
    private String acceptorCountry(String de43) {
        if (de43 == null) return null;
        String t = de43.trim();
        if (t.length() < 3) return null;
        String tail = t.substring(t.length() - 3);
        return tail.chars().allMatch(Character::isLetter) ? tail : null;
    }

    // ── value extraction ────────────────────────────────────────────────────

    private String extract(String fieldLabel, IpmMessage m, Map<String, String> pds, String de63) {
        // Map the human field label to the underlying clearing value.
        if (fieldLabel.startsWith("DE 42")) return m.get(42);
        if (fieldLabel.startsWith("DE 43")) return m.get(43);
        if (fieldLabel.startsWith("DE 22")) return m.get(22);
        if (fieldLabel.startsWith("DE 26")) return m.get(26);
        if (fieldLabel.startsWith("DE 49")) return m.get(49);
        if (fieldLabel.startsWith("DE 63")) return de63;
        if (fieldLabel.startsWith("PDS 0208")) return pds.get("0208");
        if (fieldLabel.startsWith("PDS 0185")) return pds.get("0185");
        if (fieldLabel.startsWith("PDS 0027")) return pds.get("0027");
        return null;
    }

    // ── checks ──────────────────────────────────────────────────────────────

    private String check(String check, String value) {
        String v = value == null ? "" : value.trim();
        switch (check) {
            case "present":
                return (v.isEmpty() || v.chars().allMatch(c -> c == '0'))
                        ? "clearing value is missing or all zeros" : null;
            case "de43":
                return v.length() < 20 ? "DE 43 is missing or shorter than the 20-char minimum" : null;
            case "mcc":
                return v.matches("\\d{4}") ? null : "MCC is missing or not 4 digits";
            case "aav":
                // PDS 0185 AAV, if present, must not begin with j, k, or h (per Edit 17).
                if (v.isEmpty()) return null;
                char c0 = Character.toLowerCase(v.charAt(0));
                return (c0 == 'j' || c0 == 'k' || c0 == 'h') ? "AAV begins with an invalid indicator '" + v.charAt(0) + "'" : null;
            case "highrisk":
                return HIGH_RISK_MCC.contains(v) ? "MCC " + v + " is on the high-risk list — verify acceptor data" : null;
            case "info":
            default:
                return null; // value shown on the dashboard; no pass/fail from the clearing file alone
        }
    }

    private void add(List<Violation> out, IpmMessage m, String editNo, String reason, String value) {
        DimpEdit e = byNumber.get(editNo);
        String label = e != null ? e.label() : ("Edit " + editNo);
        String rule = "DIMP_E" + editNo + (e != null && e.name != null
                ? "_" + e.name.replaceAll("[^A-Za-z0-9]+", "_") : "");
        Violation v = Violation.of(Violation.Category.DIMP, Violation.Severity.WARN,
                        trim(rule, 48), label + ": " + reason)
                .record(m.recordNo, m.mti, null).txn(m.recordNo)
                .values(null, value == null ? null : trim(value.trim(), 64));
        if (e != null) v.field(e.field != null ? e.field : e.name, 0, 0, null);
        out.add(v);
    }

    private String trim(String s, int n) { return s == null ? null : (s.length() <= n ? s : s.substring(0, n)); }
}
