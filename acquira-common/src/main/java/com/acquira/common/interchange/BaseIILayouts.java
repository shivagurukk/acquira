package com.acquira.common.interchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the Visa BASE II record-layout + edit-criteria bundle
 * (resources/interchange/baseii-layouts.json) once at startup and exposes the
 * field maps the parser and validator consume.
 *
 * The bundle is derived verbatim from the Visa "BASE II Clearing Interchange
 * Formats" manuals (TC 01-49 and TC 50-92, effective 18 April 2026). Positions
 * are 1-based inclusive. This class holds NO business logic — it is a typed view
 * over the JSON so the rules live in data, not code (same approach as the
 * Scheme Billing Reference bundle).
 */
@Component
public class BaseIILayouts {

    private static final Logger log = LoggerFactory.getLogger(BaseIILayouts.class);
    private static final String RESOURCE = "interchange/baseii-layouts.json";

    /** One field slot in a record layout. */
    public static class Field {
        public String name;
        public int start;          // 1-based inclusive
        public int end;            // 1-based inclusive
        public String fmt;         // AN | ANS | DX | N | UN
        public String req;         // M | C | O
        public boolean reserved;   // must be zero (numeric) / space (alphanumeric)
        public String enumRef;     // key into enums map, or null
        public String check;       // mod10_16 | mmdd | yddd | yyddd | ccy | mcc | not_all_zero_space
        public String key;         // ARN | FPI ...
        public String role;        // trailer control-total role
        public String constVal;    // required constant value
        public Integer decimals;   // implied decimal places for amounts
        public String when;        // human note for conditional requiredness

        public int len() { return end - start + 1; }
    }

    /** A full record layout (one TCR variant). */
    public static class RecordLayout {
        public String key;
        public String desc;
        public String tcr;
        public List<String> appliesToTc = new ArrayList<>();
        public List<Field> fields = new ArrayList<>();
    }

    private final Map<String, String> transactionCodes = new HashMap<>();
    private final List<String> draftDataCodes = new ArrayList<>();
    private final Map<String, RecordLayout> records = new HashMap<>();
    private final Map<String, Map<String, String>> enums = new HashMap<>();
    private int ctfBytes = 168;
    private int itfBytes = 170;

    public BaseIILayouts() {
        load();
    }

    private void load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);

            JsonNode rb = root.path("recordBytes");
            ctfBytes = rb.path("CTF").asInt(168);
            itfBytes = rb.path("ITF").asInt(170);

            root.path("transactionCodes").fields().forEachRemaining(
                    e -> transactionCodes.put(e.getKey(), e.getValue().asText()));
            root.path("draftDataCodes").forEach(n -> draftDataCodes.add(n.asText()));

            root.path("enums").fields().forEachRemaining(e -> {
                Map<String, String> m = new HashMap<>();
                e.getValue().fields().forEachRemaining(x -> m.put(x.getKey(), x.getValue().asText()));
                enums.put(e.getKey(), m);
            });

            root.path("records").fields().forEachRemaining(e -> {
                RecordLayout rl = new RecordLayout();
                rl.key = e.getKey();
                JsonNode rn = e.getValue();
                rl.desc = rn.path("desc").asText("");
                rl.tcr = rn.path("tcr").asText("");
                rn.path("appliesToTc").forEach(n -> rl.appliesToTc.add(n.asText()));
                for (JsonNode fn : rn.path("fields")) {
                    Field f = new Field();
                    f.name = fn.path("name").asText();
                    f.start = fn.path("start").asInt();
                    f.end = fn.path("end").asInt();
                    f.fmt = fn.path("fmt").asText("AN");
                    f.req = fn.path("req").asText("O");
                    f.reserved = fn.path("reserved").asBoolean(false);
                    f.enumRef = fn.hasNonNull("enum") ? fn.get("enum").asText() : null;
                    f.check = fn.hasNonNull("check") ? fn.get("check").asText() : null;
                    f.key = fn.hasNonNull("key") ? fn.get("key").asText() : null;
                    f.role = fn.hasNonNull("role") ? fn.get("role").asText() : null;
                    f.constVal = fn.hasNonNull("const") ? fn.get("const").asText() : null;
                    f.decimals = fn.hasNonNull("decimals") ? fn.get("decimals").asInt() : null;
                    f.when = fn.hasNonNull("when") ? fn.get("when").asText() : null;
                    rl.fields.add(f);
                }
                records.put(rl.key, rl);
            });

            log.info("BaseIILayouts loaded: {} records, {} transaction codes",
                    records.size(), transactionCodes.size());
        } catch (Exception ex) {
            // Fail loud at startup — the validator is useless without its rules.
            throw new IllegalStateException("Failed to load " + RESOURCE, ex);
        }
    }

    // ── Lookups ──────────────────────────────────────────────────────────────

    public int ctfBytes() { return ctfBytes; }
    public int itfBytes() { return itfBytes; }

    public boolean isDraftData(String tc) { return draftDataCodes.contains(tc); }

    public String describeTc(String tc) { return transactionCodes.getOrDefault(tc, "Unknown TC " + tc); }

    public Map<String, String> enumValues(String ref) { return enums.get(ref); }

    public RecordLayout record(String key) { return records.get(key); }

    /**
     * Pick the layout for a physical record given its TC, TCR sequence and the
     * file direction (OUTGOING/INCOMING, which only matters for the TC 90 header).
     * Returns null when no layout is mapped yet (e.g. TCR 3/4/5/6/7/D/E — those
     * records are still framed and counted, just not field-validated in v1).
     */
    public RecordLayout layoutFor(String tc, String tcr, String direction) {
        if ("91".equals(tc) || "92".equals(tc)) return records.get("TRAILER/0");
        if ("90".equals(tc)) {
            return "INCOMING".equalsIgnoreCase(direction)
                    ? records.get("90/0/INCOMING")
                    : records.get("90/0/OUTGOING");
        }
        if (isDraftData(tc)) {
            if ("0".equals(tcr)) return records.get("DRAFT/0");
            if ("1".equals(tcr)) return records.get("DRAFT/1");
            return null; // other draft TCRs not yet field-mapped
        }
        return null;
    }
}
