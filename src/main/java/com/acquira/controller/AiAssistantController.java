package com.acquira.controller;

import com.acquira.config.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/ai")
public class AiAssistantController {

    private static final Logger logger = LoggerFactory.getLogger(AiAssistantController.class);
    private final JdbcTemplate jdbcTemplate;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.model:llama3.2}")
    private String ollamaModel;

    private static final Set<String> ALLOWED_TABLES = Set.of("stg_merchant_master_raw", "stg_trnx_raw");
    private static final Set<String> BLOCKED_KEYWORDS = Set.of(
        "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE",
        "EXEC", "EXECUTE", "GRANT", "REVOKE", "MERGE", "CALL"
    );

    // ══════════════════════════════════════════════════════════════════
    // SCHEMA + TRAINING PROMPT — Optimized for smaller LLMs
    // ══════════════════════════════════════════════════════════════════
    private static final String SCHEMA_CONTEXT = """
You are a PostgreSQL SQL generator for a payment/acquiring platform.
RESPOND WITH ONLY A RAW SQL SELECT QUERY. Nothing else. No markdown. No explanation. No backticks.

=== TABLES ===

TABLE stg_trnx_raw — Transaction records:
  mid, merchant_name, merchant_internal_id, sid, store_name, merchant_store_internal_id,
  cmm_merchant_store_internal_id, merchant_store_legal_name, tid,
  entity_name, aggregator_internal_id, aggregator_name, aggregator_code,
  arn, rrn_number, auth_code, batch_number,
  card_number, card_scheme (Visa/Mastercard/AMEX/Diners), card_type (Credit/Debit/Prepaid),
  transaction_type (Sale/Refund/Void), dcc BOOLEAN, destination (Local/International),
  payment_date TIMESTAMP, transaction_date TIMESTAMP,
  txn_currency, txn_currency_amount DECIMAL,
  store_base_currency, store_base_currency_amount DECIMAL (USE FOR VOLUME/AMOUNT),
  msf DECIMAL (Merchant Service Fee = REVENUE),
  vat DECIMAL, total_amount_settled DECIMAL, interchange_fee DECIMAL

TABLE stg_merchant_master_raw — Merchant master data:
  mid, merchant_name, merchant_internal_id, sid, store_name, store_legal_name,
  merchant_store_internal_id, tid, terminal_name, terminal_device_number,
  institution_code, institution_name, entity_internal_id, entity_name, entity_code,
  aggregator_internal_id, aggregator_name, aggregator_code,
  business_type, business_mcc, industry_type, customer_type, product, terminal_type, source_of_fund,
  merchant_status (Active/Inactive/Suspended), store_status, terminal_status,
  city, state, postal_code, address,
  primary_contact_person, primary_contact_number, primary_contact_email, primary_contact_designation,
  secondary_contact_person, secondary_contact_email, secondary_contact_number, secondary_contact_designation,
  sales_user_email, sales_user_id, referral_partner,
  risk_level (Low/Medium/High), risk_level_high BOOLEAN, risk_level_prohibited BOOLEAN,
  risk_level_restricted BOOLEAN, regulated_activity BOOLEAN, is_pep BOOLEAN,
  high_risk_adverse_media BOOLEAN, high_risk_source_of_wealth BOOLEAN,
  auditor_name, vat_number, expected_volume DECIMAL,
  date_of_onboarding TIMESTAMP, reviewed_date TIMESTAMP, next_reviewed_date TIMESTAMP,
  created_date TIMESTAMP, merchant_created_date TIMESTAMP,
  merchant_store_created_date TIMESTAMP, terminal_created_date TIMESTAMP,
  bank_name, bank_account_name, bank_account_number, swift_code, iban_number

SYSTEM COLUMNS TO NEVER SELECT: raw_id, tenant_id, file_id, load_time, row_hash, status, error_message

=== RULES (MUST FOLLOW) ===
1. ALWAYS: WHERE tenant_id = {TENANT_ID}
2. Only SELECT, never INSERT/UPDATE/DELETE/DROP
3. Volume/Amount → store_base_currency_amount
4. Revenue/MSF → msf
5. Settlement → total_amount_settled
6. Count → COUNT(*)
7. Distinct merchants → COUNT(DISTINCT mid)
8. Active merchants → merchant_status = 'Active'
9. COALESCE(SUM(...), 0) for null safety
10. JOIN: stg_trnx_raw t LEFT JOIN stg_merchant_master_raw m ON t.mid = m.mid AND t.tenant_id = m.tenant_id

=== AMBIGUITY RULES (CRITICAL — violations cause errors) ===
- When using JOIN, prefix EVERY column with t. or m. — NEVER use bare column names
- Use t.tenant_id in WHERE (not just tenant_id)
- Use t.mid, t.merchant_name for transaction columns, m.city, m.industry_type for merchant columns
- GROUP BY must list ALL non-aggregated columns from SELECT — if SELECT has t.mid, t.merchant_name then GROUP BY must have t.mid, t.merchant_name
- For single-table queries (no JOIN), no prefix needed

=== DATE RULES (CRITICAL) ===
- NEVER: DATE_TRUNC('month', '2026-01-01') — this ERRORS!
- This month: payment_date >= DATE_TRUNC('month', CURRENT_DATE) AND payment_date < DATE_TRUNC('month', CURRENT_DATE) + INTERVAL '1 month'
- Last month (CALENDAR): payment_date >= DATE_TRUNC('month', CURRENT_DATE - INTERVAL '1 month') AND payment_date < DATE_TRUNC('month', CURRENT_DATE)
- This year (CALENDAR): payment_date >= DATE_TRUNC('year', CURRENT_DATE)
- Last year (CALENDAR 2025): payment_date >= DATE_TRUNC('year', CURRENT_DATE - INTERVAL '1 year') AND payment_date < DATE_TRUNC('year', CURRENT_DATE)
- Last 30 days (ROLLING): payment_date >= CURRENT_DATE - INTERVAL '30 days'
- Last 7 days (ROLLING): payment_date >= CURRENT_DATE - INTERVAL '7 days'
- Specific month: payment_date >= '2026-01-01'::date AND payment_date < '2026-02-01'::date
- Specific date: payment_date::date = '2026-02-10'
- ALWAYS cast literals: '2026-01-01'::date
- Group by month: TO_CHAR(payment_date, 'YYYY-MM') AS month
- Group by day: payment_date::date AS txn_date
- Use CURRENT_DATE not NOW()
- "last year" / "previous year" = CALENDAR year (Jan 1 to Dec 31), NOT rolling 12 months
- "last month" / "previous month" = CALENDAR month (1st to last day), NOT rolling 30 days
- "last N days" / "past N days" / "recent" = ROLLING period from today

=== EXAMPLES ===

Q: Total transaction volume by card scheme
SELECT card_scheme, COUNT(*) AS txn_count, COALESCE(SUM(store_base_currency_amount), 0) AS total_volume, COALESCE(SUM(msf), 0) AS total_msf FROM stg_trnx_raw WHERE tenant_id = {TENANT_ID} GROUP BY card_scheme ORDER BY total_volume DESC

Q: Top 10 merchants by transaction count
SELECT t.mid, t.merchant_name, COUNT(*) AS txn_count, COALESCE(SUM(t.store_base_currency_amount), 0) AS total_volume FROM stg_trnx_raw t WHERE t.tenant_id = {TENANT_ID} GROUP BY t.mid, t.merchant_name ORDER BY txn_count DESC LIMIT 10

Q: Active merchants by city
SELECT city, COUNT(DISTINCT mid) AS merchant_count FROM stg_merchant_master_raw WHERE tenant_id = {TENANT_ID} AND merchant_status = 'Active' AND city IS NOT NULL GROUP BY city ORDER BY merchant_count DESC

Q: Total MSF revenue this month
SELECT COALESCE(SUM(msf), 0) AS total_msf, COALESCE(SUM(vat), 0) AS total_vat, COUNT(*) AS txn_count FROM stg_trnx_raw WHERE tenant_id = {TENANT_ID} AND payment_date >= DATE_TRUNC('month', CURRENT_DATE) AND payment_date < DATE_TRUNC('month', CURRENT_DATE) + INTERVAL '1 month'

Q: Monthly volume trend
SELECT TO_CHAR(payment_date, 'YYYY-MM') AS month, COUNT(*) AS txn_count, COALESCE(SUM(store_base_currency_amount), 0) AS total_volume, COALESCE(SUM(msf), 0) AS total_msf FROM stg_trnx_raw WHERE tenant_id = {TENANT_ID} AND payment_date IS NOT NULL GROUP BY TO_CHAR(payment_date, 'YYYY-MM') ORDER BY month

Q: Card type breakdown Credit vs Debit vs Prepaid
SELECT card_type, COUNT(*) AS txn_count, COALESCE(SUM(store_base_currency_amount), 0) AS total_volume FROM stg_trnx_raw WHERE tenant_id = {TENANT_ID} AND card_type IS NOT NULL GROUP BY card_type ORDER BY total_volume DESC

Q: Local vs International transactions
SELECT destination, COUNT(*) AS txn_count, COALESCE(SUM(store_base_currency_amount), 0) AS total_volume, COALESCE(SUM(msf), 0) AS total_msf FROM stg_trnx_raw WHERE tenant_id = {TENANT_ID} AND destination IS NOT NULL GROUP BY destination ORDER BY total_volume DESC

Q: Top 5 cities by transaction volume
SELECT m.city, COUNT(*) AS txn_count, COALESCE(SUM(t.store_base_currency_amount), 0) AS total_volume FROM stg_trnx_raw t LEFT JOIN stg_merchant_master_raw m ON t.mid = m.mid AND t.tenant_id = m.tenant_id WHERE t.tenant_id = {TENANT_ID} AND m.city IS NOT NULL GROUP BY m.city ORDER BY total_volume DESC LIMIT 5

Q: Revenue by industry type
SELECT m.industry_type, COUNT(*) AS txn_count, COALESCE(SUM(t.msf), 0) AS total_msf FROM stg_trnx_raw t LEFT JOIN stg_merchant_master_raw m ON t.mid = m.mid AND t.tenant_id = m.tenant_id WHERE t.tenant_id = {TENANT_ID} AND m.industry_type IS NOT NULL GROUP BY m.industry_type ORDER BY total_msf DESC

Q: All merchants with status
SELECT mid, merchant_name, merchant_status, city, industry_type, business_type, product, date_of_onboarding FROM stg_merchant_master_raw WHERE tenant_id = {TENANT_ID} ORDER BY merchant_name LIMIT 500

Q: High risk merchants
SELECT mid, merchant_name, risk_level, city, industry_type, merchant_status, is_pep FROM stg_merchant_master_raw WHERE tenant_id = {TENANT_ID} AND (risk_level = 'High' OR risk_level_high = true) ORDER BY merchant_name LIMIT 500

Q: Average transaction value by card scheme
SELECT t.card_scheme, COUNT(*) AS txn_count, COALESCE(AVG(t.store_base_currency_amount), 0) AS avg_txn_value, COALESCE(SUM(t.store_base_currency_amount), 0) AS total_volume FROM stg_trnx_raw t WHERE t.tenant_id = {TENANT_ID} AND t.card_scheme IS NOT NULL GROUP BY t.card_scheme ORDER BY avg_txn_value DESC

Q: Refund transactions summary
SELECT t.mid, t.merchant_name, COUNT(*) AS refund_count, COALESCE(SUM(t.store_base_currency_amount), 0) AS refund_amount FROM stg_trnx_raw t WHERE t.tenant_id = {TENANT_ID} AND t.transaction_type = 'Refund' GROUP BY t.mid, t.merchant_name ORDER BY refund_amount DESC LIMIT 20

Q: Daily trend last 30 days
SELECT payment_date::date AS txn_date, COUNT(*) AS txn_count, COALESCE(SUM(store_base_currency_amount), 0) AS daily_volume FROM stg_trnx_raw WHERE tenant_id = {TENANT_ID} AND payment_date >= CURRENT_DATE - INTERVAL '30 days' GROUP BY payment_date::date ORDER BY txn_date

Q: Visa transactions this month
SELECT COUNT(*) AS txn_count, COALESCE(SUM(store_base_currency_amount), 0) AS total_volume, COALESCE(SUM(msf), 0) AS total_msf FROM stg_trnx_raw WHERE tenant_id = {TENANT_ID} AND card_scheme = 'Visa' AND payment_date >= DATE_TRUNC('month', CURRENT_DATE) AND payment_date < DATE_TRUNC('month', CURRENT_DATE) + INTERVAL '1 month'

Q: Transactions in January 2026
SELECT COUNT(*) AS txn_count, COALESCE(SUM(store_base_currency_amount), 0) AS total_volume FROM stg_trnx_raw WHERE tenant_id = {TENANT_ID} AND payment_date >= '2026-01-01'::date AND payment_date < '2026-02-01'::date

Q: Monthly trend last 6 months
SELECT TO_CHAR(payment_date, 'YYYY-MM') AS month, COUNT(*) AS txn_count, COALESCE(SUM(store_base_currency_amount), 0) AS total_volume, COALESCE(SUM(msf), 0) AS total_msf FROM stg_trnx_raw WHERE tenant_id = {TENANT_ID} AND payment_date >= CURRENT_DATE - INTERVAL '6 months' GROUP BY TO_CHAR(payment_date, 'YYYY-MM') ORDER BY month

Q: Interchange fee by card scheme
SELECT card_scheme, COUNT(*) AS txn_count, COALESCE(SUM(interchange_fee), 0) AS total_interchange, COALESCE(SUM(msf), 0) AS total_msf, COALESCE(SUM(msf), 0) - COALESCE(SUM(interchange_fee), 0) AS net_revenue FROM stg_trnx_raw WHERE tenant_id = {TENANT_ID} AND card_scheme IS NOT NULL GROUP BY card_scheme ORDER BY net_revenue DESC

Q: Terminals per merchant
SELECT m.mid, m.merchant_name, COUNT(DISTINCT m.tid) AS terminal_count FROM stg_merchant_master_raw m WHERE m.tenant_id = {TENANT_ID} AND m.tid IS NOT NULL GROUP BY m.mid, m.merchant_name ORDER BY terminal_count DESC LIMIT 20

Q: Merchants by referral partner
SELECT referral_partner, COUNT(DISTINCT mid) AS merchant_count FROM stg_merchant_master_raw WHERE tenant_id = {TENANT_ID} AND referral_partner IS NOT NULL GROUP BY referral_partner ORDER BY merchant_count DESC

Q: DCC transaction rate
SELECT CASE WHEN dcc = true THEN 'DCC' ELSE 'Non-DCC' END AS dcc_flag, COUNT(*) AS txn_count, COALESCE(SUM(store_base_currency_amount), 0) AS total_volume FROM stg_trnx_raw WHERE tenant_id = {TENANT_ID} GROUP BY dcc ORDER BY txn_count DESC

Q: Merchants onboarded this year
SELECT mid, merchant_name, city, industry_type, product, merchant_status, date_of_onboarding FROM stg_merchant_master_raw WHERE tenant_id = {TENANT_ID} AND date_of_onboarding >= DATE_TRUNC('year', CURRENT_DATE) ORDER BY date_of_onboarding DESC LIMIT 500

Q: Merchants onboarded last year
SELECT mid, merchant_name, city, industry_type, product, merchant_status, date_of_onboarding FROM stg_merchant_master_raw WHERE tenant_id = {TENANT_ID} AND date_of_onboarding >= DATE_TRUNC('year', CURRENT_DATE - INTERVAL '1 year') AND date_of_onboarding < DATE_TRUNC('year', CURRENT_DATE) ORDER BY date_of_onboarding DESC LIMIT 500

Q: Transactions last month
SELECT COUNT(*) AS txn_count, COALESCE(SUM(store_base_currency_amount), 0) AS total_volume, COALESCE(SUM(msf), 0) AS total_msf FROM stg_trnx_raw WHERE tenant_id = {TENANT_ID} AND payment_date >= DATE_TRUNC('month', CURRENT_DATE - INTERVAL '1 month') AND payment_date < DATE_TRUNC('month', CURRENT_DATE)

Q: Transactions by currency
SELECT txn_currency, COUNT(*) AS txn_count, COALESCE(SUM(txn_currency_amount), 0) AS total_in_txn_currency FROM stg_trnx_raw WHERE tenant_id = {TENANT_ID} AND txn_currency IS NOT NULL GROUP BY txn_currency ORDER BY txn_count DESC

Q: Top merchants by MSF revenue
SELECT mid, merchant_name, COUNT(*) AS txn_count, COALESCE(SUM(msf), 0) AS total_msf, COALESCE(SUM(store_base_currency_amount), 0) AS total_volume FROM stg_trnx_raw WHERE tenant_id = {TENANT_ID} GROUP BY mid, merchant_name ORDER BY total_msf DESC LIMIT 10

Q: How many merchants are there
SELECT COUNT(DISTINCT mid) AS total_merchants, COUNT(DISTINCT CASE WHEN merchant_status = 'Active' THEN mid END) AS active_merchants, COUNT(DISTINCT CASE WHEN merchant_status = 'Inactive' THEN mid END) AS inactive_merchants FROM stg_merchant_master_raw WHERE tenant_id = {TENANT_ID}

Q: Total transactions today
SELECT COUNT(*) AS txn_count, COALESCE(SUM(store_base_currency_amount), 0) AS total_volume, COALESCE(SUM(msf), 0) AS total_msf FROM stg_trnx_raw WHERE tenant_id = {TENANT_ID} AND payment_date::date = CURRENT_DATE

Q: Transaction volume by store
SELECT sid, store_name, COUNT(*) AS txn_count, COALESCE(SUM(store_base_currency_amount), 0) AS total_volume FROM stg_trnx_raw WHERE tenant_id = {TENANT_ID} GROUP BY sid, store_name ORDER BY total_volume DESC LIMIT 20

=== NOW GENERATE SQL ===
""";

    public AiAssistantController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000);
        this.restTemplate = new RestTemplate(factory);
    }

    // ═══════════════════════════════════════════════════════════════
    // HEALTH & MODELS
    // ═══════════════════════════════════════════════════════════════
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        try {
            long t0 = System.currentTimeMillis();
            ResponseEntity<String> resp = restTemplate.getForEntity(ollamaBaseUrl + "/api/tags", String.class);
            long dur = System.currentTimeMillis() - t0;
            JsonNode models = objectMapper.readTree(resp.getBody());
            List<String> names = new ArrayList<>();
            if (models.has("models")) models.get("models").forEach(m -> names.add(m.get("name").asText()));
            return ResponseEntity.ok(Map.of("status","connected","ollamaUrl",ollamaBaseUrl,"model",ollamaModel,"availableModels",names,"responseTime",dur));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("status","disconnected","error",e.getMessage(),"ollamaUrl",ollamaBaseUrl,"model",ollamaModel,"hint","Run: ollama serve"));
        }
    }

    @GetMapping("/models")
    public ResponseEntity<?> listModels() {
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(ollamaBaseUrl + "/api/tags", String.class);
            JsonNode models = objectMapper.readTree(resp.getBody());
            List<Map<String,Object>> result = new ArrayList<>();
            if (models.has("models")) models.get("models").forEach(m -> result.add(Map.of("name",m.get("name").asText(),"size",m.has("size")?m.get("size").asLong():0,"modified",m.has("modified_at")?m.get("modified_at").asText():"")));
            return ResponseEntity.ok(result);
        } catch (Exception e) { return ResponseEntity.ok(Collections.emptyList()); }
    }

    // ═══════════════════════════════════════════════════════════════
    // MAIN ASK ENDPOINT — with RETRY on SQL failure
    // ═══════════════════════════════════════════════════════════════
    @PostMapping("/ask")
    public ResponseEntity<?> ask(@RequestBody AskRequest request) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.badRequest().body(Map.of("error","No tenant context"));
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error","Question is required"));

        long totalStart = System.currentTimeMillis();
        String model = request.getModel() != null ? request.getModel() : ollamaModel;
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("question", request.getQuestion());

        int maxRetries = 2;
        String lastError = null;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // ═══ STEP 1: Generate SQL ═══
                logger.info("[ATTEMPT {}/{}] Generating SQL for: \"{}\"", attempt, maxRetries, request.getQuestion());
                long s1 = System.currentTimeMillis();

                String prompt = SCHEMA_CONTEXT.replace("{TENANT_ID}", String.valueOf(tenantId))
                    + "Q: " + request.getQuestion();

                // On retry, add error context so model can self-correct
                if (attempt > 1 && lastError != null) {
                    prompt += "\n\nPREVIOUS ATTEMPT FAILED WITH ERROR: " + lastError + "\nPlease fix and generate correct SQL:";
                    logger.info("[RETRY] Adding error context: {}", lastError);
                }

                String sqlRaw = callOllama(prompt, model, attempt > 1 ? 0.15 : 0.05);
                String sql = autoFixSql(cleanSql(sqlRaw), tenantId);
                long d1 = System.currentTimeMillis() - s1;
                logger.info("[STEP 1] SQL in {}ms: {}", d1, sql.length() > 200 ? sql.substring(0,200)+"..." : sql);
                result.put("generatedSql", sql);
                result.put("sqlGenerationTime", d1);

                // ═══ STEP 2: Validate ═══
                String valErr = validateSql(sql, tenantId);
                if (valErr != null) {
                    lastError = valErr;
                    logger.warn("[STEP 2] Validation failed: {}", valErr);
                    if (attempt < maxRetries) continue; // retry
                    result.put("error", valErr);
                    result.put("safe", false);
                    break;
                }
                result.put("safe", true);

                // ═══ STEP 3: Execute ═══
                logger.info("[STEP 3] Executing...");
                long s3 = System.currentTimeMillis();
                List<Map<String,Object>> data = jdbcTemplate.queryForList(sql);
                long d3 = System.currentTimeMillis() - s3;
                logger.info("[STEP 3] {} rows in {}ms", data.size(), d3);
                result.put("data", data);
                result.put("rowCount", data.size());
                result.put("columns", data.isEmpty() ? Collections.emptyList() : new ArrayList<>(data.get(0).keySet()));
                result.put("queryExecutionTime", d3);
                result.put("summary", buildSummary(request.getQuestion(), data));
                lastError = null;
                break; // success — exit retry loop

            } catch (Exception e) {
                lastError = e.getMessage();
                logger.error("[ATTEMPT {}/{}] Failed: {}", attempt, maxRetries, e.getMessage());
                if (attempt >= maxRetries) {
                    // Clean up error message for display
                    String errMsg = lastError;
                    if (errMsg.contains("bad SQL grammar")) {
                        int bracketStart = errMsg.indexOf('[');
                        int bracketEnd = errMsg.indexOf(']', bracketStart);
                        String badSql = bracketStart > 0 && bracketEnd > bracketStart ? errMsg.substring(bracketStart+1, bracketEnd) : "";
                        errMsg = "SQL syntax error. The generated query had an issue. Try rephrasing your question.";
                        if (!badSql.isEmpty() && badSql.length() < 200) result.put("generatedSql", badSql);
                    } else if (errMsg.contains("timeout") || errMsg.contains("Timeout")) {
                        errMsg = "Request timed out. The model may be loading — try again in a few seconds.";
                    } else if (errMsg.contains("Connection refused")) {
                        errMsg = "Cannot connect to Ollama. Make sure it's running: ollama serve";
                    }
                    result.put("error", errMsg);
                }
            }
        }

        long totalDur = System.currentTimeMillis() - totalStart;
        result.put("duration", totalDur);
        if (lastError != null && !result.containsKey("error")) result.put("error", lastError);
        logger.info("Total: {}ms", totalDur);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/explain")
    public ResponseEntity<?> explain(@RequestBody AskRequest request) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return ResponseEntity.badRequest().body(Map.of("error","No tenant context"));
        try {
            String prompt = SCHEMA_CONTEXT.replace("{TENANT_ID}", String.valueOf(tenantId)) + "Q: " + request.getQuestion();
            String model = request.getModel() != null ? request.getModel() : ollamaModel;
            String sql = autoFixSql(cleanSql(callOllama(prompt, model, 0.05)), tenantId);
            return ResponseEntity.ok(Map.of("question",request.getQuestion(),"generatedSql",sql,"safe",validateSql(sql,tenantId)==null));
        } catch (Exception e) { return ResponseEntity.ok(Map.of("error",e.getMessage())); }
    }

    // ═══════════════════════════════════════════════════════════════
    // AUTO-FIX — repair common LLM SQL mistakes before execution
    // ═══════════════════════════════════════════════════════════════
    private String autoFixSql(String sql, Long tenantId) {
        if (sql == null || sql.isEmpty()) return sql;
        String fixed = sql;

        // FIX 1: DATE_TRUNC('month', '2026-02-01') → add ::date cast
        fixed = fixed.replaceAll("DATE_TRUNC\\s*\\(\\s*'(\\w+)'\\s*,\\s*'(\\d{4}-\\d{2}-\\d{2})'\\s*\\)","DATE_TRUNC('$1', '$2'::date)");
        fixed = fixed.replaceAll("DATE_TRUNC\\s*\\(\\s*'(\\w+)'\\s*,\\s*'(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})'\\s*\\)","DATE_TRUNC('$1', '$2'::timestamp)");

        // FIX 2: Bare date literals → add ::date
        fixed = fixed.replaceAll("(>=|<=|>|<|=)\\s*'(\\d{4}-\\d{2}-\\d{2})'(?!::)","$1 '$2'::date");

        // FIX 3: NOW() → CURRENT_DATE
        fixed = fixed.replaceAll("(?i)NOW\\(\\)", "CURRENT_DATE");

        // FIX 4: Remove trailing semicolons and post-query text
        fixed = fixed.replaceAll(";\\s*$", "");
        // Cut any explanation text after the SQL
        String[] lines = fixed.split("\\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim().toUpperCase();
            // Stop if line looks like explanation (doesn't start with SQL keyword or is empty continuation)
            if (sb.length() > 0 && !trimmed.isEmpty() && !trimmed.startsWith("SELECT") && !trimmed.startsWith("FROM")
                && !trimmed.startsWith("WHERE") && !trimmed.startsWith("AND") && !trimmed.startsWith("OR")
                && !trimmed.startsWith("GROUP") && !trimmed.startsWith("ORDER") && !trimmed.startsWith("LIMIT")
                && !trimmed.startsWith("LEFT") && !trimmed.startsWith("INNER") && !trimmed.startsWith("RIGHT")
                && !trimmed.startsWith("ON") && !trimmed.startsWith("HAVING") && !trimmed.startsWith("COALESCE")
                && !trimmed.startsWith("CASE") && !trimmed.startsWith("WHEN") && !trimmed.startsWith("THEN")
                && !trimmed.startsWith("ELSE") && !trimmed.startsWith("END") && !trimmed.startsWith("JOIN")
                && !trimmed.startsWith("(") && !trimmed.startsWith(")") && !trimmed.startsWith(",")
                && trimmed.matches("^[A-Z].*") && !trimmed.startsWith("T.") && !trimmed.startsWith("M.")) {
                break;
            }
            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
        }
        fixed = sb.toString().trim();

        // FIX 5: Fix ambiguous columns in JOIN queries
        String upper = fixed.toUpperCase();
        boolean hasJoin = upper.contains("JOIN");
        if (hasJoin) {
            // Columns that exist in BOTH tables — must be prefixed
            String[] ambiguous = {"mid", "merchant_name", "merchant_internal_id", "sid", "store_name",
                "merchant_store_internal_id", "tid", "entity_name", "aggregator_internal_id",
                "aggregator_name", "aggregator_code", "tenant_id"};
            for (String col : ambiguous) {
                // Replace bare column references (not already prefixed with t. or m.)
                // Match: word boundary + col + word boundary, NOT preceded by . or alias
                // Be careful with SELECT, WHERE, GROUP BY, ORDER BY, ON clauses
                fixed = fixed.replaceAll("(?<![.\\w])" + col + "(?![.\\w])",
                    col.equals("tenant_id") ? "t.tenant_id" :
                    // Merchant-only columns use m. prefix
                    (col.equals("city") || col.equals("state") || col.equals("industry_type") ||
                     col.equals("business_type") || col.equals("merchant_status") || col.equals("risk_level")) ?
                        "m." + col : "t." + col);
            }
            // Also fix bare tenant_id in WHERE
            fixed = fixed.replaceAll("(?<![.\\w])tenant_id(?![.\\w])", "t.tenant_id");
        }

        // FIX 6: Fix "last year" / "last month" missing upper bound
        // Pattern: has DATE_TRUNC('year', CURRENT_DATE - INTERVAL '1 year') but no upper bound
        upper = fixed.toUpperCase();
        // Last year: needs upper bound < DATE_TRUNC('year', CURRENT_DATE)
        if (upper.contains("DATE_TRUNC('YEAR', CURRENT_DATE - INTERVAL '1 YEAR')") || upper.contains("DATE_TRUNC( 'YEAR', CURRENT_DATE - INTERVAL '1 YEAR')")) {
            if (!upper.contains("< DATE_TRUNC('YEAR', CURRENT_DATE)") && !upper.contains("<DATE_TRUNC('YEAR', CURRENT_DATE)")) {
                // Find the column used in the >= condition (e.g., date_of_onboarding, payment_date)
                java.util.regex.Matcher yrMatcher = java.util.regex.Pattern.compile(
                    "(?i)(\\S+)\\s*>=\\s*DATE_TRUNC\\s*\\(\\s*'year'\\s*,\\s*CURRENT_DATE\\s*-\\s*INTERVAL\\s*'1 year'\\s*\\)"
                ).matcher(fixed);
                if (yrMatcher.find()) {
                    String dateCol = yrMatcher.group(1);
                    String upperBound = " AND " + dateCol + " < DATE_TRUNC('year', CURRENT_DATE)";
                    // Insert after the matched expression
                    int insertAt = yrMatcher.end();
                    fixed = fixed.substring(0, insertAt) + upperBound + fixed.substring(insertAt);
                    logger.info("AUTO-FIX LAST YEAR: added upper bound for column {}", dateCol);
                }
            }
        }
        // Last month: needs upper bound < DATE_TRUNC('month', CURRENT_DATE)
        if (upper.contains("DATE_TRUNC('MONTH', CURRENT_DATE - INTERVAL '1 MONTH')") || upper.contains("DATE_TRUNC( 'MONTH', CURRENT_DATE - INTERVAL '1 MONTH')")) {
            if (!upper.contains("< DATE_TRUNC('MONTH', CURRENT_DATE)") && !upper.contains("<DATE_TRUNC('MONTH', CURRENT_DATE)")) {
                java.util.regex.Matcher moMatcher = java.util.regex.Pattern.compile(
                    "(?i)(\\S+)\\s*>=\\s*DATE_TRUNC\\s*\\(\\s*'month'\\s*,\\s*CURRENT_DATE\\s*-\\s*INTERVAL\\s*'1 month'\\s*\\)"
                ).matcher(fixed);
                if (moMatcher.find()) {
                    String dateCol = moMatcher.group(1);
                    String upperBound = " AND " + dateCol + " < DATE_TRUNC('month', CURRENT_DATE)";
                    int insertAt = moMatcher.end();
                    fixed = fixed.substring(0, insertAt) + upperBound + fixed.substring(insertAt);
                    logger.info("AUTO-FIX LAST MONTH: added upper bound for column {}", dateCol);
                }
            }
        }

        // FIX 7: Fix GROUP BY missing non-aggregated columns
        upper = fixed.toUpperCase();
        if (upper.contains("GROUP BY")) {
            try {
                // Extract SELECT columns (non-aggregated)
                int selIdx = upper.indexOf("SELECT") + 6;
                int fromIdx = upper.indexOf("FROM");
                if (selIdx > 6 && fromIdx > selIdx) {
                    String selectPart = fixed.substring(selIdx, fromIdx).trim();
                    String[] selectCols = selectPart.split(",");
                    List<String> nonAggCols = new ArrayList<>();
                    for (String col : selectCols) {
                        String colTrimmed = col.trim();
                        String colUpper = colTrimmed.toUpperCase();
                        // Skip aggregated columns (contain COUNT, SUM, AVG, MIN, MAX, COALESCE, CASE)
                        if (colUpper.contains("COUNT") || colUpper.contains("SUM") || colUpper.contains("AVG")
                            || colUpper.contains("MIN(") || colUpper.contains("MAX(") || colUpper.contains("COALESCE")
                            || colUpper.contains("CASE")) continue;
                        // Get the actual column name (before AS alias)
                        String actualCol = colTrimmed;
                        int asIdx = colUpper.indexOf(" AS ");
                        if (asIdx > 0) actualCol = colTrimmed.substring(0, asIdx).trim();
                        // Skip expressions like payment_date::date (those need to match exactly in GROUP BY)
                        nonAggCols.add(actualCol);
                    }

                    if (!nonAggCols.isEmpty()) {
                        int gbIdx = upper.indexOf("GROUP BY");
                        int afterGb = gbIdx + 8;
                        // Find end of GROUP BY clause
                        int gbEnd = upper.length();
                        for (String kw : new String[]{"ORDER BY", "LIMIT", "HAVING"}) {
                            int ki = upper.indexOf(kw, afterGb);
                            if (ki > 0 && ki < gbEnd) gbEnd = ki;
                        }
                        String currentGb = fixed.substring(afterGb, gbEnd).trim();
                        String currentGbUpper = currentGb.toUpperCase();

                        // Check if all non-agg columns are in GROUP BY
                        List<String> missing = new ArrayList<>();
                        for (String col : nonAggCols) {
                            if (!currentGbUpper.contains(col.toUpperCase())) {
                                missing.add(col);
                            }
                        }

                        if (!missing.isEmpty()) {
                            // Replace GROUP BY with complete list
                            String newGb = String.join(", ", nonAggCols);
                            fixed = fixed.substring(0, gbIdx) + "GROUP BY " + newGb + " " + fixed.substring(gbEnd);
                            logger.info("AUTO-FIX GROUP BY: added missing columns: {}", missing);
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("GROUP BY fix failed (non-critical): {}", e.getMessage());
            }
        }

        // FIX 8: Ensure tenant_id present
        upper = fixed.toUpperCase();
        if (!upper.contains("TENANT_ID")) {
            int w = upper.indexOf("WHERE");
            if (w >= 0) {
                String prefix = hasJoin ? "t.tenant_id" : "tenant_id";
                fixed = fixed.substring(0,w+5) + " " + prefix + " = " + tenantId + " AND" + fixed.substring(w+5);
            } else {
                int ins = findInsertPoint(upper);
                String prefix = hasJoin ? "t.tenant_id" : "tenant_id";
                fixed = fixed.substring(0,ins) + " WHERE " + prefix + " = " + tenantId + " " + fixed.substring(ins);
            }
        }

        // FIX 9: Add LIMIT if missing + no GROUP BY
        upper = fixed.toUpperCase();
        if (!upper.contains("LIMIT") && !upper.contains("GROUP BY")) fixed += " LIMIT 500";

        if (!fixed.equals(sql)) logger.info("AUTO-FIX applied:\nBefore: {}\nAfter:  {}", sql.substring(0,Math.min(sql.length(),150)), fixed.substring(0,Math.min(fixed.length(),150)));
        return fixed.trim();
    }

    private int findInsertPoint(String upper) {
        for (String kw : new String[]{"GROUP BY","ORDER BY","LIMIT","HAVING"}) {
            int idx = upper.indexOf(kw);
            if (idx >= 0) return idx;
        }
        return upper.length();
    }

    // ═══════════════════════════════════════════════════════════════
    // SMART SUMMARY — natural language, no Ollama call
    // ═══════════════════════════════════════════════════════════════
    private String buildSummary(String question, List<Map<String,Object>> data) {
        if (data == null || data.isEmpty()) return "No results found for your query.";

        int rows = data.size();
        List<String> cols = new ArrayList<>(data.get(0).keySet());

        // Separate dimension vs measure columns
        List<String> dims = new ArrayList<>(), meas = new ArrayList<>();
        for (String c : cols) {
            if (data.get(0).get(c) instanceof Number) meas.add(c); else dims.add(c);
        }

        // Compute totals for measures
        Map<String,Double> totals = new LinkedHashMap<>();
        for (String m : meas) {
            totals.put(m, data.stream().mapToDouble(r -> r.get(m) instanceof Number ? ((Number)r.get(m)).doubleValue() : 0).sum());
        }

        StringBuilder sb = new StringBuilder();

        // Single-row aggregation (e.g., "total MSF this month")
        if (rows == 1 && dims.isEmpty()) {
            sb.append("Here are the results: ");
            for (String m : meas) {
                Object v = data.get(0).get(m);
                sb.append(friendlyName(m)).append(" is **").append(fmtVal(v)).append("**. ");
            }
            return sb.toString().trim();
        }

        // Multi-row with dimension
        if (!dims.isEmpty() && !meas.isEmpty()) {
            String dimName = friendlyName(dims.get(0));
            String topMeasure = meas.get(0);

            sb.append("Found **").append(rows).append(rows==1?" result":" results").append("** grouped by ").append(dimName).append(". ");

            // Top entry
            Map<String,Object> top = data.get(0);
            Object topLabel = top.get(dims.get(0));
            Object topVal = top.get(topMeasure);
            if (topLabel != null && topVal != null) {
                sb.append("**").append(topLabel).append("** leads with ").append(friendlyName(topMeasure)).append(" of **").append(fmtVal(topVal)).append("**");
                // Show second if exists
                if (rows >= 2) {
                    Map<String,Object> second = data.get(1);
                    Object secLabel = second.get(dims.get(0));
                    Object secVal = second.get(topMeasure);
                    if (secLabel != null && secVal != null) {
                        sb.append(", followed by **").append(secLabel).append("** at **").append(fmtVal(secVal)).append("**");
                    }
                }
                sb.append(". ");
            }

            // Grand totals for key measures
            for (Map.Entry<String,Double> e : totals.entrySet()) {
                String name = e.getKey().toLowerCase();
                if (name.contains("count") || name.contains("volume") || name.contains("msf") || name.contains("amount") || name.contains("revenue") || name.contains("fee") || name.contains("settled")) {
                    sb.append("Overall ").append(friendlyName(e.getKey())).append(": **").append(formatNumber(e.getValue())).append("**. ");
                }
            }
        } else if (dims.isEmpty()) {
            // Pure aggregation, multiple rows
            sb.append("Query returned ").append(rows).append(" results. ");
        } else {
            // List of items (e.g., "show all merchants")
            sb.append("Found **").append(rows).append("** records. ");
            if (rows > 100) sb.append("Showing first 100. ");
        }

        return sb.toString().trim();
    }

    private String friendlyName(String col) {
        return col.replace("_", " ").replace("txn ", "transaction ").replace("msf", "MSF revenue").replace("store base currency amount", "volume");
    }

    private String fmtVal(Object v) {
        if (v == null) return "0";
        if (v instanceof Number) return formatNumber(((Number) v).doubleValue());
        return v.toString();
    }

    private String formatNumber(double v) {
        if (Math.abs(v) >= 1e9) return String.format("%.2fB", v/1e9);
        if (Math.abs(v) >= 1e6) return String.format("%.2fM", v/1e6);
        if (Math.abs(v) >= 1e3) return String.format("%,.0f", v);
        if (v == Math.floor(v)) return String.format("%.0f", v);
        return String.format("%.2f", v);
    }

    // ═══════════════════════════════════════════════════════════════
    // OLLAMA CALL — with adjustable temperature for retries
    // ═══════════════════════════════════════════════════════════════
    private String callOllama(String prompt, String model, double temperature) throws Exception {
        long t0 = System.currentTimeMillis();
        Map<String,Object> body = new HashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("stream", false);
        body.put("options", Map.of("temperature", temperature, "num_predict", 400, "num_ctx", 4096, "top_p", 0.9, "repeat_penalty", 1.15));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.postForEntity(ollamaBaseUrl+"/api/generate", new HttpEntity<>(body, headers), String.class);

        long dur = System.currentTimeMillis() - t0;
        JsonNode node = objectMapper.readTree(resp.getBody());
        if (node.has("response")) {
            String r = node.get("response").asText();
            logger.info("Ollama: {}ms, temp={}, len={}", dur, temperature, r.length());
            return r;
        }
        throw new RuntimeException("No response from Ollama");
    }

    private String cleanSql(String raw) {
        String sql = raw.trim();
        sql = sql.replaceAll("```sql\\s*","").replaceAll("```\\s*","");
        // Remove any leading text before SELECT
        int sel = sql.toUpperCase().indexOf("SELECT");
        if (sel > 0) sql = sql.substring(sel);
        sql = sql.strip();
        if (sql.endsWith(";")) sql = sql.substring(0,sql.length()-1).strip();
        // Extract first SELECT statement
        Pattern p = Pattern.compile("(?i)(SELECT\\s.+?)(?:;|$)", Pattern.DOTALL);
        Matcher m = p.matcher(sql);
        if (m.find()) sql = m.group(1).strip();
        return sql;
    }

    private String validateSql(String sql, Long tenantId) {
        if (sql == null || sql.isEmpty()) return "No SQL generated";
        String upper = sql.toUpperCase().replaceAll("\\s+"," ");
        if (!upper.startsWith("SELECT")) return "Only SELECT queries allowed";
        for (String b : BLOCKED_KEYWORDS) {
            if (b.equals("EXEC")||b.equals("EXECUTE")||b.equals("CALL")||b.equals("MERGE")) continue;
            if (upper.matches(".*\\b"+b+"\\b.*")) return "Blocked keyword: "+b;
        }
        if (ALLOWED_TABLES.stream().noneMatch(t -> upper.contains(t.toUpperCase()))) return "Must use allowed tables";
        if (!upper.contains("TENANT_ID")) return "Missing tenant isolation";
        return null;
    }

    public static class AskRequest {
        private String question;
        private String model;
        public String getQuestion() { return question; }
        public void setQuestion(String q) { this.question = q; }
        public String getModel() { return model; }
        public void setModel(String m) { this.model = m; }
    }
}
