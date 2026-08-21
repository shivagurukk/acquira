package com.acquira.batch.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Database backup / restore via pg_dump / pg_restore.
 *
 * WHY THIS WAS REWRITTEN (was "not working as expected"):
 *  - The pg_dump/pg_restore output was logged at DEBUG, which the prod logback
 *    profile (WARN) discards — so a failed backup gave NO diagnosable reason,
 *    just "Backup failed. Exit code: 1". We now CAPTURE the tool output and put
 *    it in the thrown exception + log it at ERROR, so the real cause (binary not
 *    found, version mismatch, auth failure, permission denied) is visible.
 *  - The binary was hard-coded to bare "pg_dump"/"pg_restore", so if the Postgres
 *    client tools aren't on the service account's PATH (common on RHEL/systemd and
 *    on Windows) it failed with a cryptic "Cannot run program". The binary path is
 *    now configurable via app.backup.pg-dump / app.backup.pg-restore.
 *  - The timeout was a fixed 60s — far too short for any real database, so large
 *    dumps were reported as failures while pg_dump was still running. Timeout is
 *    now configurable and the process is destroyed (not orphaned) when it fires.
 *  - The JDBC URL parser is more robust (host, optional port→5432, db, strips params).
 *
 * CONFIG (all optional, sane defaults):
 *   app.backup.dir                  default ./backups
 *   app.backup.pg-dump              default pg_dump      (set to full path if not on PATH)
 *   app.backup.pg-restore           default pg_restore
 *   app.backup.timeout-seconds      default 1800 (30 min)
 *   app.backup.restore-timeout-seconds  default 3600 (60 min)
 *
 * NOTE: createBackup/restoreBackup are synchronous and can exceed the HTTP
 * request timeout (server.tomcat.connection-timeout / spring.mvc.async.request-timeout,
 * 10 min by default) for very large databases. For multi-GB databases consider
 * making these async with a job-status poll — out of scope for this fix.
 */
@Service
public class BackupService {

    private static final Logger logger = LoggerFactory.getLogger(BackupService.class);

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUser;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    @Value("${app.backup.dir:./backups}")
    private String backupDir;

    @Value("${app.backup.pg-dump:pg_dump}")
    private String pgDumpBin;

    @Value("${app.backup.pg-restore:pg_restore}")
    private String pgRestoreBin;

    @Value("${app.backup.timeout-seconds:1800}")
    private long backupTimeoutSeconds;

    @Value("${app.backup.restore-timeout-seconds:3600}")
    private long restoreTimeoutSeconds;

    // Only allow simple backup file names (defense-in-depth; the controller also validates).
    private static final Pattern SAFE_FILENAME = Pattern.compile("^[a-zA-Z0-9_.-]+\\.(sql|dump)$");

    // jdbc:postgresql://host[:port]/dbname[?params]
    private static final Pattern JDBC_PG = Pattern.compile(
            "jdbc:postgresql://([^:/?]+)(?::(\\d+))?/([^?/]+).*");

    private Path backupDirPath() throws IOException {
        Path dir = Paths.get(backupDir);
        Files.createDirectories(dir); // no-op if it already exists
        return dir;
    }

    public String createBackup() throws IOException, InterruptedException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = "backup_acquira_" + timestamp + ".sql";
        Path backupPath = backupDirPath().resolve(fileName);

        DbConn db = parseDbUrl(dbUrl);
        logger.info("Starting backup of DB '{}' on {}:{} → {} (timeout {}s)",
                db.dbName, db.host, db.port, fileName, backupTimeoutSeconds);

        List<String> cmd = Arrays.asList(
                pgDumpBin,
                "-h", db.host,
                "-p", db.port,
                "-U", dbUser,
                "-w",            // never prompt for a password (we pass it via PGPASSWORD)
                "-F", "c",       // custom format (compressed, restorable with pg_restore)
                "-b",            // include large objects
                "-v",            // verbose → progress on stderr (captured)
                "-f", backupPath.toAbsolutePath().toString(),
                db.dbName);

