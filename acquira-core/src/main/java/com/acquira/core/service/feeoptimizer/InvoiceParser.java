package com.acquira.core.service.feeoptimizer;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a scheme billing invoice — Excel or CSV — into fee lines, and reports
 * what it believes about the file.
 *
 * <h2>Why it detects rather than assumes a layout</h2>
 * Visa and Mastercard billing extracts differ by scheme, by region and by the
 * processor that produced them, and the same acquirer receives several shapes.
 * A parser pinned to one column order would be wrong on the second file it
 * ever saw. So this reads the grid, finds the header row by scoring every row
 * against a dictionary of column aliases, and maps the columns it recognises.
 *
 * <h2>Why nothing is imported on detection</h2>
 * Everything here produces a {@link Detection} and a list of staged lines —
 * it writes nothing. The caller shows the detection, the operator agrees or
 * corrects it, and only then are the lines committed. A fee invoice that
 * imports itself under a wrong period or currency is worse than one that did
 * not import at all, because the error then propagates into every trend and
 * every finding downstream.
 *
 * <h2>Confidence</h2>
 * Each detected field carries a confidence and the evidence behind it, so the
 * review step can show WHY it thinks the period is September rather than
 * simply asserting it.
 */
@Service
public class InvoiceParser {

    /** The most rows we will read from one file. */
    private static final int MAX_ROWS = 200_000;

    /** How far down the sheet the header can hide behind a letterhead. */
    private static final int HEADER_SEARCH_ROWS = 40;

    // ── Column aliases ─────────────────────────────────────────────────────
    // Ordered by how strongly each phrase implies the field. "amount" is a
    // weaker signal than "billing amount" because a quantity column is
    // sometimes headed "amount of items".
    private static final Map<String, List<String>> ALIASES = new LinkedHashMap<>();
    static {
        ALIASES.put("eventCode", List.of(
                "billing event", "event code", "fee code", "charge code", "service code",
                "event id", "event", "code"));
        ALIASES.put("description", List.of(
                "event description", "fee description", "billing description", "description",
                "narrative", "details", "service"));
        ALIASES.put("quantity", List.of(
                "quantity", "qty", "units", "item count", "no of items", "count"));
        ALIASES.put("unitRate", List.of(
                "unit rate", "unit price", "fee rate", "rate per", "tariff", "rate", "price"));
        ALIASES.put("amount", List.of(
                "billing amount", "fee amount", "net amount", "total amount", "charge amount",
                "amount", "charge", "value", "total"));
        ALIASES.put("currency", List.of("currency", "ccy", "curr"));
        ALIASES.put("entity", List.of(
                "business id", "member id", "entity", "ica", "bid", "member", "institution"));
        ALIASES.put("period", List.of("billing period", "period", "cycle", "month"));
    }

    private static final Pattern YEAR_MONTH = Pattern.compile(
            "(20\\d{2})[-_/ ]?(0[1-9]|1[0-2])(?![0-9])");
    private static final Pattern MONTH_YEAR = Pattern.compile(
            "(?i)(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[-_/ ,]*(20\\d{2})");
    private static final Pattern CURRENCY_CODE = Pattern.compile("\\b([A-Z]{3})\\b");

    // ── Result types ───────────────────────────────────────────────────────

    /** One detected field: the value, how sure we are, and why. */
    public record Field(Object value, int confidence, String evidence) {
        public static Field none(String why) { return new Field(null, 0, why); }
    }

    /** A line as read, before classification and before commit. */
    public record StagedLine(
            int sourceRow, String eventCode, String description,
            BigDecimal quantity, BigDecimal unitRate, BigDecimal amount) { }

