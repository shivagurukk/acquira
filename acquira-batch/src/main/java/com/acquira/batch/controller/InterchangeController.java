package com.acquira.batch.controller;

import com.acquira.batch.interchange.InterchangeScanService;
import com.acquira.common.config.TenantContext;
import com.acquira.common.interchange.DimpEdit;
import com.acquira.common.interchange.DimpEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Read + scan API behind the INTERCHANGE screens (/interchange/outgoing and
 * /interchange/incoming). Lists parsed Visa BASE II clearing files, drills into
 * one file's transactions and data-integrity violations, and triggers a manual
 * folder scan.
 *
 * Gated by @menuAccess — a caller granted either Interchange screen may use it,
 * and results are tenant-scoped (super-admins see all tenants), mirroring
 * IngestTrustController.
 */
@RestController
@RequestMapping("/api/interchange")
@PreAuthorize("@menuAccess.canAccess('/interchange/outgoing') or @menuAccess.canAccess('/interchange/incoming') or @menuAccess.canAccess('/interchange/dimp')")
public class InterchangeController {

    private final JdbcTemplate jdbc;
    private final InterchangeScanService scanService;
    private final DimpEngine dimp;

    public InterchangeController(JdbcTemplate jdbc, InterchangeScanService scanService, DimpEngine dimp) {
        this.jdbc = jdbc;
        this.scanService = scanService;
        this.dimp = dimp;
    }

    // ── List files for a direction ────────────────────────────────────────────

    @GetMapping("/files")
    public ResponseEntity<List<Map<String, Object>>> files(
            @RequestParam(defaultValue = "OUTGOING") String direction,
            @RequestParam(defaultValue = "100") int limit) {
        String dir = normalizeDirection(direction);
        Long tenant = scopeTenant();
        int lim = Math.min(Math.max(limit, 1), 500);

        StringBuilder sql = new StringBuilder(
                "SELECT id, file_name AS \"fileName\", scheme, direction, format, " +
                "record_count AS \"recordCount\", batch_count AS \"batchCount\", " +
                "transaction_count AS \"transactionCount\", monetary_count AS \"monetaryCount\", " +
                "processing_date AS \"processingDate\", settlement_date AS \"settlementDate\", " +
                "center_info_block AS \"centerInfoBlock\", test_file AS \"testFile\", status, " +
                "violation_count AS \"violationCount\", error_count AS \"errorCount\", " +
                "warning_count AS \"warningCount\", balanced, parsed_at AS \"parsedAt\" " +
                "FROM interchange_file WHERE direction = ? ");
        List<Object> args = new ArrayList<>();
        args.add(dir);
        if (tenant != null) { sql.append("AND tenant_id = ? "); args.add(tenant); }
        sql.append("ORDER BY parsed_at DESC LIMIT ").append(lim);

        return ResponseEntity.ok(jdbc.queryForList(sql.toString(), args.toArray()));
    }

    // ── One file's detail ─────────────────────────────────────────────────────

    @GetMapping("/files/{id}")
    public ResponseEntity<Map<String, Object>> fileDetail(@PathVariable long id) {
        Long tenant = scopeTenant();
        List<Object> args = new ArrayList<>();
        args.add(id);
        String tenantClause = "";
        if (tenant != null) { tenantClause = " AND tenant_id = ?"; args.add(tenant); }

        List<Map<String, Object>> file = jdbc.queryForList(
                "SELECT id, file_name AS \"fileName\", scheme, direction, format, " +
                "record_count AS \"recordCount\", batch_count AS \"batchCount\", " +
                "transaction_count AS \"transactionCount\", monetary_count AS \"monetaryCount\", " +
                "processing_date AS \"processingDate\", settlement_date AS \"settlementDate\", " +
                "center_info_block AS \"centerInfoBlock\", test_file AS \"testFile\", status, " +
                "violation_count AS \"violationCount\", error_count AS \"errorCount\", " +
                "warning_count AS \"warningCount\", balanced, parsed_at AS \"parsedAt\" " +
                "FROM interchange_file WHERE id = ?" + tenantClause, args.toArray());
        if (file.isEmpty()) return ResponseEntity.notFound().build();

        List<Map<String, Object>> txns = jdbc.queryForList(
                "SELECT id, seq_in_file AS \"seqInFile\", batch_number AS \"batchNumber\", " +
                "transaction_code AS \"transactionCode\", tcr_present AS \"tcrPresent\", " +
                "account_masked AS \"accountMasked\", acquirer_ref_number AS \"acquirerRefNumber\", " +
                "purchase_date AS \"purchaseDate\", destination_amount AS \"destinationAmount\", " +
                "destination_ccy AS \"destinationCcy\", source_amount AS \"sourceAmount\", " +
                "source_ccy AS \"sourceCcy\", merchant_name AS \"merchantName\", " +
                "merchant_country AS \"merchantCountry\", mcc, fee_program_ind AS \"feeProgramInd\", " +
                "reimbursement_attr AS \"reimbursementAttr\", violation_count AS \"violationCount\" " +
                "FROM interchange_transaction WHERE file_id = ? ORDER BY seq_in_file", id);

        List<Map<String, Object>> violations = jdbc.queryForList(
                "SELECT id, transaction_id AS \"transactionId\", record_no AS \"recordNo\", " +
                "transaction_code AS \"transactionCode\", tcr, field_name AS \"fieldName\", " +
                "field_position AS \"fieldPosition\", category, severity, requiredness, " +
                "rule_code AS \"ruleCode\", message, expected_value AS \"expectedValue\", " +
                "actual_value AS \"actualValue\" FROM interchange_violation WHERE file_id = ? " +
                "ORDER BY CASE severity WHEN 'ERROR' THEN 0 WHEN 'WARN' THEN 1 ELSE 2 END, record_no", id);

        Map<String, Object> out = new HashMap<>();
        out.put("file", file.get(0));
        out.put("transactions", txns);
        out.put("violations", violations);
        return ResponseEntity.ok(out);
    }

