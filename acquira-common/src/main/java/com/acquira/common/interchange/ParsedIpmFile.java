package com.acquira.common.interchange;

import java.util.ArrayList;
import java.util.List;

/** Result of parsing one Mastercard IPM clearing file. */
public class ParsedIpmFile {
    public String fileName;
    public String direction;            // OUTGOING | INCOMING
    public boolean blocked;             // 1014-byte blocked wrapper stripped
    public int recordCount;             // total IPM messages
    public final List<IpmMessage> messages = new ArrayList<>();

    public IpmMessage header;           // first 1644 (File Header)
    public IpmMessage trailer;          // last 1644 (File Trailer)

    /** Financial presentment messages (MTI 1240). */
    public List<IpmMessage> presentments() {
        List<IpmMessage> out = new ArrayList<>();
        for (IpmMessage m : messages) if ("1240".equals(m.mti)) out.add(m);
        return out;
    }
}
