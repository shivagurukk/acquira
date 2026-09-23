package com.acquira.common.interchange;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads the Mastercard IPM data-element + PDS dictionary
 * (resources/interchange/ipm-layouts.json, extracted from the Dual Message
 * Clearing System Guide) and exposes each DE's decoding spec: fixed vs
 * variable (LLVAR/LLLVAR), length, and format. Rules live in data, mirroring
 * {@link BaseIILayouts} for Visa.
 */
@Component
public class IpmLayouts {

    private static final Logger log = LoggerFactory.getLogger(IpmLayouts.class);
    private static final String RESOURCE = "interchange/ipm-layouts.json";

    /** How one data element is laid out on the wire. */
    public static class DeSpec {
        public int de;
        public String name;
        public String format;        // n | a | an | ans | b
        public boolean variable;     // LLVAR/LLLVAR
        public int prefixDigits;     // 2 for LLVAR, 3 for LLLVAR (0 if fixed)
        public int fixedLen;         // byte length when fixed
        public String representation;
    }

    private final Map<Integer, DeSpec> des = new HashMap<>();
    private final Map<String, String> pdsNames = new HashMap<>();

    public IpmLayouts() { load(); }

    private void load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);
            root.path("dataElements").fields().forEachRemaining(e -> {
                JsonNode n = e.getValue();
                DeSpec s = new DeSpec();
                s.de = n.path("de").asInt(Integer.parseInt(e.getKey()));
                s.name = n.path("name").asText("");
                s.format = n.path("format").asText("");
                s.representation = n.path("dataRepresentation").asText("");
                // A DE is length-prefixed whenever "Data length representation" is
                // "N positions" — this is authoritative even when the data
                // representation says "Fixed" (e.g. DE 31: Fixed n-23 but 2-position
                // prefix). Falls back to the LLVAR/LLLVAR word if that field is absent.
                int prefix = positionsPrefix(n.path("lengthRepresentation").asText(""));
                if (prefix == 0) {
                    String repr = s.representation.toUpperCase();
                    if (repr.contains("LLLVAR")) prefix = 3;
                    else if (repr.contains("LLVAR")) prefix = 2;
                }
                if (prefix > 0) {
                    s.variable = true;
                    s.prefixDigits = prefix;
                } else {
                    s.variable = false;
                    int len = n.path("maxLen").asInt(n.path("minLen").asInt(0));
                    // Binary lengths are quoted in bits when a multiple of 8.
                    if ("b".equalsIgnoreCase(s.format) && len % 8 == 0 && len >= 8) len = len / 8;
                    s.fixedLen = len;
                }
                des.put(s.de, s);
            });
            root.path("pds").fields().forEachRemaining(e ->
                    pdsNames.put(e.getKey(), e.getValue().path("name").asText("")));
            log.info("IpmLayouts loaded: {} data elements, {} PDS", des.size(), pdsNames.size());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load " + RESOURCE, ex);
        }
    }

    /** Parse a "Data length representation" like "2 positions" to its digit count; 0 if none. */
    private int positionsPrefix(String lenRep) {
        if (lenRep == null) return 0;
        String s = lenRep.toLowerCase();
        if (!s.contains("position")) return 0;
        int i = 0;
        while (i < s.length() && !Character.isDigit(s.charAt(i))) i++;
        int j = i;
        while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
        return j > i ? Integer.parseInt(s.substring(i, j)) : 0;
    }

    public DeSpec de(int de) { return des.get(de); }
    public boolean known(int de) { return des.containsKey(de); }
    public String deName(int de) { DeSpec s = des.get(de); return s == null ? ("DE " + de) : s.name; }
    public String pdsName(String pds) { return pdsNames.getOrDefault(pds, "PDS " + pds); }
    public int deCount() { return des.size(); }
}