    /** What the parser believes about a file, plus the lines it read. */
    public record Detection(
            Map<String, Field> fields,
            Map<String, Integer> columns,
            int headerRow,
            List<StagedLine> lines,
            BigDecimal parsedTotal,
            List<String> warnings) {

        public String scheme()      { return strOf("scheme"); }
        public String entityCode()  { return strOf("entityCode"); }
        public String currency()    { return strOf("currency"); }
        public String invoiceRef()  { return strOf("invoiceRef"); }
        public Integer year()       { return intOf("periodYear"); }
        public Integer month()      { return intOf("periodMonth"); }
        public BigDecimal invoiceTotal() {
            Field f = fields.get("invoiceTotal");
            return f == null || f.value() == null ? null : new BigDecimal(String.valueOf(f.value()));
        }

        private String strOf(String k) {
            Field f = fields.get(k);
            return f == null || f.value() == null ? null : String.valueOf(f.value());
        }

        private Integer intOf(String k) {
            Field f = fields.get(k);
            if (f == null || f.value() == null) return null;
            try { return Integer.valueOf(String.valueOf(f.value())); }
            catch (NumberFormatException e) { return null; }
        }
    }

    // ── Entry point ────────────────────────────────────────────────────────

    /**
     * Reads the file. Never throws for a file it merely does not understand —
     * it comes back with warnings and whatever it managed, so the review step
     * can show the operator the problem instead of a stack trace.
     */
    public Detection parse(String fileName, byte[] content) {
        List<String[]> grid;
        List<String> warnings = new ArrayList<>();

        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        try {
            grid = lower.endsWith(".csv") || lower.endsWith(".txt")
                    ? readDelimited(content)
                    : readWorkbook(content);
        } catch (Exception e) {
            return new Detection(Map.of("error", Field.none("Could not read the file: " + e.getMessage())),
                    Map.of(), -1, List.of(), BigDecimal.ZERO,
                    List.of("Could not read the file: " + e.getMessage()));
        }

        if (grid.isEmpty())
            return new Detection(Map.of(), Map.of(), -1, List.of(), BigDecimal.ZERO,
                    List.of("The file has no readable rows."));

        int headerRow = findHeaderRow(grid);
        Map<String, Integer> columns = headerRow < 0 ? Map.of() : mapColumns(grid.get(headerRow));

        if (headerRow < 0) warnings.add("No header row could be identified.");
        if (!columns.containsKey("amount"))
            warnings.add("No amount column was found — without it nothing can be billed or reconciled.");
        if (!columns.containsKey("eventCode") && !columns.containsKey("description"))
            warnings.add("Neither a billing event code nor a description column was found, "
                       + "so lines cannot be classified.");

        List<StagedLine> lines = headerRow < 0 || !columns.containsKey("amount")
                ? List.of()
                : readLines(grid, headerRow, columns);

        BigDecimal parsedTotal = lines.stream()
                .map(StagedLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_UP);

        Map<String, Field> fields = new LinkedHashMap<>();
        fields.put("scheme",       detectScheme(fileName, grid));
        fields.put("currency",     detectCurrency(grid, headerRow, columns));
        fields.put("invoiceRef",   detectLabelled(grid, headerRow,
                List.of("invoice number", "invoice no", "invoice ref", "invoice id", "invoice")));
        fields.put("entityCode",   detectEntity(grid, headerRow, columns));
        fields.put("invoiceTotal", detectInvoiceTotal(grid));

        Field[] period = detectPeriod(fileName, grid, headerRow, columns);
        fields.put("periodYear",  period[0]);
        fields.put("periodMonth", period[1]);

        // Reconciliation is the check that decides whether this file can be
        // trusted at all: if the lines we read do not add up to the total the
        // invoice prints, we have mis-read something.
        BigDecimal printed = fields.get("invoiceTotal").value() == null ? null
                : new BigDecimal(String.valueOf(fields.get("invoiceTotal").value()));
        if (printed != null) {
            BigDecimal diff = printed.subtract(parsedTotal).abs();
            if (diff.compareTo(new BigDecimal("0.01")) > 0)
                warnings.add(String.format(
                        "The lines read total %s but the invoice states %s — a difference of %s. "
                        + "Something has been mis-read; do not import until this is explained.",
                        parsedTotal.toPlainString(), printed.toPlainString(), diff.toPlainString()));
        } else {
            warnings.add("No invoice total was found on the file, so the lines could not be "
                       + "reconciled against it.");
        }

        if (lines.isEmpty()) warnings.add("No fee lines were read.");

        return new Detection(fields, columns, headerRow, lines, parsedTotal, warnings);
    }

