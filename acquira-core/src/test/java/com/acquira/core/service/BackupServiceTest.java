package com.acquira.core.service;

import com.acquira.batch.service.BackupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the rewritten {@link BackupService}.
 *
 * Lives in the acquira-core test module (which depends on acquira-batch) because
 * acquira-batch has no test source tree yet; BackupService is public and on the
 * classpath, and the private internals are reached via reflection, so package
 * placement is irrelevant to what's exercised.
 *
 * These cover the parts that do NOT spawn pg_dump/pg_restore: the filename safety
 * guard (path-traversal defence), the JDBC-URL parser, and backup listing.
 * Actual dump/restore is integration-tested separately (needs the PostgreSQL
 * client binaries and a live database).
 */
@DisplayName("BackupService")
class BackupServiceTest {

    private BackupService svc;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        svc = new BackupService();
        // @Value fields aren't injected in a plain unit test — set them by reflection.
        set("backupDir", tempDir.toString());
        set("dbUrl", "jdbc:postgresql://localhost:5433/postgres");
        set("dbUser", "postgres");
        set("dbPassword", "postgres");
        set("pgDumpBin", "pg_dump");
        set("pgRestoreBin", "pg_restore");
        set("backupTimeoutSeconds", 30L);
        set("restoreTimeoutSeconds", 30L);
    }

    // ─────────────────────────────────────────────────────────────
    // Filename safety guard (path-traversal defence)
    // ─────────────────────────────────────────────────────────────
    @ParameterizedTest
    @ValueSource(strings = {
            "../evil.sql",          // parent traversal
            "..\\evil.sql",         // windows traversal
            "/etc/passwd.sql",      // absolute path
            "a/b.sql",              // path separator
            "evil.exe",             // disallowed extension
            "no_extension",         // no .sql/.dump
            "name with space.sql",  // space not allowed
            "report.SQL"            // wrong-case extension
    })
    @DisplayName("rejects unsafe / malformed backup file names")
    void rejectsUnsafeNames(String bad) {
        assertThrows(IllegalArgumentException.class, () -> svc.getBackupFile(bad));
        assertThrows(IllegalArgumentException.class, () -> svc.deleteBackup(bad));
        assertThrows(IllegalArgumentException.class, () -> svc.restoreBackup(bad));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "backup.sql",
            "backup_acquira_20260101_010000.sql",
            "snapshot.dump",
            "a-b_c.1.sql"
    })
    @DisplayName("accepts well-formed backup file names")
    void acceptsSafeNames(String good) {
        assertDoesNotThrow(() -> svc.getBackupFile(good));
    }

    @Test
    @DisplayName("rejects null / empty file name")
    void rejectsNullAndEmpty() {
        assertThrows(IllegalArgumentException.class, () -> svc.getBackupFile(null));
        assertThrows(IllegalArgumentException.class, () -> svc.getBackupFile(""));
    }

    // ─────────────────────────────────────────────────────────────
    // getBackupFile / deleteBackup / restoreBackup
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("getBackupFile resolves under the configured backup dir")
    void getBackupFile_resolvesUnderBackupDir() {
        File f = svc.getBackupFile("ok.sql");
        assertEquals("ok.sql", f.getName());
        assertEquals(tempDir.toFile().getAbsolutePath(), f.getParentFile().getAbsolutePath());
    }

    @Test
    @DisplayName("deleteBackup removes an existing file")
    void deleteBackup_removesExisting() throws IOException {
        Files.createFile(tempDir.resolve("del.sql"));
        assertTrue(Files.exists(tempDir.resolve("del.sql")));

        svc.deleteBackup("del.sql");

        assertFalse(Files.exists(tempDir.resolve("del.sql")));
    }

    @Test
    @DisplayName("deleteBackup is a no-op for a valid name with no file")
    void deleteBackup_noopWhenAbsent() {
        assertDoesNotThrow(() -> svc.deleteBackup("absent.sql"));
    }

    @Test
    @DisplayName("restoreBackup throws IOException when the (valid-named) file is missing")
    void restoreBackup_missingFile() {
        assertThrows(IOException.class, () -> svc.restoreBackup("missing.sql"));
    }

    // ─────────────────────────────────────────────────────────────
    // listBackups
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("listBackups returns empty for an empty directory")
    void listBackups_empty() {
        assertTrue(svc.listBackups().isEmpty());
    }

    @Test
    @DisplayName("listBackups includes .sql and .dump, excludes others, newest first")
    void listBackups_filtersAndSorts() throws IOException {
        Path older = Files.createFile(tempDir.resolve("older.sql"));
        Path newer = Files.createFile(tempDir.resolve("newer.dump"));
        Files.createFile(tempDir.resolve("ignore.txt"));     // excluded
        older.toFile().setLastModified(1_000_000L);
        newer.toFile().setLastModified(2_000_000L);

        List<BackupService.BackupFile> list = svc.listBackups();

        assertEquals(2, list.size(), "only .sql and .dump are listed");
        assertEquals("newer.dump", list.get(0).name(), "sorted newest-first");
        assertEquals("older.sql", list.get(1).name());
        assertTrue(list.get(0).lastModified() >= list.get(1).lastModified());
    }

    // ─────────────────────────────────────────────────────────────
    // parseDbUrl (private — exercised via reflection)
    // ─────────────────────────────────────────────────────────────
    @Test
    @DisplayName("parseDbUrl extracts host, explicit port and db; strips query params")
    void parseDbUrl_full() throws Exception {
        Object c = parse("jdbc:postgresql://db.internal:6543/misuat?reWriteBatchedInserts=true&ssl=on");
        assertEquals("db.internal", comp(c, "host"));
        assertEquals("6543", comp(c, "port"));
        assertEquals("misuat", comp(c, "dbName"));
    }

    @Test
    @DisplayName("parseDbUrl defaults the port to 5432 when omitted")
    void parseDbUrl_defaultPort() throws Exception {
        Object c = parse("jdbc:postgresql://localhost/postgres");
        assertEquals("localhost", comp(c, "host"));
        assertEquals("5432", comp(c, "port"));
        assertEquals("postgres", comp(c, "dbName"));
    }

    @Test
    @DisplayName("parseDbUrl falls back to localhost:5432/postgres for an unparseable URL")
    void parseDbUrl_fallback() throws Exception {
        Object c = parse("not-a-valid-jdbc-url");
        assertEquals("localhost", comp(c, "host"));
        assertEquals("5432", comp(c, "port"));
        assertEquals("postgres", comp(c, "dbName"));
    }

    // ─────────────────────────────────────────────────────────────
    // reflection helpers
    // ─────────────────────────────────────────────────────────────
    private void set(String field, Object value) throws Exception {
        Field f = BackupService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(svc, value);
    }

    private Object parse(String url) throws Exception {
        Method m = BackupService.class.getDeclaredMethod("parseDbUrl", String.class);
        m.setAccessible(true);
        return m.invoke(svc, url);
    }

    private String comp(Object dbConn, String accessor) throws Exception {
        Method m = dbConn.getClass().getDeclaredMethod(accessor);
        m.setAccessible(true);
        return (String) m.invoke(dbConn);
    }
}
