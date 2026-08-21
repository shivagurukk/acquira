package com.acquira.core.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import com.lowagie.text.Document;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.FontFactory;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Generates Excel, CSV and PDF exports from DataExplorer query results.
 * Reuses the same query logic as AnalyticsExplorerController.
 *
 * ─── Currency handling ───
 * Every export is denominated in the requesting tenant's base currency, and
 * monetary columns are written at that currency's minor-unit precision
 * (BHD = 3, EGP/AED = 2). Before this, the Excel number format was a hardcoded
 * {@code "#,##0.00"} (so BHD lost its third digit), a monetary value that
 * happened to be whole was silently downgraded to the integer style (so
 * "1234.000" became "1234"), and the CSV / PDF paths emitted raw
 * {@code toString()} with no currency label anywhere on the artifact.
 *
 * Every public export method now has a tenant-explicit overload. The no-tenant
 * overloads resolve from {@link TenantContext}, which request threads already
 * populate — that keeps {@code ReportBuilderController} working unchanged.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReportExportService {

    private final JdbcTemplate jdbcTemplate;
    private final com.acquira.common.service.CurrencyResolver currencyResolver;

    /**
     * Column-name fragments that mark a numeric column as MONEY. Data Explorer
     * returns an arbitrary, user-chosen column set with no type metadata, so the
     * measure name is the only signal available. Anything not matched here is
     * treated as a plain number (counts, ratios, percentages) and is NOT scaled
     * to the currency precision.
     */
    private static final List<String> MONEY_COLUMN_HINTS = List.of(
        "amount", "volume", "value", "revenue", "sales", "msf", "fee", "spend",
        "interchange", "commission", "net_", "gross_", "turnover", "ticket", "atv",
        "refund", "chargeback", "settlement", "payout", "balance"
    );

    /** Resolved currency for a report: ISO code plus minor-unit precision. */
    private record ExportCurrency(String code, int decimals) {
        String excelFormat() {
            if (decimals <= 0) return "#,##0";
            StringBuilder sb = new StringBuilder("#,##0.");
            for (int i = 0; i < decimals; i++) sb.append('0');
            return sb.toString();
        }
    }

    /**
     * FAILS LOUD rather than defaulting to 2 decimals — a BHD export silently
     * rounded to fils-less dinars is worse than a failed export.
     */
    private ExportCurrency requireCurrency(Long tenantId) {
        if (tenantId == null) {
            log.error("[export] no tenant on the calling thread — cannot resolve the report currency");
            throw new IllegalStateException("Tenant context not resolved — refusing to export an unlabelled report");
        }
        try {
            var info = currencyResolver.forTenant(tenantId);
            if (info == null || info.code() == null || info.code().isBlank()) {
                throw new IllegalStateException("CurrencyResolver returned no currency");
            }
            return new ExportCurrency(info.code(), info.decimals());
        } catch (RuntimeException e) {
            log.error("[export] currency unresolved for tenant {} — refusing to export. Cause: {}", tenantId, e.toString());
            throw new IllegalStateException("Currency could not be resolved for tenant " + tenantId, e);
        }
    }

    private static boolean isMoneyColumn(String header) {
        if (header == null) return false;
        String h = header.toLowerCase();
        return MONEY_COLUMN_HINTS.stream().anyMatch(h::contains);
    }

    private static String formatMoney(Object val, int decimals) {
        if (val == null) return "";
        if (val instanceof Number n) return String.format("%." + decimals + "f", n.doubleValue());
        return val.toString();
    }

    /**
     * Export query results as styled Excel (.xlsx), for the tenant on the
     * current thread. Kept so {@code ReportBuilderController} needs no change.
     */
    public byte[] exportExcel(List<Map<String, Object>> rows, String reportName, Map<String, Object> queryConfig) {
        return exportExcel(rows, reportName, queryConfig,
                com.acquira.common.config.TenantContext.getCurrentTenant());
    }

    /**
     * Export query results as styled Excel (.xlsx) for an explicit tenant.
     * Monetary columns are written with the tenant's decimal precision.
     */
    public byte[] exportExcel(List<Map<String, Object>> rows, String reportName,
                             Map<String, Object> queryConfig, Long tenantId) {
        ExportCurrency ccy = requireCurrency(tenantId);
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            XSSFSheet sheet = workbook.createSheet("Report");

            // ─── Styles ───
            XSSFCellStyle headerStyle = workbook.createCellStyle();
            XSSFFont headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setFontHeightInPoints((short) 11);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte) 37, (byte) 99, (byte) 235}, null));
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            headerStyle.setBorderBottom(BorderStyle.THIN);

            // Currency-aware money format: EGP/AED "#,##0.00", BHD "#,##0.000".
            XSSFCellStyle moneyStyle = workbook.createCellStyle();
            moneyStyle.setDataFormat(workbook.createDataFormat().getFormat(ccy.excelFormat()));

            // Non-monetary decimals (rates, ratios, averages) keep 2dp.
            XSSFCellStyle numberStyle = workbook.createCellStyle();
            numberStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));

            XSSFCellStyle intStyle = workbook.createCellStyle();
            intStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));

            XSSFCellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));

            XSSFCellStyle altRowStyle = workbook.createCellStyle();
            altRowStyle.setFillForegroundColor(new XSSFColor(new byte[]{(byte) 248, (byte) 250, (byte) 252}, null));
            altRowStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // ─── Title row ───
            int rowIdx = 0;
            XSSFCellStyle titleStyle = workbook.createCellStyle();
            XSSFFont titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            titleStyle.setFont(titleFont);

            Row titleRow = sheet.createRow(rowIdx++);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(reportName != null ? reportName : "Data Explorer Report");
            titleCell.setCellStyle(titleStyle);

            Row dateRow = sheet.createRow(rowIdx++);
            // Report-level currency metadata — previously the workbook carried no
            // indication of which currency the amounts were denominated in.
            dateRow.createCell(0).setCellValue("Generated: "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                + "   |   Currency: " + ccy.code() + " (" + ccy.decimals() + " decimals)");
            rowIdx++; // blank row

            if (rows == null || rows.isEmpty()) {
                sheet.createRow(rowIdx).createCell(0).setCellValue("No data found");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                workbook.write(out);
                return out.toByteArray();
            }

            // ─── Headers ───
            List<String> headers = new ArrayList<>(rows.get(0).keySet());
            Row headerRow = sheet.createRow(rowIdx++);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(formatHeader(headers.get(i)));
                cell.setCellStyle(headerStyle);
            }

            // ─── Data rows ───
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(rowIdx++);
                Map<String, Object> data = rows.get(r);
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = row.createCell(c);
                    Object val = data.get(headers.get(c));

                    if (val == null) {
                        cell.setCellValue("");
                    } else if (val instanceof Number) {
                        double d = ((Number) val).doubleValue();
                        cell.setCellValue(d);
                        // A monetary column ALWAYS gets the currency format, even when
                        // the value happens to be whole. The old `d == Math.floor(d)`
                        // shortcut sent 1234.000 BHD down the integer path and printed
                        // "1,234", hiding the fils entirely.
                        if (isMoneyColumn(headers.get(c))) {
                            cell.setCellStyle(moneyStyle);
                        } else {
                            cell.setCellStyle(d == Math.floor(d) ? intStyle : numberStyle);
                        }
                    } else {
                        cell.setCellValue(val.toString());
                    }

                    // Alt row shading
                    if (r % 2 == 1 && !(val instanceof Number)) {
                        cell.setCellStyle(altRowStyle);
                    }
                }
            }

            // ─── Auto-size columns ───
            for (int i = 0; i < headers.size(); i++) {
                sheet.autoSizeColumn(i);
                int w = sheet.getColumnWidth(i);
                sheet.setColumnWidth(i, Math.min(w + 512, 15000)); // cap width
            }

            // ─── Auto-filter ───
            if (headers.size() > 0) {
                sheet.setAutoFilter(new CellRangeAddress(3, 3, 0, headers.size() - 1));
            }

            // ─── Freeze header ───
            sheet.createFreezePane(0, 4);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();

        } catch (Exception e) {
            log.error("Excel export failed", e);
            throw new RuntimeException("Excel export failed: " + e.getMessage(), e);
        }
    }

    /** Export query results as CSV for the tenant on the current thread. */
    public byte[] exportCsv(List<Map<String, Object>> rows) {
        return exportCsv(rows, com.acquira.common.config.TenantContext.getCurrentTenant());
    }

    /**
     * Export query results as CSV for an explicit tenant.
     *
     * Two changes from the raw {@code toString()} dump this used to be:
     *  1. monetary columns are written at the tenant's minor-unit precision, so a
     *     BHD figure keeps its three digits instead of arriving as "450.755" or
     *     "450.76" depending on how the driver stringified the BigDecimal;
     *  2. a trailing {@code currency} column labels every row, because a bare CSV
     *     carried no indication of the denomination at all.
     */
    public byte[] exportCsv(List<Map<String, Object>> rows, Long tenantId) {
        ExportCurrency ccy = requireCurrency(tenantId);
        if (rows == null || rows.isEmpty()) return "No data\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        StringBuilder sb = new StringBuilder();
        List<String> headers = new ArrayList<>(rows.get(0).keySet());

        // Header (+ currency column)
        sb.append(String.join(",", headers.stream().map(this::csvEscape).toArray(String[]::new)));
        sb.append(",").append(csvEscape("currency"));
        sb.append("\n");

        // Data
        for (Map<String, Object> row : rows) {
            for (int i = 0; i < headers.size(); i++) {
                if (i > 0) sb.append(",");
                String h = headers.get(i);
                Object val = row.get(h);
                String out = (val != null && isMoneyColumn(h))
                        ? formatMoney(val, ccy.decimals())
                        : (val != null ? val.toString() : "");
                sb.append(csvEscape(out));
            }
            sb.append(",").append(csvEscape(ccy.code()));
            sb.append("\n");
        }

        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Export query results as a simple, branded tabular PDF (OpenPDF).
     * Landscape A4, zebra rows, capped at 2000 rows for size.
     */
    public byte[] exportPdf(List<Map<String, Object>> rows, String reportName) {
        return exportPdf(rows, reportName, com.acquira.common.config.TenantContext.getCurrentTenant());
    }

    /**
     * Tenant-explicit PDF export. Monetary cells are rendered at the tenant's
     * minor-unit precision and the currency is stated in the header meta line —
     * the previous version printed raw {@code toString()} with no currency
     * anywhere on the page.
     */
    public byte[] exportPdf(List<Map<String, Object>> rows, String reportName, Long tenantId) {
        ExportCurrency ccy = requireCurrency(tenantId);
        Document doc = new Document(PageSize.A4.rotate(), 24, 24, 28, 28);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            com.lowagie.text.Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, new java.awt.Color(30, 41, 59));
            Paragraph title = new Paragraph(reportName != null ? reportName : "Report", titleFont);
            title.setSpacingAfter(6f);
            doc.add(title);

            com.lowagie.text.Font metaFont = FontFactory.getFont(FontFactory.HELVETICA, 8, new java.awt.Color(100, 116, 139));
            doc.add(new Paragraph("Generated "
                + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                + "  •  " + (rows != null ? rows.size() : 0) + " rows"
                + "  •  Amounts in " + ccy.code() + " (" + ccy.decimals() + " dp)", metaFont));

            if (rows == null || rows.isEmpty()) {
                doc.add(new Paragraph(" "));
                doc.add(new Paragraph("No data.", FontFactory.getFont(FontFactory.HELVETICA, 10)));
            } else {
                List<String> headers = new ArrayList<>(rows.get(0).keySet());
                PdfPTable table = new PdfPTable(headers.size());
                table.setWidthPercentage(100);
                table.setSpacingBefore(10f);

                com.lowagie.text.Font hf = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8, java.awt.Color.WHITE);
                java.awt.Color headerBg = new java.awt.Color(37, 99, 235);
                java.awt.Color borderCol = new java.awt.Color(226, 232, 240);
                for (String h : headers) {
                    PdfPCell c = new PdfPCell(new Phrase(formatHeader(h), hf));
                    c.setBackgroundColor(headerBg);
                    c.setBorderColor(borderCol);
                    c.setPadding(5f);
                    table.addCell(c);
                }

                com.lowagie.text.Font cf = FontFactory.getFont(FontFactory.HELVETICA, 8, new java.awt.Color(30, 41, 59));
                int limit = Math.min(rows.size(), 2000);
                for (int r = 0; r < limit; r++) {
                    Map<String, Object> row = rows.get(r);
                    java.awt.Color rowBg = (r % 2 == 0) ? java.awt.Color.WHITE : new java.awt.Color(248, 250, 252);
                    for (String h : headers) {
                        Object v = row.get(h);
                        String text = (v != null && isMoneyColumn(h))
                                ? formatMoney(v, ccy.decimals())
                                : (v != null ? v.toString() : "");
                        PdfPCell c = new PdfPCell(new Phrase(text, cf));
                        c.setBackgroundColor(rowBg);
                        c.setBorderColor(borderCol);
                        c.setPadding(4f);
                        table.addCell(c);
                    }
                }
                doc.add(table);
                if (rows.size() > limit) {
                    doc.add(new Paragraph("… " + (rows.size() - limit) + " more rows truncated.", metaFont));
                }
            }

            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            log.error("PDF export failed", e);
            throw new RuntimeException("PDF export failed: " + e.getMessage(), e);
        }
    }

    private String formatHeader(String key) {
        return key.replace("_", " ").substring(0, 1).toUpperCase() + key.replace("_", " ").substring(1);
    }

    private String csvEscape(String val) {
        if (val.contains(",") || val.contains("\"") || val.contains("\n")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }
}
