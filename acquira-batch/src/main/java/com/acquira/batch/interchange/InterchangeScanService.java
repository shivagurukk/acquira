package com.acquira.batch.interchange;

import com.acquira.common.ingest.IngestRunRecorder;
import com.acquira.common.ingest.IngestSource;
import com.acquira.common.interchange.BaseIIClearingParser;
import com.acquira.common.interchange.BaseIIValidator;
import com.acquira.common.interchange.ClearingTransaction;
import com.acquira.common.interchange.IpmClearingParser;
import com.acquira.common.interchange.IpmMessage;
import com.acquira.common.interchange.IpmValidator;
import com.acquira.common.interchange.ParsedClearingFile;
import com.acquira.common.interchange.ParsedIpmFile;
import com.acquira.common.interchange.Violation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Visa BASE II clearing files from a watched server folder, validates each
 * against the scheme's data-element requirements, and persists the parsed
 * transactions + any data-integrity violations.
 *
 * Folder layout (under {@code acquira.interchange.folder}):
 *   outgoing/   — files submitted to VisaNet
 *   incoming/   — files received from VisaNet
 *   processed/  — archived here after a successful read
 *
 * Two triggers, both landing in {@link #scanDirection}:
 *   • a scheduled poller ({@code acquira.interchange.scheduler.enabled}, default
 *     off), and
 *   • the manual "Scan now" button on the Interchange screen ({@link #scanNow}).
 *
 * Each file read is recorded as an ingest_run (source SERVER_FILE) via the
 * shared {@link IngestRunRecorder}. PANs are never stored in full.
 */
@Service
@Slf4j
public class InterchangeScanService {

    private final BaseIIClearingParser parser;
    private final BaseIIValidator validator;
    private final IpmClearingParser ipmParser;
    private final IpmValidator ipmValidator;
    private final IngestRunRecorder ledger;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    @Value("${acquira.interchange.folder:data/interchange}")
    private String folderRoot;

    /** Scheduler master switch. The @Scheduled method fires on the tick but returns early when off. */
    @Value("${acquira.interchange.scheduler.enabled:false}")
    private boolean schedulerEnabled;

    /**
     * Clearing files carry no Acquira tenant id, so a scan is attributed to this
     * configured tenant. (Filename-based mapping can replace this later.)
     */
    @Value("${acquira.interchange.default-tenant-id:1}")
    private long defaultTenantId;

    public InterchangeScanService(BaseIIClearingParser parser, BaseIIValidator validator,
                                  IpmClearingParser ipmParser, IpmValidator ipmValidator,
                                  IngestRunRecorder ledger, JdbcTemplate jdbc,
                                  PlatformTransactionManager txManager) {
        this.parser = parser;
        this.validator = validator;
        this.ipmParser = ipmParser;
        this.ipmValidator = ipmValidator;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    // ── Triggers ──────────────────────────────────────────────────────────────

    /** Scheduled sweep of both subfolders. Off by default; enable with the flag. */
    @Scheduled(cron = "${acquira.interchange.scheduler.cron:0 */5 * * * ?}")
    public void scheduledScan() {
        if (!schedulerEnabled) {
            log.debug("Interchange scheduled scan skipped — acquira.interchange.scheduler.enabled=false");
            return;
        }
        scanNow("OUTGOING", "scheduler");
        scanNow("INCOMING", "scheduler");
    }

    /** Manual trigger from the Interchange screen. Returns a per-file summary. */
    public List<Map<String, Object>> scanNow(String direction, String triggeredBy) {
        Path dir = subfolder(direction);
        List<Map<String, Object>> results = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            log.info("Interchange folder for {} not found: {}", direction, dir);
            return results;
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) if (Files.isRegularFile(p) && !p.getFileName().toString().startsWith(".")) files.add(p);
        } catch (IOException e) {
            log.warn("Could not list interchange folder {}: {}", dir, e.toString());
            return results;
        }
        files.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
        for (Path f : files) {
            try {
                results.add(scanFile(f, direction, triggeredBy));
            } catch (Exception e) {
                log.error("Interchange scan failed for {}: {}", f.getFileName(), e.toString(), e);
                Map<String, Object> r = new HashMap<>();
                r.put("file", f.getFileName().toString());
                r.put("status", "ERROR");
                r.put("error", e.getMessage());
                results.add(r);
            }
        }
        return results;
    }

    // ── One file ────────────────────────────────────────────────────────────

    private Map<String, Object> scanFile(Path path, String direction, String triggeredBy) throws IOException {
        String fileName = path.getFileName().toString();
        byte[] raw = Files.readAllBytes(path);
        long bytes = raw.length;
        String sha = sha256Bytes(raw);

        Long runId = ledger.openRun(defaultTenantId, IngestSource.SERVER_FILE, null,
                "interchangeScan", path.toString(), "APPEND", triggeredBy, null);

        Map<String, Object> r = "MASTERCARD".equals(detectScheme(raw, fileName))
                ? scanIpm(raw, fileName, direction, bytes, sha, triggeredBy)
                : scanVisa(raw, fileName, direction, bytes, sha, triggeredBy);

        ledger.updateCounts(runId, (long) r.get("records"), (long) r.get("records"), null,
                null, ((Number) r.get("errors")).longValue(), null, null, null, null, null);
        ledger.closeRun(runId, "COMPLETED", null);
        archive(path);
        return r;
    }

    private Map<String, Object> scanVisa(byte[] raw, String fileName, String direction,
                                         long bytes, String sha, String triggeredBy) {
        String content = new String(raw, StandardCharsets.ISO_8859_1);
        ParsedClearingFile pf = parser.parse(content, fileName, direction);
        List<Violation> violations = validator.validate(pf);
        long fileId = tx.execute(s -> persist(pf, violations, direction, fileName, bytes, sha, triggeredBy));

        int errors = countSeverity(violations, Violation.Severity.ERROR);
        Map<String, Object> r = new HashMap<>();
        r.put("file", fileName); r.put("fileId", fileId); r.put("scheme", "VISA"); r.put("direction", direction);
        r.put("records", (long) pf.records.size()); r.put("transactions", pf.transactions.size());
        r.put("batches", pf.batches.size()); r.put("violations", violations.size()); r.put("errors", errors);
        r.put("status", errors > 0 ? "INVALID" : "PARSED");
        log.info("Interchange VISA {} {} — {} txns, {} records, {} violations ({} errors)",
                direction, fileName, pf.transactions.size(), pf.records.size(), violations.size(), errors);
        return r;
    }

    private Map<String, Object> scanIpm(byte[] raw, String fileName, String direction,
                                        long bytes, String sha, String triggeredBy) {
        ParsedIpmFile pf = ipmParser.parse(raw, fileName, direction);
        List<Violation> violations = ipmValidator.validate(pf);
        long fileId = tx.execute(s -> persistIpm(pf, violations, direction, fileName, bytes, sha, triggeredBy));

        int errors = countSeverity(violations, Violation.Severity.ERROR);
        Map<String, Object> r = new HashMap<>();
        r.put("file", fileName); r.put("fileId", fileId); r.put("scheme", "MASTERCARD"); r.put("direction", direction);
        r.put("records", (long) pf.recordCount); r.put("transactions", pf.presentments().size());
        r.put("batches", 0); r.put("violations", violations.size()); r.put("errors", errors);
        r.put("status", errors > 0 ? "INVALID" : "PARSED");
        log.info("Interchange MASTERCARD {} {} — {} presentments, {} records, {} violations ({} errors)",
                direction, fileName, pf.presentments().size(), pf.recordCount, violations.size(), errors);
        return r;
    }

    /** VISA BASE II CTF is ASCII fixed-width; Mastercard IPM is EBCDIC binary. */
    private String detectScheme(byte[] raw, String fileName) {
        String fn = fileName.toUpperCase();
        if (fn.startsWith("MCI") || fn.contains(".IPM")) return "MASTERCARD";
        if (fn.endsWith(".CTF") || fn.startsWith("OBA")) return "VISA";
        // Content: IPM's first record is a 4-byte length then an EBCDIC MTI (F0-F9).
        if (raw.length >= 8) {
            int b4 = raw[4] & 0xFF, b5 = raw[5] & 0xFF;
            if (b4 >= 0xF0 && b4 <= 0xF9 && b5 >= 0xF0 && b5 <= 0xF9) return "MASTERCARD";
        }
        // Otherwise printable-ASCII with a leading TC digit -> VISA.
        return "VISA";
    }

    private long persist(ParsedClearingFile pf, List<Violation> violations, String direction,
                         String fileName, long bytes, String sha, String triggeredBy) {
        int errors = countSeverity(violations, Violation.Severity.ERROR);
        int warns = countSeverity(violations, Violation.Severity.WARN);
        boolean balanced = violations.stream().noneMatch(v -> v.category == Violation.Category.BALANCING);

        Long fileId = jdbc.queryForObject(
                "INSERT INTO interchange_file (tenant_id, ingest_run_id, scheme, direction, format, " +
                "file_name, file_bytes, file_sha256, record_bytes, record_count, batch_count, " +
                "transaction_count, monetary_count, processing_date, settlement_date, center_info_block, " +
                "test_file, status, violation_count, error_count, warning_count, balanced, triggered_by) " +
                "VALUES (?,?, 'VISA', ?, ?, ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                Long.class,
                defaultTenantId, null, direction, pf.format, fileName, bytes, sha, pf.recordBytes,
                (long) pf.records.size(), pf.batches.size(), (long) pf.transactions.size(), pf.monetaryCount(),
                sqlDate(pf.processingDate), sqlDate(pf.settlementDate), pf.centerInfoBlock, pf.testFile,
                errors > 0 ? "INVALID" : "PARSED", violations.size(), errors, warns, balanced, triggeredBy);

        // Transactions — capture generated id per seqInFile to link violations.
        Map<Long, Long> txnIdBySeq = new HashMap<>();
        for (ClearingTransaction t : pf.transactions) {
            Long txnId = jdbc.queryForObject(
                    "INSERT INTO interchange_transaction (file_id, tenant_id, seq_in_file, batch_number, " +
                    "transaction_code, tc_qualifier, tcr_present, account_masked, acquirer_ref_number, " +
                    "acquirer_bid, purchase_date, destination_amount, destination_ccy, source_amount, " +
                    "source_ccy, merchant_name, merchant_country, mcc, fee_program_ind, reimbursement_attr, " +
                    "monetary, violation_count) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                    Long.class,
                    fileId, defaultTenantId, t.seqInFile, t.batchNumber, t.tc, t.qualifier, t.tcrPresent(),
                    t.accountMasked, t.acquirerRefNumber, t.acquirerBid, t.purchaseDate,
                    t.destinationAmount, t.destinationCcy, t.sourceAmount, t.sourceCcy,
                    t.merchantName, t.merchantCountry, t.mcc, t.feeProgramIndicator, t.reimbursementAttr,
                    t.monetary, 0);
            txnIdBySeq.put(t.seqInFile, txnId);
        }

        // Violations.
        Map<Long, Integer> viaCountByTxn = new HashMap<>();
        for (Violation v : violations) {
            Long txnId = v.transactionSeq == null ? null : txnIdBySeq.get(v.transactionSeq);
            if (txnId != null) viaCountByTxn.merge(txnId, 1, Integer::sum);
            jdbc.update(
                    "INSERT INTO interchange_violation (file_id, transaction_id, tenant_id, record_no, " +
                    "transaction_code, tcr, field_name, field_position, category, severity, requiredness, " +
                    "rule_code, message, expected_value, actual_value) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    fileId, txnId, defaultTenantId, v.recordNo, v.tc, v.tcr, v.fieldName, v.fieldPosition,
                    v.category.name(), v.severity.name(), v.requiredness, v.ruleCode, v.message,
                    trunc(v.expected, 64), trunc(v.actual, 64));
        }
        viaCountByTxn.forEach((txnId, c) ->
                jdbc.update("UPDATE interchange_transaction SET violation_count = ? WHERE id = ?", c, txnId));

        return fileId;
    }

    private long persistIpm(ParsedIpmFile pf, List<Violation> violations, String direction,
                            String fileName, long bytes, String sha, String triggeredBy) {
        int errors = countSeverity(violations, Violation.Severity.ERROR);
        int warns = countSeverity(violations, Violation.Severity.WARN);
        List<IpmMessage> presentments = pf.presentments();

        Long fileId = jdbc.queryForObject(
                "INSERT INTO interchange_file (tenant_id, ingest_run_id, scheme, direction, format, " +
                "file_name, file_bytes, file_sha256, record_bytes, record_count, batch_count, " +
                "transaction_count, monetary_count, processing_date, settlement_date, center_info_block, " +
                "test_file, status, violation_count, error_count, warning_count, balanced, triggered_by) " +
                "VALUES (?,?, 'MASTERCARD', ?, 'IPM', ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                Long.class,
                defaultTenantId, null, direction, fileName, bytes, sha, 0,
                (long) pf.recordCount, 0, (long) presentments.size(), (long) presentments.size(),
                null, null, null, false, errors > 0 ? "INVALID" : "PARSED",
                violations.size(), errors, warns, true, triggeredBy);

        Map<Long, Long> txnIdBySeq = new HashMap<>();
        for (IpmMessage m : presentments) {
            Long txnId = jdbc.queryForObject(
                    "INSERT INTO interchange_transaction (file_id, tenant_id, seq_in_file, batch_number, " +
                    "transaction_code, tc_qualifier, tcr_present, account_masked, acquirer_ref_number, " +
                    "acquirer_bid, purchase_date, destination_amount, destination_ccy, source_amount, " +
                    "source_ccy, merchant_name, merchant_country, mcc, fee_program_ind, reimbursement_attr, " +
                    "monetary, violation_count) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id",
                    Long.class,
                    fileId, defaultTenantId, m.recordNo, null, m.mti, null, m.deList(),
                    m.get(2), trunc(m.get(31), 23), null, mmddFromIpm(m.get(12)),
                    null, null, ipmAmount(m.get(4)), m.get(49),
                    trunc(m.get(43), 25), null, m.get(26), null, null, true, 0);
            txnIdBySeq.put(m.recordNo, txnId);
        }

        Map<Long, Integer> viaCountByTxn = new HashMap<>();
        for (Violation v : violations) {
            Long txnId = v.transactionSeq == null ? null : txnIdBySeq.get(v.transactionSeq);
            if (txnId != null) viaCountByTxn.merge(txnId, 1, Integer::sum);
            jdbc.update(
                    "INSERT INTO interchange_violation (file_id, transaction_id, tenant_id, record_no, " +
                    "transaction_code, tcr, field_name, field_position, category, severity, requiredness, " +
                    "rule_code, message, expected_value, actual_value) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    fileId, txnId, defaultTenantId, v.recordNo, v.tc, v.tcr, v.fieldName, v.fieldPosition,
                    v.category.name(), v.severity.name(), v.requiredness, v.ruleCode, v.message,
                    trunc(v.expected, 64), trunc(v.actual, 64));
        }
        viaCountByTxn.forEach((txnId, c) ->
                jdbc.update("UPDATE interchange_transaction SET violation_count = ? WHERE id = ?", c, txnId));

        return fileId;
    }

    /** DE 4 is a 12-digit minor-units amount (2 implied decimals). */
    private java.math.BigDecimal ipmAmount(String de4) {
        if (de4 == null) return null;
        String d = de4.replaceAll("\\D", "");
        return d.isEmpty() ? null : new java.math.BigDecimal(d).movePointLeft(2);
    }

    /** DE 12 is YYMMDDhhmmss; take MMDD to match the Visa purchase_date column. */
    private String mmddFromIpm(String de12) {
        if (de12 == null) return null;
        String d = de12.replaceAll("\\D", "");
        return d.length() >= 6 ? d.substring(2, 6) : null;
    }

    // ── Folder helpers ────────────────────────────────────────────────────────

    private Path subfolder(String direction) {
        Path base = validateAllowedRoot(folderRoot);
        if (base == null) return null;
        return base.resolve("INCOMING".equalsIgnoreCase(direction) ? "incoming" : "outgoing");
    }

    /** Confine the configured root defensively (no traversal, must resolve). */
    private Path validateAllowedRoot(String root) {
        if (root == null || root.isBlank() || root.contains("..")) return null;
        try {
            Path p = Paths.get(root);
            Files.createDirectories(p);                 // create the drop folder on first use
            Files.createDirectories(p.resolve("outgoing"));
            Files.createDirectories(p.resolve("incoming"));
            return p.toRealPath();
        } catch (IOException e) {
            log.warn("Interchange folder root unusable: {} ({})", root, e.toString());
            return null;
        }
    }

    private void archive(Path path) {
        try {
            Path base = path.getParent().getParent(); // .../interchange
            String stamp = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
            Path dest = base.resolve("processed").resolve(stamp);
            Files.createDirectories(dest);
            Files.move(path, dest.resolve(path.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Could not archive processed interchange file {}: {}", path.getFileName(), e.toString());
        }
    }

    // ── small utils ────────────────────────────────────────────────────────

    private int countSeverity(List<Violation> vs, Violation.Severity s) {
        return (int) vs.stream().filter(v -> v.severity == s).count();
    }

    private Date sqlDate(LocalDate d) { return d == null ? null : Date.valueOf(d); }

    private String trunc(String s, int n) { return s == null ? null : (s.length() <= n ? s : s.substring(0, n)); }

    private String sha256Bytes(byte[] content) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
