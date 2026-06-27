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
 * Generates Excel and CSV exports from DataExplorer query results.
 * Reuses the same query logic as AnalyticsExplorerController.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ReportExportService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Export query results as styled Excel (.xlsx).
     */
    public byte[] exportExcel(List<Map<String, Object>> rows, String reportName, Map<String, Object> queryConfig) {
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
            dateRow.createCell(0).setCellValue("Generated: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
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
                        cell.setCellStyle(d == Math.floor(d) ? intStyle : numberStyle);
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

    /**
     * Export query results as CSV.
     */
    public byte[] exportCsv(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return "No data\n".getBytes();

        StringBuilder sb = new StringBuilder();
        List<String> headers = new ArrayList<>(rows.get(0).keySet());

        // Header
        sb.append(String.join(",", headers.stream().map(this::csvEscape).toArray(String[]::new)));
        sb.append("\n");

        // Data
        for (Map<String, Object> row : rows) {
            for (int i = 0; i < headers.size(); i++) {
                if (i > 0) sb.append(",");
                Object val = row.get(headers.get(i));
                sb.append(csvEscape(val != null ? val.toString() : ""));
            }
            sb.append("\n");
        }

        return sb.toString().getBytes();
    }

    /**
     * Export query results as a simple, branded tabular PDF (OpenPDF).
     * Landscape A4, zebra rows, capped at 2000 rows for size.
     */
    public byte[] exportPdf(List<Map<String, Object>> rows, String reportName) {
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
                + "  •  " + (rows != null ? rows.size() : 0) + " rows", metaFont));

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
                        PdfPCell c = new PdfPCell(new Phrase(v != null ? v.toString() : "", cf));
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
