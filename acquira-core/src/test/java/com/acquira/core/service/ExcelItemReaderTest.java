package com.acquira.core.service;

import com.acquira.batch.job.ExcelItemReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.core.io.FileSystemResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ExcelItemReader}'s CSV path, driven through the public
 * Spring Batch ItemReader API (open/read/close) against real temp files.
 *
 * Covers: simple comma parsing, header normalization (case/space/underscore),
 * quoted fields containing the delimiter, quoted fields containing embedded
 * newlines, the "" escape, tab-delimiter auto-detection, UTF-8 BOM stripping,
 * blank-line skipping, missing-column and empty-field -> null, isCsvMode(),
 * and empty files.
 */
class ExcelItemReaderTest {

    @TempDir
    Path dir;

    /** Write a CSV file and read every row, projecting the requested headers into a map. */
    private List<Map<String, String>> readAll(String fileName, String content, String... headers) throws Exception {
        Path file = dir.resolve(fileName);
        Files.writeString(file, content, StandardCharsets.UTF_8);

        ExcelItemReader<Map<String, String>> reader = new ExcelItemReader<>();
        reader.setResource(new FileSystemResource(file.toFile()));
        reader.setLinesToSkip(1);
        reader.setCsvRowMapper((r, n) -> {
            Map<String, String> m = new LinkedHashMap<>();
            for (String h : headers) m.put(h, r.getCsvCellValue(h));
            return m;
        });

        List<Map<String, String>> out = new ArrayList<>();
        reader.open(new ExecutionContext());
        try {
            Map<String, String> row;
            while ((row = reader.read()) != null) out.add(row);
        } finally {
            reader.close();
        }
        return out;
    }

    @Test
    @DisplayName("simple comma-delimited CSV reads all rows and columns")
    void simpleComma() throws Exception {
        List<Map<String, String>> rows = readAll("simple.csv",
                "name,city\nAcme,NYC\nBob,LA\n", "name", "city");
        assertEquals(2, rows.size());
        assertEquals("Acme", rows.get(0).get("name"));
        assertEquals("NYC", rows.get(0).get("city"));
        assertEquals("Bob", rows.get(1).get("name"));
        assertEquals("LA", rows.get(1).get("city"));
    }

    @Test
    @DisplayName("header lookup ignores case, spaces and underscores")
    void headerNormalization() throws Exception {
        String content = "Merchant Name,Merchant_ID\nAcme,123\n";
        // all three spellings must resolve to the same column
        assertEquals("Acme", readAll("h1.csv", content, "merchant name").get(0).get("merchant name"));
        assertEquals("Acme", readAll("h2.csv", content, "MERCHANTNAME").get(0).get("MERCHANTNAME"));
        assertEquals("123", readAll("h3.csv", content, "merchant id").get(0).get("merchant id"));
    }

    @Test
    @DisplayName("a quoted field containing the delimiter is not split")
    void quotedDelimiter() throws Exception {
        List<Map<String, String>> rows = readAll("q.csv",
                "name,note\nAcme,\"Hello, World\"\n", "name", "note");
        assertEquals(1, rows.size());
        assertEquals("Hello, World", rows.get(0).get("note"));
    }

    @Test
    @DisplayName("a quoted field with an embedded newline stays a single record")
    void embeddedNewline() throws Exception {
        List<Map<String, String>> rows = readAll("nl.csv",
                "name,note\nAcme,\"line1\nline2\"\nBob,plain\n", "name", "note");
        assertEquals(2, rows.size());
        assertEquals("line1\nline2", rows.get(0).get("note"));
        assertEquals("Bob", rows.get(1).get("name"));
        assertEquals("plain", rows.get(1).get("note"));
    }

    @Test
    @DisplayName("an escaped double-quote (\"\") becomes a single literal quote")
    void escapedQuote() throws Exception {
        List<Map<String, String>> rows = readAll("esc.csv",
                "name,note\nAcme,\"She said \"\"hi\"\"\"\n", "name", "note");
        assertEquals(1, rows.size());
        assertEquals("She said \"hi\"", rows.get(0).get("note"));
    }

    @Test
    @DisplayName("tab-delimited files are auto-detected")
    void tabDelimited() throws Exception {
        List<Map<String, String>> rows = readAll("tab.tsv",
                "name\tcity\nAcme\tNYC\n", "name", "city");
        assertEquals(1, rows.size());
        assertEquals("Acme", rows.get(0).get("name"));
        assertEquals("NYC", rows.get(0).get("city"));
    }

    @Test
    @DisplayName("a UTF-8 BOM on the first header is stripped")
    void bomStripped() throws Exception {
        List<Map<String, String>> rows = readAll("bom.csv",
                "\uFEFFname,city\nAcme,NYC\n", "name", "city");
        assertEquals(1, rows.size());
        assertEquals("Acme", rows.get(0).get("name"), "first header must still resolve after BOM strip");
    }

    @Test
    @DisplayName("blank lines between records are skipped")
    void blankLinesSkipped() throws Exception {
        List<Map<String, String>> rows = readAll("blank.csv",
                "name,city\n\nAcme,NYC\n\n\nBob,LA\n", "name", "city");
        assertEquals(2, rows.size());
    }

    @Test
    @DisplayName("a missing column resolves to null")
    void missingColumn() throws Exception {
        List<Map<String, String>> rows = readAll("miss.csv",
                "name,city\nAcme,NYC\n", "name", "ghost");
        assertEquals("Acme", rows.get(0).get("name"));
        assertNull(rows.get(0).get("ghost"));
    }

    @Test
    @DisplayName("an empty field resolves to null")
    void emptyFieldNull() throws Exception {
        List<Map<String, String>> rows = readAll("empty.csv",
                "name,city,note\nAcme,,Plain\n", "name", "city", "note");
        assertEquals("Acme", rows.get(0).get("name"));
        assertNull(rows.get(0).get("city"));
        assertEquals("Plain", rows.get(0).get("note"));
    }

    @Test
    @DisplayName("isCsvMode() is true after opening a .csv resource")
    void isCsvMode() throws Exception {
        Path file = dir.resolve("mode.csv");
        Files.writeString(file, "a\n1\n");
        ExcelItemReader<String> reader = new ExcelItemReader<>();
        reader.setResource(new FileSystemResource(file.toFile()));
        reader.setCsvRowMapper((r, n) -> "x");
        reader.open(new ExecutionContext());
        try {
            assertTrue(reader.isCsvMode());
        } finally {
            reader.close();
        }
    }

    @Test
    @DisplayName("an empty file yields zero rows")
    void emptyFile() throws Exception {
        List<Map<String, String>> rows = readAll("nothing.csv", "", "name");
        assertTrue(rows.isEmpty());
    }
}
