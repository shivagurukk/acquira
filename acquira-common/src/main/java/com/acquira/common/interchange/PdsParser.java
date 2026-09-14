package com.acquira.common.interchange;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tokenises a Mastercard PDS-bearing data element (DE 48, DE 62, DE 123…) into
 * its Private Data Subelements. Each PDS is [4-digit tag][3-digit length][data],
 * concatenated — validated against a live IPM file.
 *
 * The input is the already-EBCDIC-decoded DE string (1 byte → 1 char), so string
 * offsets equal byte offsets and the length walk stays aligned.
 */
public final class PdsParser {
    private PdsParser() {}

    /** Parse a DE value into an ordered map of PDS tag → value. Lenient: stops on a bad length. */
    public static Map<String, String> parse(String deValue) {
        Map<String, String> out = new LinkedHashMap<>();
        if (deValue == null) return out;
        int p = 0, n = deValue.length();
        while (p + 7 <= n) {
            String tag = deValue.substring(p, p + 4);
            String lenStr = deValue.substring(p + 4, p + 7);
            if (!tag.chars().allMatch(Character::isDigit) || !lenStr.chars().allMatch(Character::isDigit)) break;
            int len = Integer.parseInt(lenStr);
            int start = p + 7;
            if (start + len > n) break;
            out.put(tag, deValue.substring(start, start + len));
            p = start + len;
        }
        return out;
    }
}
