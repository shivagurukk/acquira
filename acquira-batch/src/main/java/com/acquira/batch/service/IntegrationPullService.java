package com.acquira.batch.service;

import com.acquira.common.model.*;
import com.acquira.common.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Core DB Pull service — fetches data from external databases
 * and inserts into the SAME staging tables used by file upload.
 *
 * After staging, it triggers the SAME processing pipeline:
 *   MERCHANT type  → upsertDimensions + activitySummary
 *   TRANSACTION type → stagingToFact + summaries + metrics
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IntegrationPullService {

    private final IntegrationRunLogRepository runLogRepo;
    private final IntegrationScheduleRepository scheduleRepo;
    private final JdbcTemplate jdbcTemplate;
    private final ManualIngestionService manualIngestionService;

    /**
     * Execute a DB pull for a given report configuration.
     * This is the main entry point — called by Run Now, Scheduler, and Retry.
     */
    @Async
    public void executePull(IntegrationReport report, IntegrationSchedule schedule,
                            IntegrationRunLog.TriggerType triggerType,
                            LocalDate dateFrom, LocalDate dateTo,
                            int attemptNumber) {

        Long tenantId = report.getTenantId();
        IntegrationConnection conn = report.getConnection();

        // 1. Create run log
        IntegrationRunLog runLog = new IntegrationRunLog();
        runLog.setTenantId(tenantId);
        runLog.setReport(report);
        runLog.setSchedule(schedule);
        runLog.setTriggerType(triggerType);
        runLog.setStatus(IntegrationRunLog.Status.RUNNING);
        runLog.setAttemptNumber(attemptNumber);
        runLog.setMaxRetries(conn.getMaxRetries() != null ? conn.getMaxRetries() : 3);
        runLog.setStartTime(LocalDateTime.now());
        runLog.setDateRangeFrom(dateFrom);
        runLog.setDateRangeTo(dateTo);
        runLogRepo.save(runLog);

        long startMs = System.currentTimeMillis();

        try {
            // 2. Build params
            Map<String, Object> params = buildParams(dateFrom, dateTo);

            // 3. Fetch from external DB
            log.info("[Integration] Pulling {} report '{}' for tenant {} (attempt {}/{})",
                    report.getReportType(), report.getName(), tenantId, attemptNumber, runLog.getMaxRetries());

            List<Map<String, Object>> rawRows = executeExternalQuery(conn, report.getSqlText(), params);
            runLog.setRowsFetched(rawRows.size());

            if (rawRows.isEmpty()) {
                log.warn("[Integration] No rows returned for report '{}', tenant {}", report.getName(), tenantId);
                runLog.setStatus(IntegrationRunLog.Status.SUCCESS);
                runLog.setRowsProcessed(0);
                finishRunLog(runLog, startMs);
                return;
            }

            // 4. Parse column mapping
            Map<String, String> columnMap = parseColumnMapping(report.getColumnMapping());

            // 5. Insert into staging table based on report type
            int processed;
            if (report.getReportType() == IntegrationReport.ReportType.MERCHANT) {
                processed = insertMerchantStaging(rawRows, columnMap, tenantId);
            } else {
                processed = insertTransactionStaging(rawRows, columnMap, tenantId);
            }

            runLog.setRowsProcessed(processed);
            runLog.setRowsFailed(rawRows.size() - processed);

            // 6. Trigger same processing pipeline as file upload
            triggerProcessingPipeline(report.getReportType(), tenantId);

            // 7. Success
            runLog.setStatus(IntegrationRunLog.Status.SUCCESS);
            log.info("[Integration] SUCCESS — '{}' for tenant {}: {} rows fetched, {} processed",
                    report.getName(), tenantId, rawRows.size(), processed);

        } catch (Exception e) {
            log.error("[Integration] FAILED — '{}' for tenant {}: {}", report.getName(), tenantId, e.getMessage(), e);
            runLog.setStatus(IntegrationRunLog.Status.FAILED);
            runLog.setErrorMessage(e.getMessage());

            // Schedule retry if attempts remaining
            if (attemptNumber < runLog.getMaxRetries()) {
                scheduleRetry(report, schedule, dateFrom, dateTo, attemptNumber + 1);
                runLog.setStatus(IntegrationRunLog.Status.RETRYING);
            }
        } finally {
            finishRunLog(runLog, startMs);
        }

        // Update schedule last run time
        if (schedule != null) {
            schedule.setLastRunAt(LocalDateTime.now());
            scheduleRepo.save(schedule);
        }
    }

    /**
     * Execute query against external database.
     */
    private List<Map<String, Object>> executeExternalQuery(IntegrationConnection config, String sql, Map<String, Object> params) {
        String url = config.getJdbcUrl();
        int timeout = config.getTimeoutSeconds() != null ? config.getTimeoutSeconds() : 30;

        log.info("[Integration] Connecting to {} ({}) at {}:{}", config.getName(), config.getDbType(), config.getHost(), config.getPort());

        Properties props = new Properties();
        props.setProperty("user", config.getUsername());
        props.setProperty("password", config.getEncryptedPassword()); // TODO: decrypt
        props.setProperty("loginTimeout", String.valueOf(timeout));

        try (Connection conn = DriverManager.getConnection(url, props)) {
            conn.setReadOnly(true); // Safety: prevent accidental writes to external DB
            conn.setNetworkTimeout(java.util.concurrent.Executors.newSingleThreadExecutor(), timeout * 1000);

            // Named param replacement
            List<Object> values = new ArrayList<>();
            String parsedSql = sql;
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                String placeholder = ":" + entry.getKey();
                if (parsedSql.contains(placeholder)) {
                    parsedSql = parsedSql.replace(placeholder, "?");
                    values.add(entry.getValue());
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(parsedSql)) {
                ps.setQueryTimeout(timeout);
                for (int i = 0; i < values.size(); i++) {
                    ps.setObject(i + 1, values.get(i));
                }

                try (ResultSet rs = ps.executeQuery()) {
                    return mapResultSet(rs);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("External DB query failed for '" + config.getName() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Insert fetched rows into stg_merchant_master_raw.
     */
    private int insertMerchantStaging(List<Map<String, Object>> rows, Map<String, String> columnMap, Long tenantId) {
        // Clear existing staging for this tenant
        jdbcTemplate.update("DELETE FROM stg_merchant_master_raw WHERE tenant_id = ?", tenantId);

        String sql = """
            INSERT INTO stg_merchant_master_raw (
                tenant_id, institution_code, institution_name, entity_internal_id, entity_name, entity_code,
                aggregator_internal_id, aggregator_name, aggregator_code,
                merchant_internal_id, mid, merchant_name, merchant_status,
                merchant_store_internal_id, sid, store_legal_name, store_name, store_status,
                business_type, business_mcc, vat_number,
                primary_contact_person, primary_contact_number, primary_contact_email,
                address, city, state, postal_code,
                risk_level, product, date_of_onboarding,
                load_time
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
        """;

        int count = 0;
        for (Map<String, Object> row : rows) {
            try {
                jdbcTemplate.update(sql,
                    tenantId,
                    getMapped(row, columnMap, "institution_code"),
                    getMapped(row, columnMap, "institution_name"),
                    getMapped(row, columnMap, "entity_internal_id"),
                    getMapped(row, columnMap, "entity_name"),
                    getMapped(row, columnMap, "entity_code"),
                    getMapped(row, columnMap, "aggregator_internal_id"),
                    getMapped(row, columnMap, "aggregator_name"),
                    getMapped(row, columnMap, "aggregator_code"),
                    getMapped(row, columnMap, "merchant_internal_id"),
                    getMapped(row, columnMap, "mid"),
                    getMapped(row, columnMap, "merchant_name"),
                    getMapped(row, columnMap, "merchant_status"),
                    getMapped(row, columnMap, "merchant_store_internal_id"),
                    getMapped(row, columnMap, "sid"),
                    getMapped(row, columnMap, "store_legal_name"),
                    getMapped(row, columnMap, "store_name"),
                    getMapped(row, columnMap, "store_status"),
                    getMapped(row, columnMap, "business_type"),
                    getMapped(row, columnMap, "business_mcc"),
                    getMapped(row, columnMap, "vat_number"),
                    getMapped(row, columnMap, "primary_contact_person"),
                    getMapped(row, columnMap, "primary_contact_number"),
                    getMapped(row, columnMap, "primary_contact_email"),
                    getMapped(row, columnMap, "address"),
                    getMapped(row, columnMap, "city"),
                    getMapped(row, columnMap, "state"),
                    getMapped(row, columnMap, "postal_code"),
                    getMapped(row, columnMap, "risk_level"),
                    getMapped(row, columnMap, "product"),
                    getMapped(row, columnMap, "date_of_onboarding")
                );
                count++;
            } catch (Exception e) {
                log.warn("[Integration] Skipped merchant row: {}", e.getMessage());
            }
        }
        return count;
    }

    /**
     * Insert fetched rows into stg_trnx_raw.
     */
    private int insertTransactionStaging(List<Map<String, Object>> rows, Map<String, String> columnMap, Long tenantId) {
        // Clear existing staging for this tenant
        jdbcTemplate.update("DELETE FROM stg_trnx_raw WHERE tenant_id = ?", tenantId);

        String sql = """
            INSERT INTO stg_trnx_raw (
                tenant_id, entity_name, aggregator_internal_id, aggregator_name, aggregator_code,
                mid, merchant_internal_id, merchant_name,
                sid, merchant_store_internal_id, store_name,
                tid, arn, rrn_number, card_number, auth_code,
                payment_date, transaction_date, batch_number, transaction_type,
                card_scheme, card_type, dcc,
                txn_currency, txn_currency_amount, store_base_currency, store_base_currency_amount,
                msf, vat, total_amount_settled, interchange_fee, destination,
                load_time
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
        """;

        int count = 0;
        for (Map<String, Object> row : rows) {
            try {
                jdbcTemplate.update(sql,
                    tenantId,
                    getMapped(row, columnMap, "entity_name"),
                    getMapped(row, columnMap, "aggregator_internal_id"),
                    getMapped(row, columnMap, "aggregator_name"),
                    getMapped(row, columnMap, "aggregator_code"),
                    getMapped(row, columnMap, "mid"),
                    getMapped(row, columnMap, "merchant_internal_id"),
                    getMapped(row, columnMap, "merchant_name"),
                    getMapped(row, columnMap, "sid"),
                    getMapped(row, columnMap, "merchant_store_internal_id"),
                    getMapped(row, columnMap, "store_name"),
                    getMapped(row, columnMap, "tid"),
                    getMapped(row, columnMap, "arn"),
                    getMapped(row, columnMap, "rrn_number"),
                    getMapped(row, columnMap, "card_number"),
                    getMapped(row, columnMap, "auth_code"),
                    parseTimestamp(getMapped(row, columnMap, "payment_date")),
                    parseTimestamp(getMapped(row, columnMap, "transaction_date")),
                    getMapped(row, columnMap, "batch_number"),
                    getMapped(row, columnMap, "transaction_type"),
                    getMapped(row, columnMap, "card_scheme"),
                    getMapped(row, columnMap, "card_type"),
                    parseBoolean(getMapped(row, columnMap, "dcc")),
                    getMapped(row, columnMap, "txn_currency"),
                    parseBigDecimal(getMapped(row, columnMap, "txn_currency_amount")),
                    getMapped(row, columnMap, "store_base_currency"),
                    parseBigDecimal(getMapped(row, columnMap, "store_base_currency_amount")),
                    parseBigDecimal(getMapped(row, columnMap, "msf")),
                    parseBigDecimal(getMapped(row, columnMap, "vat")),
                    parseBigDecimal(getMapped(row, columnMap, "total_amount_settled")),
                    parseBigDecimal(getMapped(row, columnMap, "interchange_fee")),
                    getMapped(row, columnMap, "destination")
                );
                count++;
            } catch (Exception e) {
                log.warn("[Integration] Skipped transaction row: {}", e.getMessage());
            }
        }
        return count;
    }

    /**
     * Trigger the SAME processing pipeline that file upload uses.
     * This is the key architectural decision — DB pull feeds staging,
     * then the existing batch steps process it identically.
     */
    private void triggerProcessingPipeline(IntegrationReport.ReportType reportType, Long tenantId) {
        log.info("[Integration] Triggering {} processing pipeline for tenant {}", reportType, tenantId);

        if (reportType == IntegrationReport.ReportType.MERCHANT) {
            // Same as what MerchantMasterJobConfig does after file ingestion:
            // upsertDimensions + populateActivitySummary
            executeMerchantPostProcessing(tenantId);
        } else {
            // Same as what TransactionJobConfig does after file ingestion:
            // stagingToFact + populateSummary + businessMetrics + dashboardMetrics
            executeTransactionPostProcessing(tenantId);
        }
    }

    /**
     * Merchant post-processing — mirrors upsertDimensionsTasklet + populateActivitySummaryTasklet.
     * Uses same SQL as MerchantMasterJobConfig but runs via JdbcTemplate directly.
     */
    private void executeMerchantPostProcessing(Long tenantId) {
        String tId = String.valueOf(tenantId);

        // Upsert Merchants
        jdbcTemplate.execute("""
            INSERT INTO dim_merchant (tenant_id, internal_id, mid, name, status, created_date, sales_user_id, sales_email, referral_partner, risk_level)
            SELECT CAST(%s AS INTEGER), COALESCE(merchant_internal_id, mid), mid,
                MAX(merchant_name), COALESCE(MAX(merchant_status), 'ACTIVE'), MAX(created_date),
                MAX(sales_user_id), MAX(sales_user_email), MAX(referral_partner), MAX(risk_level)
            FROM stg_merchant_master_raw WHERE tenant_id = %s
            GROUP BY tenant_id, COALESCE(merchant_internal_id, mid), mid
            ON CONFLICT (tenant_id, internal_id) DO UPDATE SET
                name = EXCLUDED.name, status = EXCLUDED.status,
                sales_user_id = EXCLUDED.sales_user_id, sales_email = EXCLUDED.sales_email,
                referral_partner = EXCLUDED.referral_partner, risk_level = EXCLUDED.risk_level
        """.formatted(tId, tId));

        // Upsert Stores
        jdbcTemplate.execute("""
            INSERT INTO dim_store (tenant_id, internal_id, merchant_id, sid, name, legal_name, address, city, state, postal_code, mcc, status, created_date)
            SELECT CAST(%s AS INTEGER), COALESCE(merchant_store_internal_id, sid, CONCAT('STORE_', s.mid)),
                MAX(m.merchant_id), sid,
                MAX(COALESCE(store_name, merchant_name)), MAX(store_legal_name),
                MAX(s.address), MAX(s.city), MAX(s.state), MAX(s.postal_code),
                MAX(s.business_mcc), COALESCE(MAX(store_status), 'ACTIVE'), MAX(merchant_store_created_date)
            FROM stg_merchant_master_raw s
            JOIN dim_merchant m ON s.mid = m.mid AND m.tenant_id = %s
            WHERE s.tenant_id = %s
            GROUP BY s.tenant_id, COALESCE(merchant_store_internal_id, sid, CONCAT('STORE_', s.mid)), sid
            ON CONFLICT (tenant_id, internal_id) DO UPDATE SET
                name = EXCLUDED.name, address = EXCLUDED.address, city = EXCLUDED.city, state = EXCLUDED.state, status = EXCLUDED.status
        """.formatted(tId, tId, tId));

        log.info("[Integration] Merchant dimensions upserted for tenant {}", tenantId);
    }

    /**
     * Transaction post-processing — mirrors TransactionJobConfig steps.
     * Calls ManualIngestionService which handles fact + summaries + metrics.
     */
    private void executeTransactionPostProcessing(Long tenantId) {
        // stagingToFact
        jdbcTemplate.update(
            "DELETE FROM fact_transaction WHERE tenant_id = ? AND DATE(payment_date) IN " +
            "(SELECT DISTINCT DATE(payment_date) FROM stg_trnx_raw WHERE tenant_id = ?)",
            tenantId, tenantId);

        jdbcTemplate.update("""
            INSERT INTO fact_transaction (
                tenant_id, merchant_id, store_id, terminal_id,
                arn, rrn_number, card_number, auth_code,
                payment_date, transaction_date, batch_number, transaction_type, card_scheme, card_type, dcc,
                txn_currency, txn_currency_amount, store_base_currency, store_base_currency_amount,
                msf, vat, total_amount_settled, interchange_fee, destination
            )
            SELECT stg.tenant_id, m.merchant_id, s.store_id, t.terminal_id,
                stg.arn, stg.rrn_number, stg.card_number, stg.auth_code,
                stg.payment_date, stg.transaction_date, stg.batch_number, stg.transaction_type,
                stg.card_scheme, stg.card_type, stg.dcc,
                stg.txn_currency, stg.txn_currency_amount, stg.store_base_currency, stg.store_base_currency_amount,
                stg.msf, stg.vat, stg.total_amount_settled, stg.interchange_fee, stg.destination
            FROM stg_trnx_raw stg
            LEFT JOIN dim_merchant m ON stg.mid = m.mid AND m.tenant_id = ?
            LEFT JOIN dim_store s ON s.merchant_id = m.merchant_id
                AND (s.sid = stg.sid OR s.internal_id = stg.merchant_store_internal_id OR s.internal_id = CONCAT('STORE_', stg.mid))
                AND s.tenant_id = ?
            LEFT JOIN dim_terminal t ON t.store_id = s.store_id
                AND (t.tid = stg.tid OR t.internal_id = stg.tid OR t.internal_id = CONCAT('TERM_', stg.mid))
                AND t.tenant_id = ?
            WHERE stg.tenant_id = ?
        """, tenantId, tenantId, tenantId, tenantId);

        // Trigger ManualIngestionService for summaries + metrics (reuses existing code)
        manualIngestionService.processManualUpload(tenantId);

        log.info("[Integration] Transaction pipeline completed for tenant {}", tenantId);
    }

    /**
     * Schedule a retry with exponential backoff.
     */
    private void scheduleRetry(IntegrationReport report, IntegrationSchedule schedule,
                                LocalDate dateFrom, LocalDate dateTo, int nextAttempt) {
        long delayMs = (long) Math.pow(5, nextAttempt) * 60_000L; // 5min, 25min, 125min
        delayMs = Math.min(delayMs, 30 * 60_000L); // Cap at 30 minutes

        log.info("[Integration] Scheduling retry #{} for '{}' in {}ms", nextAttempt, report.getName(), delayMs);

        // Use a simple timer for retry
        new java.util.Timer("integration-retry").schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                executePull(report, schedule, IntegrationRunLog.TriggerType.RETRY, dateFrom, dateTo, nextAttempt);
            }
        }, delayMs);
    }

    // ─── Helpers ──────────────────────────────────────────────

    private Map<String, Object> buildParams(LocalDate dateFrom, LocalDate dateTo) {
        Map<String, Object> params = new HashMap<>();
        LocalDate now = LocalDate.now();
        params.put("year", now.getYear());
        params.put("month", now.getMonthValue());
        params.put("today", now);
        if (dateFrom != null) params.put("dateFrom", dateFrom);
        if (dateTo != null) params.put("dateTo", dateTo);
        params.put("dateFrom", dateFrom != null ? dateFrom : now.withDayOfMonth(1));
        params.put("dateTo", dateTo != null ? dateTo : now);
        return params;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseColumnMapping(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            // Simple JSON parser — format: {"SQL_COL":"staging_field", ...}
            Map<String, String> map = new HashMap<>();
            json = json.trim();
            if (json.startsWith("{")) json = json.substring(1);
            if (json.endsWith("}")) json = json.substring(0, json.length() - 1);
            for (String pair : json.split(",")) {
                String[] kv = pair.split(":");
                if (kv.length == 2) {
                    String key = kv[0].trim().replaceAll("\"", "").toLowerCase();
                    String val = kv[1].trim().replaceAll("\"", "").toLowerCase();
                    map.put(val, key); // staging_field -> sql_column
                }
            }
            return map;
        } catch (Exception e) {
            log.warn("Failed to parse column mapping: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Get value from row using column mapping.
     * If no mapping exists, tries exact column name match (case-insensitive).
     */
    private String getMapped(Map<String, Object> row, Map<String, String> columnMap, String stagingField) {
        // 1. Check mapping
        String sqlCol = columnMap.get(stagingField);
        if (sqlCol != null) {
            Object val = getIgnoreCase(row, sqlCol);
            return val != null ? val.toString() : null;
        }
        // 2. Direct match (case-insensitive)
        Object val = getIgnoreCase(row, stagingField);
        return val != null ? val.toString() : null;
    }

    private Object getIgnoreCase(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Timestamp parseTimestamp(String val) {
        if (val == null || val.isBlank()) return null;
        try {
            return Timestamp.valueOf(java.time.LocalDateTime.parse(val));
        } catch (Exception e1) {
            try {
                return Timestamp.valueOf(java.time.LocalDate.parse(val).atStartOfDay());
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private Boolean parseBoolean(String val) {
        if (val == null) return null;
        val = val.trim().toUpperCase();
        return "Y".equals(val) || "YES".equals(val) || "TRUE".equals(val) || "1".equals(val);
    }

    private BigDecimal parseBigDecimal(String val) {
        if (val == null || val.isBlank()) return null;
        try {
            return new BigDecimal(val.replaceAll(",", "").trim());
        } catch (Exception e) {
            return null;
        }
    }

    private List<Map<String, Object>> mapResultSet(ResultSet rs) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= colCount; i++) {
                row.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    private void finishRunLog(IntegrationRunLog runLog, long startMs) {
        runLog.setEndTime(LocalDateTime.now());
        runLog.setDurationMs(System.currentTimeMillis() - startMs);
        runLogRepo.save(runLog);
    }

    // ─── Public test connection ───────────────────────────────

    public boolean testConnection(IntegrationConnection config) {
        try (Connection conn = DriverManager.getConnection(
                config.getJdbcUrl(), config.getUsername(), config.getEncryptedPassword())) {
            return conn.isValid(config.getTimeoutSeconds() != null ? config.getTimeoutSeconds() : 5);
        } catch (SQLException e) {
            log.error("Test connection failed for '{}': {}", config.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * Validate SQL by running with LIMIT/ROWNUM 5.
     */
    public List<Map<String, Object>> validateQuery(IntegrationConnection config, String sql) {
        String limitedSql = "SELECT * FROM (" + sql.replaceAll(";$", "") + ") _vq LIMIT 5";
        if (config.getDbType() == IntegrationConnection.DbType.ORACLE) {
            limitedSql = "SELECT * FROM (" + sql.replaceAll(";$", "") + ") WHERE ROWNUM <= 5";
        } else if (config.getDbType() == IntegrationConnection.DbType.MSSQL) {
            limitedSql = "SELECT TOP 5 * FROM (" + sql.replaceAll(";$", "") + ") AS _vq";
        }

        Map<String, Object> params = buildParams(LocalDate.now().withDayOfMonth(1), LocalDate.now());
        return executeExternalQuery(config, limitedSql, params);
    }
}