    // ── Reading the grid ───────────────────────────────────────────────────

    private List<String[]> readWorkbook(byte[] content) throws Exception {
        List<String[]> out = new ArrayList<>();
        DataFormatter fmt = new DataFormatter();
        try (InputStream in = new ByteArrayInputStream(content);
             Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getNumberOfSheets() == 0 ? null : wb.getSheetAt(0);
            if (sheet == null) return out;
            for (Row row : sheet) {
                if (out.size() >= MAX_ROWS) break;
                int last = row.getLastCellNum();
                if (last <= 0) { out.add(new String[0]); continue; }
                String[] cells = new String[last];
                for (int c = 0; c < last; c++) {
                    Cell cell = row.getCell(c);
                    if (cell == null) { cells[c] = ""; continue; }
                    // A date cell has to come back as an ISO date, not as the
                    // serial number a raw numeric read would give.
                    if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                        cells[c] = cell.getLocalDateTimeCellValue().toLocalDate().toString();
                    } else {
                        cells[c] = fmt.formatCellValue(cell).trim();
                    }
                }
                out.add(cells);
            }
        }
        return out;
    }

    /** CSV or tab-delimited, with quoted fields and embedded separators. */
    private List<String[]> readDelimited(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);

        String[] rawLines = text.split("\\r?\\n");
        char sep = guessSeparator(rawLines);

        List<String[]> out = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        List<String> row = new ArrayList<>();
        boolean quoted = false;

        for (String line : rawLines) {
            if (out.size() >= MAX_ROWS) break;
            for (int i = 0; i < line.length(); i++) {
                char ch = line.charAt(i);
                if (quoted) {
                    if (ch == '"') {
                        if (i + 1 < line.length() && line.charAt(i + 1) == '"') { field.append('"'); i++; }
                        else quoted = false;
                    } else field.append(ch);
                } else if (ch == '"') {
                    quoted = true;
                } else if (ch == sep) {
                    row.add(field.toString().trim());
                    field.setLength(0);
                } else {
                    field.append(ch);
                }
            }
            if (quoted) {
                // A newline inside a quoted field: keep going on the next line.
                field.append('\n');
                continue;
            }
            row.add(field.toString().trim());
            field.setLength(0);
            out.add(row.toArray(new String[0]));
            row = new ArrayList<>();
        }
        return out;
    }

    private static char guessSeparator(String[] lines) {
        int comma = 0, tab = 0, semi = 0, pipe = 0;
        for (int i = 0; i < Math.min(lines.length, 20); i++) {
            for (char ch : lines[i].toCharArray()) {
                if (ch == ',') comma++;
                else if (ch == '\t') tab++;
                else if (ch == ';') semi++;
                else if (ch == '|') pipe++;
            }
        }
        int best = Math.max(Math.max(comma, tab), Math.max(semi, pipe));
        if (best == 0) return ',';
        if (best == tab) return '\t';
        if (best == semi) return ';';
        if (best == pipe) return '|';
        return ',';
    }

    // ── Header and columns ─────────────────────────────────────────────────

    /**
     * The header is the row that reads most like column names.
     *
     * Scored rather than assumed to be row 0, because these files routinely
     * open with a letterhead, an address block and a summary before the table
     * starts.
     */
    private int findHeaderRow(List<String[]> grid) {
        int bestRow = -1, bestScore = 0;
        int limit = Math.min(grid.size(), HEADER_SEARCH_ROWS);
        for (int r = 0; r < limit; r++) {
            Map<String, Integer> mapped = mapColumns(grid.get(r));
            int score = mapped.size();
            // An amount column is what makes a row a billing table rather than
            // a summary block, so it counts double.
            if (mapped.containsKey("amount")) score += 2;
            if (mapped.containsKey("eventCode")) score += 1;
            if (score > bestScore) { bestScore = score; bestRow = r; }
        }
        return bestScore >= 2 ? bestRow : -1;
    }

    /** Maps a header row onto our field names. First match per field wins. */
    private Map<String, Integer> mapColumns(String[] header) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (header == null) return out;

        for (Map.Entry<String, List<String>> e : ALIASES.entrySet()) {
            String field = e.getKey();
            int found = -1;
            // Aliases are ordered strongest-first, so the first alias that
            // matches any column is the best available reading.
            outer:
            for (String alias : e.getValue()) {
                for (int c = 0; c < header.length; c++) {
                    String h = norm(header[c]);
                    if (h.isEmpty() || out.containsValue(c)) continue;
                    if (h.equals(alias) || h.contains(alias)) { found = c; break outer; }
                }
            }
            if (found >= 0) out.put(field, found);
        }
        return out;
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    // ── Lines ──────────────────────────────────────────────────────────────

    private List<StagedLine> readLines(List<String[]> grid, int headerRow, Map<String, Integer> cols) {
        List<StagedLine> out = new ArrayList<>();
        Integer amountCol = cols.get("amount");
        for (int r = headerRow + 1; r < grid.size(); r++) {
            String[] row = grid.get(r);
            if (row == null || row.length == 0) continue;

            String rawAmount = at(row, amountCol);
            BigDecimal amount = decimal(rawAmount);
            if (amount == null) continue;

            String code = at(row, cols.get("eventCode"));
            String desc = at(row, cols.get("description"));
            if (code.isEmpty() && desc.isEmpty()) continue;

            // A printed subtotal would otherwise be imported as a fee line and
            // double the invoice.
            if (looksLikeTotalRow(code, desc)) continue;

            out.add(new StagedLine(r + 1, code, desc,
                    decimal(at(row, cols.get("quantity"))),
                    decimal(at(row, cols.get("unitRate"))),
                    amount));
        }
        return out;
    }

    private static boolean looksLikeTotalRow(String code, String desc) {
        String d = norm(desc);
        String c = norm(code);
        for (String t : List.of("total", "grand total", "sub total", "subtotal", "sum of")) {
            if (d.equals(t) || d.startsWith(t + " ") || c.equals(t)) return true;
        }
        return false;
    }

    // ── Detection ──────────────────────────────────────────────────────────

    private Field detectScheme(String fileName, List<String[]> grid) {
        String haystack = (fileName == null ? "" : fileName) + " " + firstRowsText(grid, 30);
        String h = haystack.toLowerCase(Locale.ROOT);

        boolean mc   = h.contains("mastercard") || h.contains("master card")
                    || h.contains("gcms") || h.contains("mcbs") || h.contains("ipm");
        boolean visa = h.contains("visa") || h.contains("vss") || h.contains("base ii")
                    || h.contains("baseii");

        if (mc && !visa)  return new Field("MASTERCARD", 90, "The file names Mastercard");
        if (visa && !mc)  return new Field("VISA", 90, "The file names Visa");
        if (mc)           return new Field("MASTERCARD", 40, "Both schemes are mentioned — Mastercard appears first");
        return Field.none("Neither scheme could be identified from the file");
    }

    private Field detectCurrency(List<String[]> grid, int headerRow, Map<String, Integer> cols) {
        // A currency column is the strongest signal: it says so per line.
        Integer col = cols.get("currency");
        if (col != null && headerRow >= 0) {
            for (int r = headerRow + 1; r < Math.min(grid.size(), headerRow + 200); r++) {
                String v = at(grid.get(r), col).toUpperCase(Locale.ROOT).trim();
                if (v.length() == 3 && v.chars().allMatch(Character::isLetter))
                    return new Field(v, 95, "Read from the currency column");
            }
        }
        // Otherwise a labelled cell in the header block.
        Field labelled = detectLabelled(grid, headerRow, List.of("currency", "ccy"));
        if (labelled.value() != null) {
            Matcher m = CURRENCY_CODE.matcher(String.valueOf(labelled.value()).toUpperCase(Locale.ROOT));
            if (m.find()) return new Field(m.group(1), 75, "Found beside a currency label");
        }
        return Field.none("No currency was stated on the file");
    }

    private Field detectEntity(List<String[]> grid, int headerRow, Map<String, Integer> cols) {
        Integer col = cols.get("entity");
        if (col != null && headerRow >= 0) {
            for (int r = headerRow + 1; r < Math.min(grid.size(), headerRow + 200); r++) {
                String v = at(grid.get(r), col).trim();
                if (!v.isEmpty()) return new Field(v, 85, "Read from the entity column");
            }
        }
        Field labelled = detectLabelled(grid, headerRow,
                List.of("business id", "member id", "ica", "bid", "entity"));
        if (labelled.value() != null)
            return new Field(labelled.value(), 70, labelled.evidence());
        return Field.none("No billing entity was identified");
    }

    /**
     * Period, from the strongest evidence available: a period column, then a
     * labelled cell, then the file name.
     */
    private Field[] detectPeriod(String fileName, List<String[]> grid,
                                 int headerRow, Map<String, Integer> cols) {
        Integer col = cols.get("period");
        if (col != null && headerRow >= 0) {
            for (int r = headerRow + 1; r < Math.min(grid.size(), headerRow + 200); r++) {
                LocalDate d = asPeriod(at(grid.get(r), col));
                if (d != null) return period(d, 90, "Read from the period column");
            }
        }
        Field labelled = detectLabelled(grid, headerRow,
                List.of("billing period", "period", "cycle", "statement month", "month"));
        if (labelled.value() != null) {
            LocalDate d = asPeriod(String.valueOf(labelled.value()));
            if (d != null) return period(d, 80, labelled.evidence());
        }
        LocalDate fromName = asPeriod(fileName);
        if (fromName != null) return period(fromName, 60, "Read from the file name");
        return new Field[]{ Field.none("No billing period was found"),
                            Field.none("No billing period was found") };
    }

    private static Field[] period(LocalDate d, int confidence, String why) {
        return new Field[]{
                new Field(d.getYear(), confidence, why),
                new Field(d.getMonthValue(), confidence, why),
        };
    }

    /** Turns "2026-09", "Sep 2026", "01/09/2026" into a month. */
    private static LocalDate asPeriod(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Matcher ym = YEAR_MONTH.matcher(raw);
        if (ym.find())
            return LocalDate.of(Integer.parseInt(ym.group(1)), Integer.parseInt(ym.group(2)), 1);

        Matcher my = MONTH_YEAR.matcher(raw);
        if (my.find()) {
            int month = switch (my.group(1).toLowerCase(Locale.ROOT)) {
                case "jan" -> 1;  case "feb" -> 2;  case "mar" -> 3;  case "apr" -> 4;
                case "may" -> 5;  case "jun" -> 6;  case "jul" -> 7;  case "aug" -> 8;
                case "sep" -> 9;  case "oct" -> 10; case "nov" -> 11; default -> 12;
            };
            return LocalDate.of(Integer.parseInt(my.group(2)), month, 1);
        }
        return null;
    }

    /**
     * The invoice total as PRINTED on the file — never the sum of the lines.
     *
     * Falling back to the parsed sum would make reconciliation tautological:
     * it would always agree, and the one check that catches a mis-read file
     * would be dead.
     */
    private Field detectInvoiceTotal(List<String[]> grid) {
        List<String> labels = List.of("grand total", "invoice total", "total amount due",
                "total due", "net total", "total");
        for (int r = 0; r < grid.size(); r++) {
            String[] row = grid.get(r);
            if (row == null) continue;
            for (int c = 0; c < row.length; c++) {
                String cell = norm(row[c]);
                if (cell.isEmpty()) continue;
                boolean isLabel = labels.stream().anyMatch(l -> cell.equals(l) || cell.startsWith(l + " "));
                if (!isLabel) continue;
                // The figure is in the same row, to the right of the label.
                for (int k = c + 1; k < row.length; k++) {
                    BigDecimal v = decimal(row[k]);
                    if (v != null && v.abs().compareTo(BigDecimal.ZERO) > 0)
                        return new Field(v.toPlainString(), 85,
                                "Found beside \"" + row[c].trim() + "\" on row " + (r + 1));
                }
            }
        }
        return Field.none("No printed invoice total was found");
    }

    /** Finds a value beside a labelled cell in the block above the table. */
    private Field detectLabelled(List<String[]> grid, int headerRow, List<String> labels) {
        int limit = headerRow > 0 ? Math.min(headerRow + 1, grid.size()) : Math.min(grid.size(), 40);
        for (int r = 0; r < limit; r++) {
            String[] row = grid.get(r);
            if (row == null) continue;
            for (int c = 0; c < row.length; c++) {
                String cell = norm(row[c]);
                if (cell.isEmpty()) continue;
                for (String label : labels) {
                    if (!cell.equals(label) && !cell.startsWith(label + " ") && !cell.endsWith(" " + label))
                        continue;
                    // Either the next non-empty cell on the row …
                    for (int k = c + 1; k < row.length; k++) {
                        if (!row[k].isBlank())
                            return new Field(row[k].trim(), 70,
                                    "Found beside \"" + row[c].trim() + "\" on row " + (r + 1));
                    }
                    // … or the label and value share one cell: "Currency: AED".
                    String inline = row[c];
                    int colon = inline.indexOf(':');
                    if (colon >= 0 && colon + 1 < inline.length())
                        return new Field(inline.substring(colon + 1).trim(), 65,
                                "Read from \"" + inline.trim() + "\" on row " + (r + 1));
                }
            }
        }
        return Field.none("No labelled value was found");
    }

    // ── Cell helpers ───────────────────────────────────────────────────────

    private static String firstRowsText(List<String[]> grid, int rows) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < Math.min(grid.size(), rows); r++) {
            String[] row = grid.get(r);
            if (row == null) continue;
            for (String c : row) sb.append(c).append(' ');
        }
        return sb.toString();
    }

    private static String at(String[] row, Integer col) {
        if (row == null || col == null || col < 0 || col >= row.length) return "";
        return row[col] == null ? "" : row[col].trim();
    }

    /**
     * Parses a billing amount.
     *
     * Handles thousands separators, a trailing or leading currency symbol or
     * code, and accounting negatives in parentheses — all of which appear in
     * real extracts and all of which {@code Double.parseDouble} rejects.
     * Returns null when the cell is not a number at all, which is how a
     * heading or a blank row is told apart from a zero charge.
     */
    static BigDecimal decimal(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        boolean negative = false;
        if (s.startsWith("(") && s.endsWith(")")) { negative = true; s = s.substring(1, s.length() - 1); }
        if (s.endsWith("-")) { negative = true; s = s.substring(0, s.length() - 1); }

        s = s.replaceAll("[A-Za-z$€£¥]", "").replace(",", "").replace(" ", "").trim();
        if (s.startsWith("-")) { negative = true; s = s.substring(1); }
        if (s.isEmpty() || s.equals(".")) return null;
        if (!s.matches("\\d*\\.?\\d+")) return null;

        try {
            BigDecimal v = new BigDecimal(s).setScale(4, RoundingMode.HALF_UP);
            return negative ? v.negate() : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
