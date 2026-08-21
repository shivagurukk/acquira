package com.acquira.core.service;

import com.acquira.batch.job.CsvPartitioner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.item.ExecutionContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CsvPartitioner} — the Spring Batch partitioner that
 * fans one ExecutionContext out per .csv file in a directory.
 *
 * Covers: unset directory, missing directory, empty directory, .csv-only
 * filtering (mixed file types), per-file context contents (absolute fileName),
 * and partition-key naming.
 */
class CsvPartitionerTest {

    @TempDir
    Path dir;

    private CsvPartitioner partitionerFor(Path directory) {
        CsvPartitioner p = new CsvPartitioner();
        p.setPartitionDirectory(directory.toString());
        return p;
    }

    @Test
    @DisplayName("unset partition directory throws IllegalArgumentException")
    void directoryNotSet() {
        CsvPartitioner p = new CsvPartitioner();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> p.partition(1));
        assertTrue(ex.getMessage().contains("not set"));
    }

    @Test
    @DisplayName("non-existent partition directory throws IllegalArgumentException")
    void directoryMissing() {
        CsvPartitioner p = new CsvPartitioner();
        p.setPartitionDirectory(dir.resolve("does-not-exist").toString());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> p.partition(1));
        assertTrue(ex.getMessage().contains("does not exist"));
    }

    @Test
    @DisplayName("empty directory yields no partitions")
    void emptyDirectory() {
        Map<String, ExecutionContext> map = partitionerFor(dir).partition(4);
        assertTrue(map.isEmpty());
    }

    @Test
    @DisplayName("one partition is created per .csv file")
    void onePartitionPerCsv() throws Exception {
        Files.writeString(dir.resolve("part_001.csv"), "a,b\n1,2\n");
        Files.writeString(dir.resolve("part_002.csv"), "a,b\n3,4\n");

        Map<String, ExecutionContext> map = partitionerFor(dir).partition(1);

        assertEquals(2, map.size());
        assertTrue(map.keySet().stream().allMatch(k -> k.startsWith("partition_")));
    }

    @Test
    @DisplayName("non-csv files are ignored")
    void ignoresNonCsv() throws Exception {
        Files.writeString(dir.resolve("data.csv"), "a\n1\n");
        Files.writeString(dir.resolve("notes.txt"), "ignore me");
        Files.writeString(dir.resolve("sheet.xlsx"), "ignore me too");

        Map<String, ExecutionContext> map = partitionerFor(dir).partition(1);

        assertEquals(1, map.size());
        assertTrue(map.containsKey("partition_data.csv"));
    }

    @Test
    @DisplayName("each partition context carries the absolute fileName ending in .csv")
    void contextHasAbsoluteFileName() throws Exception {
        Path csv = dir.resolve("solo.csv");
        Files.writeString(csv, "a\n1\n");

        Map<String, ExecutionContext> map = partitionerFor(dir).partition(1);
        ExecutionContext ctx = map.get("partition_solo.csv");

        assertNotNull(ctx);
        String fileName = ctx.getString("fileName");
        assertTrue(fileName.endsWith("solo.csv"));
        assertTrue(Path.of(fileName).isAbsolute());
    }
}
