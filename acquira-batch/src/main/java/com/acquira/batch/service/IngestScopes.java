package com.acquira.batch.service;

/**
 * Canonical date-scope SQL builders for the ingest/rebuild pipelines.
 *
 * These used to be private statics on TransactionJobConfig; they moved here so
 * BulkMigrationService and BackfillIngestionService build their fact/summary
 * scopes with the SAME strings the upload job uses (summary-rebuild-drift
 * rule: the two rebuild paths silently regressed whenever their hand-rolled
 * scopes diverged from the job's — most recently by staying non-sargable, so
 * every rebuild scanned all partitions).
 *
 * Contract shared by every consumer:
 *   - dateInList(dates)      -> "(DATE 'yyyy-mm-dd', ...)" — the exact-day
 *     filter, safe to inline (each date is regex-checked ISO).
 *   - rangeClause(dates, a)  -> " a.payment_date >= DATE 'first' AND
 *     a.payment_date < DATE 'last' + INTERVAL '1 day' AND " — the SARGABLE
 *     companion that gives the planner partition pruning + index ranges.
 *     Always splice it IN FRONT of the DATE(...) IN list, never instead of it
 *     (the range is a superset when the date list has gaps).
 */
public final class IngestScopes {

    private IngestScopes() {}

    // Compiled once at class-load time, not per call.
    private static final java.util.regex.Pattern ISO_DATE_PATTERN =
        java.util.regex.Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    public static String dateInList(java.util.List<java.sql.Date> dates) {
        if (dates == null || dates.isEmpty()) return "(NULL)";
        StringBuilder sb = new StringBuilder("(");
        boolean first = true;
        for (java.sql.Date d : dates) {
            if (d == null) continue;
            String s = d.toString();
            if (!ISO_DATE_PATTERN.matcher(s).matches()) {
                throw new IllegalStateException("Refusing to inline non-ISO date: '" + s + "'");
            }
            if (!first) sb.append(',');
            sb.append("DATE '").append(s).append("'");
            first = false;
        }
        sb.append(")");
        return sb.toString();
    }

    public static String rangeClause(java.util.List<java.sql.Date> sortedDates, String alias) {
        String first = sortedDates.get(0).toString();
        String last = sortedDates.get(sortedDates.size() - 1).toString();
        String col = alias + "payment_date";
        return " " + col + " >= DATE '" + first + "' AND "
             + col + " < DATE '" + last + "' + INTERVAL '1 day' AND ";
    }

    /** Every day of [from..to] inclusive, as the java.sql.Date list the scope builders take. */
    public static java.util.List<java.sql.Date> daysBetween(java.time.LocalDate from, java.time.LocalDate to) {
        java.util.List<java.sql.Date> out = new java.util.ArrayList<>();
        for (java.time.LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            out.add(java.sql.Date.valueOf(d));
        }
        return out;
    }
}
