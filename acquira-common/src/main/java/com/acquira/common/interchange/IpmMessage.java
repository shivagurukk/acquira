package com.acquira.common.interchange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One decoded IPM message (one clearing record): MTI + the data elements present. */
public class IpmMessage {
    public long recordNo;
    public String mti;                                   // e.g. 1240, 1644
    public final Map<Integer, String> de = new LinkedHashMap<>();   // DE number -> decoded value
    public boolean fullyDecoded = true;                  // false if the DE walk misaligned
    public final List<String> notes = new ArrayList<>(); // parse notes / partial-decode reasons

    public String get(int n) { return de.get(n); }
    public boolean has(int n) { return de.containsKey(n); }

    /** DEs present, comma-joined, for display (e.g. "2,3,4,12,26,31,43"). */
    public String deList() {
        StringBuilder sb = new StringBuilder();
        for (Integer k : de.keySet()) { if (sb.length() > 0) sb.append(','); sb.append(k); }
        return sb.toString();
    }
}