        ProcResult r = runProcess(cmd, backupTimeoutSeconds, "pg_dump");

        if (r.exitCode == 0) {
            logger.info("Backup created successfully: {} ({} bytes)", fileName,
                    Files.exists(backupPath) ? Files.size(backupPath) : 0);
            return fileName;
        }
        // Clean up the partial/empty dump so it doesn't show up as a usable backup.
        try { Files.deleteIfExists(backupPath); } catch (IOException ignored) {}
        throw new IOException(failureMessage("Backup", "pg_dump", r));
    }

    public void restoreBackup(String fileName) throws IOException, InterruptedException {
        requireSafeName(fileName);
        Path backupPath = backupDirPath().resolve(fileName);
        if (!Files.exists(backupPath)) {
            throw new IOException("Backup file not found: " + fileName);
        }

        DbConn db = parseDbUrl(dbUrl);
        logger.warn("Restoring backup {} into DB '{}' on {}:{} (timeout {}s)",
                fileName, db.dbName, db.host, db.port, restoreTimeoutSeconds);

        List<String> cmd = Arrays.asList(
                pgRestoreBin,
                "-h", db.host,
                "-p", db.port,
                "-U", dbUser,
                "-w",
                "-d", db.dbName,
                "-v",
                "-c",            // drop objects before recreating
                "--if-exists",   // don't error if an object to drop is missing
                backupPath.toAbsolutePath().toString());

        ProcResult r = runProcess(cmd, restoreTimeoutSeconds, "pg_restore");

        // pg_restore returns exit code 1 for non-fatal warnings (very common with -c
        // on a partially-populated DB) BUT ALSO for genuine failures (permission
        // denied on RDS, missing role/extension, corrupt dump). Previously we treated
        // every exit-1 as success-with-warnings, so a failed restore reported success.
        // Now: exit 0 = clean; exit 1 = inspect the output and only pass if it contains
        // no real error lines; anything else = hard fail.
        if (r.exitCode == 0) {
            logger.info("Restore completed successfully from {}", fileName);
        } else if (r.exitCode == 1 && !hasRealErrors(r.output)) {
            logger.warn("Restore completed with non-fatal warnings (exit 1) from {}. Output tail:\n{}",
                    fileName, r.outputTail());
        } else {
            throw new IOException(failureMessage("Restore", "pg_restore", r));
        }
    }

    public List<BackupFile> listBackups() {
        File folder;
        try {
            folder = backupDirPath().toFile();
        } catch (IOException e) {
            logger.warn("Could not access backup directory '{}': {}", backupDir, e.getMessage());
            return Collections.emptyList();
        }
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".sql") || name.endsWith(".dump"));
        if (files == null) return Collections.emptyList();

        return Arrays.stream(files)
                .map(file -> new BackupFile(file.getName(), file.length(), file.lastModified()))
                .sorted(Comparator.comparingLong(BackupFile::lastModified).reversed())
                .collect(Collectors.toList());
    }

    public void deleteBackup(String fileName) throws IOException {
        requireSafeName(fileName);
        Files.deleteIfExists(backupDirPath().resolve(fileName));
    }

    public File getBackupFile(String fileName) {
        requireSafeName(fileName);
        try {
            return backupDirPath().resolve(fileName).toFile();
        } catch (IOException e) {
            // Fall back to a path under the configured dir even if mkdir failed.
            return Paths.get(backupDir, fileName).toFile();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Process runner: captures merged stdout+stderr, enforces a timeout, and
    // destroys the process (never orphans it) if the timeout fires.
    // ──────────────────────────────────────────────────────────────────────
    private ProcResult runProcess(List<String> command, long timeoutSeconds, String tool)
            throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("PGPASSWORD", dbPassword == null ? "" : dbPassword);
        pb.redirectErrorStream(true); // merge stderr into stdout so we capture everything

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            // Most common real-world failure: the binary isn't on PATH.
            throw new IOException(tool + " could not be started. Ensure the PostgreSQL client tools are "
                    + "installed and on PATH, or set app.backup." + (tool.equals("pg_dump") ? "pg-dump" : "pg-restore")
                    + " to the full executable path. Cause: " + e.getMessage(), e);
        }

        // Drain the merged output in a background thread into a bounded buffer so the
        // child can't block on a full pipe, and so we have the real error text to show.
        final StringBuilder out = new StringBuilder();
        final int MAX_CAPTURE = 64 * 1024; // keep memory bounded for very chatty -v output
        Thread drain = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (out) {
                        if (out.length() < MAX_CAPTURE) out.append(line).append('\n');
                    }
                    logger.debug("[{}] {}", tool, line);
                }
            } catch (IOException ignored) {
                // stream closed when the process exits / is destroyed — expected
            }
        }, tool + "-output");
        drain.setDaemon(true);
        drain.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

        if (!finished) {
            // Timed out — destroy the process so we don't orphan it, then report.
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            drain.join(2000);
            String captured;
            synchronized (out) { captured = out.toString(); }
            throw new IOException(tool + " timed out after " + timeoutSeconds + "s and was terminated. "
                    + "Increase app.backup." + (tool.equals("pg_dump") ? "timeout-seconds" : "restore-timeout-seconds")
                    + " for large databases. Output tail:\n" + tail(captured));
        }

        drain.join(2000); // let the drainer finish reading whatever's left
        String captured;
        synchronized (out) { captured = out.toString(); }
        return new ProcResult(process.exitValue(), captured);
    }

    private String failureMessage(String action, String tool, ProcResult r) {
        return action + " failed (" + tool + " exit code " + r.exitCode + "). Output:\n" + tail(r.output);
    }

    /**
     * pg_restore exits 1 for harmless warnings AND for real failures. Distinguish
     * them by scanning for actual error lines. pg_restore/psql prefix genuine
     * problems with "pg_restore: error:" or "... error:"; benign noise uses
     * "warning:". We only treat output with real "error:" lines as a failure.
     */
    private boolean hasRealErrors(String output) {
        if (output == null || output.isEmpty()) return false;
        for (String line : output.split("\n")) {
            String l = line.toLowerCase();
            if (l.contains("error:") || l.contains("fatal:")) return true;
        }
        return false;
    }

    /** Return roughly the last 2KB of output — enough to show the real error line. */
    private String tail(String s) {
        if (s == null || s.isEmpty()) return "(no output captured)";
        int max = 2048;
        return s.length() <= max ? s.strip() : "…" + s.substring(s.length() - max).strip();
    }

    private void requireSafeName(String fileName) {
        if (fileName == null || !SAFE_FILENAME.matcher(fileName).matches()
                || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            throw new IllegalArgumentException("Invalid backup file name: " + fileName);
        }
    }

    private DbConn parseDbUrl(String url) {
        Matcher m = JDBC_PG.matcher(url == null ? "" : url.trim());
        if (m.matches()) {
            String host = m.group(1);
            String port = m.group(2) != null ? m.group(2) : "5432";
            String dbName = m.group(3);
            return new DbConn(host, port, dbName);
        }
        // Fallback to sensible localhost defaults rather than failing outright.
        logger.warn("Could not parse datasource URL '{}' — falling back to localhost:5432/postgres", url);
        return new DbConn("localhost", "5432", "postgres");
    }

    private record DbConn(String host, String port, String dbName) {}

    private static final class ProcResult {
        final int exitCode;
        final String output;
        ProcResult(int exitCode, String output) { this.exitCode = exitCode; this.output = output; }
        String outputTail() {
            if (output == null || output.isEmpty()) return "(no output)";
            int max = 2048;
            return output.length() <= max ? output.strip() : "…" + output.substring(output.length() - max).strip();
        }
    }

    // DTO for UI
    public record BackupFile(String name, long size, long lastModified) {}
}
