package com.acquira.batch.job;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the MSF precision bug: the Excel->CSV splitter used
 * cell.getText(), which applies the cell's DISPLAY format. A workbook whose
 * MSF column stores 12.3456 but is formatted to show 2 decimals emitted
 * "12.35" into the split CSV, silently truncating every money value before
 * staging. Money columns must be read via getRawValue() (safeMoneyText).
 */
class ExcelSplitterPrecisionTest {

    private static final String[] HEADERS = {
            "Entity Name", "Aggregator Internal Id", "Aggregator Name", "AggregatorCode",
            "MID", "Merchant Internal Id", "Merchant Name",
            "SID", "Merchant Store Internal Id", "CMM Merchant Store Internal Id",
            "Merchant Store Legal Name", "Store Name",
            "TerminalID", "ARN", "RRN Number", "CardNumber", "Auth Code",
            "Payment Date", "Transaction Date", "Transaction Time", "BatchNumber", "Transaction Type", "CardScheme",
            "Card Type", "DCC",
            "Txn Currency", "Txn Currency Amount", "Store Base Currency",
            "Store Base Currency Amount",
            "MSF", "VAT", "Total Amount Settled", "Interchange Fee", "Destination"
    };

    @TempDir
    Path tempDir;

    @Test
    void moneyColumnsKeepStoredPrecisionEvenWhenDisplayFormatRoundsTo2dp() throws Exception {
        // ── Author a workbook: MSF stores 12.3456 but DISPLAYS as 12.35 ──
        File xlsx = tempDir.resolve("AMS_precision_probe.xlsx").toFile();
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("data");
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) header.createCell(i).setCellValue(HEADERS[i]);

            CellStyle twoDp = wb.createCellStyle();
            twoDp.setDataFormat(wb.createDataFormat().getFormat("0.00"));

            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue("BANK1");
            r.createCell(4).setCellValue("MID001");
            r.createCell(6).setCellValue("Test Merchant");
            r.createCell(17).setCellValue("2026-07-01");           // Payment Date (text)
            r.createCell(18).setCellValue("2026-07-01 10:00:00");  // Transaction Date (text)
            r.createCell(21).setCellValue("PURCHASE");
            r.createCell(22).setCellValue("VISA");

            Cell amount = r.createCell(26);                        // Txn Currency Amount
            amount.setCellValue(100.4567);
            amount.setCellStyle(twoDp);

            Cell msf = r.createCell(29);                           // MSF
            msf.setCellValue(12.3456);
            msf.setCellStyle(twoDp);

            try (FileOutputStream out = new FileOutputStream(xlsx)) { wb.write(out); }
        }

        // ── Run the private splitExcelFile(File, Path) via reflection ──
        ExcelSplitterTasklet tasklet = new ExcelSplitterTasklet();
        Method split = ExcelSplitterTasklet.class.getDeclaredMethod("splitExcelFile", File.class, Path.class);
        split.setAccessible(true);
        Path outDir = tempDir.resolve("out");
        Files.createDirectories(outDir);
        long rows = (long) split.invoke(tasklet, xlsx, outDir);
        assertEquals(1L, rows, "exactly the one data row should be split");

        // ── The split CSV must carry the STORED values, not the displayed ones ──
        Path csv;
        try (var s = Files.list(outDir)) {
            csv = s.filter(p -> p.toString().endsWith(".csv")).findFirst().orElseThrow();
        }
        List<String> lines = Files.readAllLines(csv);
        String dataLine = lines.get(1);

        assertTrue(dataLine.contains("12.3456"),
                "MSF must keep its stored 4-dp value; got line: " + dataLine);
        assertFalse(dataLine.contains("\"12.35\""),
                "MSF must NOT be the display-rounded 12.35; got line: " + dataLine);
        assertTrue(dataLine.contains("100.4567"),
                "Txn Currency Amount must keep stored precision; got line: " + dataLine);
    }
}
