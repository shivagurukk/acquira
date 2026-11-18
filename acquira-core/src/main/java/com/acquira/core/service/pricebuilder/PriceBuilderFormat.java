package com.acquira.core.service.pricebuilder;

import java.util.List;
import java.util.Map;

/**
 * Formatting and unwrapping shared by the two quote renderers.
 *
 * It exists because the workbook and the term sheet cannot live in one class:
 * Apache POI and OpenPDF both export types called {@code Font}, {@code Row},
 * {@code Cell} and {@code Rectangle}, so a file that imports both will not
 * compile. Splitting them leaves this, the part that is about neither library.
 */
final class PriceBuilderFormat {

    private PriceBuilderFormat() { }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Object v) {
        return v instanceof List ? (List<Map<String, Object>>) v : List.of();
    }

    static String str(Object v, String dflt) {
        return v == null ? dflt : String.valueOf(v);
    }

    static double num(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return 0.0;
        try { return Double.parseDouble(String.valueOf(v)); }
        catch (NumberFormatException e) { return 0.0; }
    }

    static String money(double v) { return String.format("%,.2f", v); }
    static String count(double v) { return String.format("%,.0f", v); }
    static String fmt(double v)   { return String.format("%.4f", v); }
    static String rate(Object v)  { return String.format("%.4f%%", num(v)); }

    /** Channel label as the reader expects to see it on a document. */
    static String channelLabel(String channel) {
        return "ECOM".equals(channel) ? "E-commerce" : "POS";
    }
}