    // ── Manual scan ───────────────────────────────────────────────────────────

    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> scan(@RequestParam(defaultValue = "OUTGOING") String direction) {
        String dir = normalizeDirection(direction);
        String who = currentUser();
        List<Map<String, Object>> results = scanService.scanNow(dir, who);
        Map<String, Object> out = new HashMap<>();
        out.put("direction", dir);
        out.put("scanned", results.size());
        out.put("results", results);
        return ResponseEntity.ok(out);
    }

    // ── DIMP dashboard ────────────────────────────────────────────────────────

    /** The full 24-edit DIMP catalogue with each edit's evaluability status. */
    @GetMapping("/dimp/catalogue")
    public ResponseEntity<List<Map<String, Object>>> dimpCatalogue() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DimpEdit e : dimp.catalogue()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("editNumber", e.editNumber);
            m.put("name", e.name);
            m.put("title", e.title);
            m.put("billingCode", e.billingCode);
            m.put("status", e.status.name());
            m.put("field", e.field);
            m.put("description", e.description);
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    /** Data Integrity assessment: per-edit counts/rates and a by-month trend. */
    @GetMapping("/dimp/assessment")
    public ResponseEntity<Map<String, Object>> dimpAssessment() {
        Long tenant = scopeTenant();
        Object[] tArg = tenant == null ? new Object[0] : new Object[]{ tenant };

        long presentments = orZero(jdbc.queryForObject(
                "SELECT COALESCE(SUM(transaction_count),0) FROM interchange_file " +
                "WHERE scheme = 'MASTERCARD'" + tclause(tenant, "tenant_id"), Long.class, tArg));
        long files = orZero(jdbc.queryForObject(
                "SELECT COUNT(*) FROM interchange_file WHERE scheme = 'MASTERCARD'" + tclause(tenant, "tenant_id"),
                Long.class, tArg));

        // Per-edit violation counts (rule_code like DIMP_E<n>_...).
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT rule_code, COUNT(*) AS c FROM interchange_violation WHERE category = 'DIMP'" +
                tclause(tenant, "tenant_id") + " GROUP BY rule_code", tArg);
        Map<String, Long> countByEdit = new HashMap<>();
        for (Map<String, Object> r : rows) {
            String edit = editNumberOf((String) r.get("rule_code"));
            if (edit != null) countByEdit.merge(edit, ((Number) r.get("c")).longValue(), Long::sum);
        }

        List<Map<String, Object>> byEdit = new ArrayList<>();
        long dimpTotal = 0, editsFired = 0;
        for (DimpEdit e : dimp.catalogue()) {
            long c = countByEdit.getOrDefault(e.editNumber, 0L);
            dimpTotal += c;
            if (c > 0) editsFired++;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("editNumber", e.editNumber);
            m.put("name", e.name);
            m.put("title", e.title);
            m.put("billingCode", e.billingCode);
            m.put("status", e.status.name());
            m.put("field", e.field);
            m.put("count", c);
            m.put("rate", presentments > 0 ? Math.round(c * 10000.0 / presentments) / 100.0 : 0.0);
            byEdit.add(m);
        }
        byEdit.sort((a, b) -> Long.compare((long) b.get("count"), (long) a.get("count")));

        List<Map<String, Object>> byMonth = jdbc.queryForList(
                "SELECT to_char(f.parsed_at,'YYYY-MM') AS month, " +
                "COUNT(v.id) AS violations, COUNT(DISTINCT f.id) AS files " +
                "FROM interchange_file f " +
                "LEFT JOIN interchange_violation v ON v.file_id = f.id AND v.category = 'DIMP' " +
                "WHERE f.scheme = 'MASTERCARD'" + tclause(tenant, "f.tenant_id") +
                " GROUP BY 1 ORDER BY 1", tArg);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("files", files);
        summary.put("presentments", presentments);
        summary.put("dimpViolations", dimpTotal);
        summary.put("editsFired", editsFired);
        summary.put("editsTotal", (long) dimp.catalogue().size());
        summary.put("editsFileChecked", dimp.catalogue().stream()
                .filter(e -> e.status == DimpEdit.Status.FILE).count());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("byEdit", byEdit);
        out.put("byMonth", byMonth);
        return ResponseEntity.ok(out);
    }

    private String tclause(Long tenant, String col) { return tenant == null ? "" : " AND " + col + " = ?"; }

    private long orZero(Long v) { return v == null ? 0L : v; }

    /** Parse the edit number out of a rule_code like "DIMP_E21_High_Risk...". */
    private String editNumberOf(String ruleCode) {
        if (ruleCode == null || !ruleCode.startsWith("DIMP_E")) return null;
        int i = 6, j = 6;
        while (j < ruleCode.length() && Character.isDigit(ruleCode.charAt(j))) j++;
        return j > i ? ruleCode.substring(i, j) : null;
    }

    // ── Scoping helpers ───────────────────────────────────────────────────────

    private String normalizeDirection(String d) {
        return "INCOMING".equalsIgnoreCase(d) ? "INCOMING" : "OUTGOING";
    }

    private boolean isSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPER_ADMIN".equals(a.getAuthority()));
    }

    /** Tenant to filter by, or null for a super-admin (all tenants). Fails closed. */
    private Long scopeTenant() {
        if (isSuperAdmin()) return null;
        Long own = TenantContext.getCurrentTenant();
        return own == null ? -1L : own;
    }

    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }
}
