package com.acquira.ai.service;

import com.acquira.common.config.TenantContext;
import com.acquira.common.model.AiChatHistory;
import com.acquira.common.model.User;
import com.acquira.common.repository.AiChatHistoryRepository;
import com.acquira.common.repository.UserRepository;
import com.acquira.ai.provider.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NL-to-SQL query service for the AI Assistant.
 *
 * Why this is a @Service (not inline in the controller):
 *  - TenantAspect advises com.acquira..service..* — running JDBC here means
 *    set_config('app.current_tenant', ...) is applied on the connection, so
 *    RLS covers AI queries too (the controller path never set it). App-layer
 *    WHERE tenant_id = ? is still emitted as the primary guard.
 *
 * Grounding: WAREHOUSE tables (sum_daily_* / dim_merchant), per the project's
 * data-sourcing rules — NOT the staging tables, which hold only the last upload.
 * This makes "monthly trend", "last 6 months" etc. actually historical.
 *
 * DATA-BOUNDS ANCHORING: relative-date questions ("this month", "today") are
 * anchored to the tenant's latest loaded business_date, not the wall clock.
 * Otherwise a tenant whose newest upload is May gets zero rows for "this
 * month" asked in July — a correct query over an empty window, perceived as
 * a model failure. The anchor is injected into the prompt as a date literal;
 * CURRENT_DATE is used only when the tenant has no data at all.
 *
 * MULTI-PROVIDER ROUTING: all ModelProvider beans are injected; the model
 * string may be provider-qualified ("anthropic/claude-sonnet-4-5",
 * "ollama/llama3.2"). Unqualified names route to the default (@Primary)
 * provider selected by ai.provider, so existing clients keep working.
 * /models merges every configured provider's list (qualified names), letting
 * the UI offer Ollama and Claude side by side with zero frontend changes.
 *
 * Guardrails:
 *  - Generated SQL is wrapped as SELECT * FROM ( <gen> ) q LIMIT N — a hard cap
 *    that can't be defeated by the model omitting LIMIT.
 *  - Execution runs in a short transaction with SET LOCAL statement_timeout so a
 *    pathological join can't pin a pooled connection (core sets statement_timeout=0
 *    globally for batch ingestion; SET LOCAL overrides it for this txn only).
 *  - Every ask is persisted to ai_chat_history (audit trail + recent list).
 */
@Service
public class AiQueryService {

    private static final Logger logger = LoggerFactory.getLogger(AiQueryService.class);

    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;
    private final AiChatHistoryRepository historyRepository;
    /** The @Primary provider selected by ai.provider — used for unqualified model names. */
    private final ModelProvider defaultProvider;
    /** All providers keyed by id (ollama/anthropic/openai/gemini), deduped. */
    private final Map<String, ModelProvider> providersById;

    @Value("${ai.query.row-limit:1000}")
    private int rowLimit;

    @Value("${ai.query.timeout-ms:15000}")
    private int statementTimeoutMs;

    // Only the read-only warehouse/dim tables the assistant is allowed to touch.
    private static final Set<String> ALLOWED_TABLES = Set.of(
        "sum_daily_bank", "sum_monthly_bank",
        "sum_daily_merchant", "sum_daily_insight",
        "sum_daily_scheme", "sum_daily_channel", "sum_daily_terminal", "sum_daily_mcc",
        "sum_daily_merchant_destination",
        "dim_merchant", "dim_store", "dim_terminal"
    );

    private static final Set<String> BLOCKED_KEYWORDS = Set.of(
        "INSERT", "UPDATE", "DELETE", "DROP", "ALTER", "CREATE", "TRUNCATE",
        "GRANT", "REVOKE", "COPY", "VACUUM", "ANALYZE",
        // Write paths the original list missed. SELECT ... INTO creates a table;
        // CALL/DO/EXECUTE reach procedural code that can write; SET can change
        // role or session state. The read-only transaction in executeGuarded is
        // the actual enforcement — these keep the failure at validation time
        // with a clear message instead of a SQLException.
        "MERGE", "INTO", "CALL", "DO", "EXECUTE", "PREPARE",
        "REFRESH", "REINDEX", "LOCK", "NOTIFY", "SET", "RESET"
    );

    // Tokens that have no place in a governed analytics read — block outright.
    private static final Set<String> BLOCKED_TOKENS = Set.of(
        "PG_SLEEP", "INFORMATION_SCHEMA", "PG_CATALOG", "PG_READ_FILE",
        "DBLINK", "PG_SETTINGS", "CURRENT_SETTING", "SET_CONFIG", "PG_TERMINATE"
    );

    // Identifier immediately following FROM or JOIN — used to validate that EVERY
    // referenced table is whitelisted (not merely that one whitelisted name appears).
    private static final Pattern TABLE_REF_PATTERN =
        Pattern.compile("(?i)\\b(?:FROM|JOIN)\\s+([a-zA-Z_][a-zA-Z0-9_\\.]*)");

    // First FROM target + optional alias — used to alias-qualify the injected
    // tenant predicate so it isn't ambiguous on join queries.
    private static final Pattern FROM_ALIAS_PATTERN =
        Pattern.compile("(?i)\\bFROM\\s+([a-zA-Z_][a-zA-Z0-9_]*)(?:\\s+(?:AS\\s+)?([a-zA-Z_][a-zA-Z0-9_]*))?");

    // Words that can follow "FROM table" without being an alias.
    private static final Set<String> NON_ALIAS_KEYWORDS = Set.of(
        "WHERE", "GROUP", "ORDER", "LIMIT", "HAVING", "UNION", "JOIN",
        "LEFT", "RIGHT", "INNER", "FULL", "CROSS", "ON", "AND", "OR"
    );

    // ══════════════════════════════════════════════════════════════════
    // SCHEMA + TRAINING PROMPT — grounded on the WAREHOUSE (historical).
    // Kept compact so it fits the model context (num_ctx below).
    // {TENANT_ID}  -> the tenant id literal.
    // {ANCHOR}     -> date literal of the tenant's latest loaded business_date
    //                 (e.g. '2026-05-31'::date), or CURRENT_DATE if none.
    // ══════════════════════════════════════════════════════════════════
    private static final String SCHEMA_CONTEXT = """
You are a PostgreSQL SELECT generator for a multi-tenant card-acquiring analytics warehouse.
RESPOND WITH ONLY ONE RAW SQL SELECT. No markdown, no backticks, no prose.

=== TABLES (pre-aggregated daily warehouse; each row is already one day) ===

sum_daily_bank  -- bank-wide daily totals (UNFILTERED KPIs/trends)
  tenant_id, business_date DATE, total_txns, total_volume (cardholder ccy),
  total_base_volume (SETTLEMENT ccy), total_msf, total_interchange,
  total_scheme_fee, total_vat, total_net_revenue

sum_monthly_bank  -- bank-wide monthly totals
  tenant_id, month_key INT (YYYYMM), total_txns, total_volume,
  total_base_volume, total_msf, total_interchange, total_scheme_fee,
  total_vat, total_net_revenue

sum_daily_merchant  -- per-merchant per-day (leaderboards, per-merchant views)
  tenant_id, business_date DATE, merchant_id, total_txns,
  total_volume (cardholder ccy), total_base_volume (SETTLEMENT, single-ccy — USE THIS FOR RANKING),
  total_msf, total_interchange, total_scheme_fee, total_margin,
  total_credit_volume, total_debit_prepaid_volume, sales_user_id (for RM attribution prefer joining dim_merchant.sales_user_id — the authoritative current assignment),
  dcc_eligible_volume, dcc_optin_volume, dcc_optout_volume

sum_daily_merchant_destination  -- per-merchant per-day DOMESTIC/INTERNATIONAL split WITH real fees (settlement ccy)
  tenant_id, business_date DATE, merchant_id, destination ('DOMESTIC'|'INTERNATIONAL'),
  total_txns, total_volume (settlement), total_msf, total_interchange,
  total_scheme_fee, total_ecom_fee, total_net_revenue
  -- USE THIS for domestic vs international questions at merchant grain or when fees per destination are needed.

sum_daily_insight  -- dimensional cross-tab (card scheme/type/destination/channel)
  tenant_id, business_date DATE, merchant_id, store_id, terminal_id,
  card_scheme, card_type, destination, channel, is_opt_in BOOLEAN,
  total_txns, total_volume (cardholder ccy), total_msf
  -- NOTE: has NO interchange/scheme/vat columns.

sum_daily_mcc  -- per-MCC (industry category) per-scheme daily rollup
  tenant_id, business_date DATE, mcc, card_scheme, total_txns, total_volume,
  total_msf, total_scheme_fee, total_net_revenue
  -- USE THIS for "by MCC" / "by industry category" rollups (NOT dim_merchant.mcc).

sum_daily_scheme   -- tenant_id, business_date, card_scheme, total_txns, total_volume, total_msf, total_interchange, total_scheme_fee, total_net_revenue
sum_daily_channel  -- tenant_id, business_date, channel, total_txns, total_volume, total_msf, total_interchange, total_scheme_fee, total_net_revenue
sum_daily_terminal -- tenant_id, business_date, merchant_id, store_id, terminal_id, total_txns, total_volume, total_msf, total_revenue

dim_merchant  -- merchant master (join on merchant_id)
  merchant_id, tenant_id, mid, name, status, city, mcc, industry,
  risk_level, sales_user_id, sales_email, referral_partner, created_date
  -- NOTE: the canonical MCC lives on dim_store.mcc; dim_merchant.mcc can be sparse.
dim_store     -- store_id, tenant_id, merchant_id, sid, name, city, mcc, status
dim_terminal  -- terminal_id, tenant_id, store_id, tid, status

=== RULES ===
1. ALWAYS include WHERE tenant_id = {TENANT_ID} (on the base table; if joining dims, also dX.tenant_id = base.tenant_id).
2. SELECT only. Never write. Only the tables above.
3. Volume/amount -> total_volume; ranking/leaderboard/settlement -> total_base_volume (sum_daily_merchant / sum_daily_bank).
4. Revenue/fees -> total_msf; net margin (also called net revenue) -> total_net_revenue (bank/scheme/channel/mcc/destination tables).
5. Count of transactions -> SUM(total_txns) (rows are pre-aggregated; do NOT COUNT(*)).
6. Distinct merchants -> COUNT(DISTINCT merchant_id).
7. Wrap sums in COALESCE(SUM(x),0).
8. Merchant name/city live in dim_merchant -> join sum_daily_merchant m ON d.merchant_id = m.merchant_id AND d.tenant_id = m.tenant_id.
9. When joining, prefix every column with its table alias. GROUP BY every non-aggregated SELECT column.
10. MCC/industry rollups -> sum_daily_mcc. Domestic vs international -> sum_daily_merchant_destination (merchant grain / fees) or sum_daily_insight.destination (card-dimension cross-tab).

=== DATES (business_date is a DATE; anchor = latest loaded data date) ===
The latest loaded business date for this tenant is {ANCHOR}. There is NO data after it.
Interpret "today", "this month", "this year", "recent" relative to {ANCHOR}, not the wall clock.
- This month: business_date >= DATE_TRUNC('month', {ANCHOR})::date AND business_date <= {ANCHOR}
- Last month (calendar): business_date >= DATE_TRUNC('month', {ANCHOR} - INTERVAL '1 month')::date AND business_date < DATE_TRUNC('month', {ANCHOR})::date
- This year: business_date >= DATE_TRUNC('year', {ANCHOR})::date AND business_date <= {ANCHOR}
- Last year (calendar): business_date >= DATE_TRUNC('year', {ANCHOR} - INTERVAL '1 year')::date AND business_date < DATE_TRUNC('year', {ANCHOR})::date
- Last N days (rolling): business_date >= {ANCHOR} - INTERVAL 'N days' AND business_date <= {ANCHOR}
- Specific month: business_date >= '2026-01-01'::date AND business_date < '2026-02-01'::date
- Today / latest day: business_date = {ANCHOR}
- Month grouping: TO_CHAR(business_date, 'YYYY-MM') AS month
- Never use NOW(). Always cast literals ::date.

=== EXAMPLES ===

Q: Total volume and revenue this month
SELECT COALESCE(SUM(total_volume),0) AS total_volume, COALESCE(SUM(total_msf),0) AS total_msf, COALESCE(SUM(total_txns),0) AS txn_count FROM sum_daily_bank WHERE tenant_id = {TENANT_ID} AND business_date >= DATE_TRUNC('month', {ANCHOR})::date AND business_date <= {ANCHOR}

Q: Monthly volume trend last 6 months
SELECT TO_CHAR(business_date, 'YYYY-MM') AS month, COALESCE(SUM(total_volume),0) AS total_volume, COALESCE(SUM(total_msf),0) AS total_msf, COALESCE(SUM(total_txns),0) AS txn_count FROM sum_daily_bank WHERE tenant_id = {TENANT_ID} AND business_date >= (DATE_TRUNC('month', {ANCHOR}) - INTERVAL '5 months')::date GROUP BY TO_CHAR(business_date, 'YYYY-MM') ORDER BY month

Q: Top 10 merchants by volume
SELECT d.merchant_id, m.name AS merchant_name, COALESCE(SUM(d.total_base_volume),0) AS total_volume, COALESCE(SUM(d.total_msf),0) AS total_msf FROM sum_daily_merchant d JOIN dim_merchant m ON d.merchant_id = m.merchant_id AND d.tenant_id = m.tenant_id WHERE d.tenant_id = {TENANT_ID} GROUP BY d.merchant_id, m.name ORDER BY total_volume DESC LIMIT 10

Q: Volume by card scheme
SELECT card_scheme, COALESCE(SUM(total_volume),0) AS total_volume, COALESCE(SUM(total_msf),0) AS total_msf, COALESCE(SUM(total_txns),0) AS txn_count FROM sum_daily_scheme WHERE tenant_id = {TENANT_ID} AND card_scheme IS NOT NULL GROUP BY card_scheme ORDER BY total_volume DESC

Q: Volume by MCC this year
SELECT mcc, COALESCE(SUM(total_volume),0) AS total_volume, COALESCE(SUM(total_msf),0) AS total_msf, COALESCE(SUM(total_txns),0) AS txn_count FROM sum_daily_mcc WHERE tenant_id = {TENANT_ID} AND business_date >= DATE_TRUNC('year', {ANCHOR})::date GROUP BY mcc ORDER BY total_volume DESC

Q: Domestic vs international volume and net margin by merchant last month
SELECT d.merchant_id, m.name AS merchant_name, d.destination, COALESCE(SUM(d.total_volume),0) AS total_volume, COALESCE(SUM(d.total_net_revenue),0) AS net_revenue FROM sum_daily_merchant_destination d JOIN dim_merchant m ON d.merchant_id = m.merchant_id AND d.tenant_id = m.tenant_id WHERE d.tenant_id = {TENANT_ID} AND d.business_date >= DATE_TRUNC('month', {ANCHOR} - INTERVAL '1 month')::date AND d.business_date < DATE_TRUNC('month', {ANCHOR})::date GROUP BY d.merchant_id, m.name, d.destination ORDER BY total_volume DESC LIMIT 50

Q: Local vs international volume
SELECT destination, COALESCE(SUM(total_volume),0) AS total_volume, COALESCE(SUM(total_txns),0) AS txn_count FROM sum_daily_merchant_destination WHERE tenant_id = {TENANT_ID} GROUP BY destination ORDER BY total_volume DESC

Q: Active merchants by city
SELECT city, COUNT(DISTINCT merchant_id) AS merchant_count FROM dim_merchant WHERE tenant_id = {TENANT_ID} AND status = 'Active' AND city IS NOT NULL GROUP BY city ORDER BY merchant_count DESC

Q: Net margin by scheme this year
SELECT card_scheme, COALESCE(SUM(total_net_revenue),0) AS net_revenue, COALESCE(SUM(total_volume),0) AS total_volume FROM sum_daily_scheme WHERE tenant_id = {TENANT_ID} AND business_date >= DATE_TRUNC('year', {ANCHOR})::date GROUP BY card_scheme ORDER BY net_revenue DESC

Q: Daily volume last 30 days
SELECT business_date, COALESCE(SUM(total_volume),0) AS total_volume, COALESCE(SUM(total_txns),0) AS txn_count FROM sum_daily_bank WHERE tenant_id = {TENANT_ID} AND business_date >= {ANCHOR} - INTERVAL '30 days' AND business_date <= {ANCHOR} GROUP BY business_date ORDER BY business_date

Q: Card type breakdown
SELECT card_type, COALESCE(SUM(total_volume),0) AS total_volume, COALESCE(SUM(total_txns),0) AS txn_count FROM sum_daily_insight WHERE tenant_id = {TENANT_ID} AND card_type IS NOT NULL GROUP BY card_type ORDER BY total_volume DESC

Q: Top sales reps by volume
SELECT m.sales_user_id, COALESCE(SUM(d.total_base_volume),0) AS total_volume, COALESCE(SUM(d.total_msf),0) AS total_msf FROM sum_daily_merchant d JOIN dim_merchant m ON m.merchant_id = d.merchant_id AND m.tenant_id = d.tenant_id WHERE d.tenant_id = {TENANT_ID} AND m.sales_user_id IS NOT NULL GROUP BY m.sales_user_id ORDER BY total_volume DESC LIMIT 20

Q: How many merchants by status
SELECT status, COUNT(*) AS merchant_count FROM dim_merchant WHERE tenant_id = {TENANT_ID} GROUP BY status ORDER BY merchant_count DESC

Q: High risk merchants
SELECT mid, name, city, industry, status, risk_level FROM dim_merchant WHERE tenant_id = {TENANT_ID} AND risk_level = 'High' ORDER BY name LIMIT 500

Q: Volume last year vs this year by month
SELECT TO_CHAR(business_date, 'YYYY-MM') AS month, COALESCE(SUM(total_volume),0) AS total_volume FROM sum_daily_bank WHERE tenant_id = {TENANT_ID} AND business_date >= DATE_TRUNC('year', {ANCHOR} - INTERVAL '1 year')::date GROUP BY TO_CHAR(business_date, 'YYYY-MM') ORDER BY month

=== NOW GENERATE SQL ===
""";

    public AiQueryService(JdbcTemplate jdbcTemplate,
                          UserRepository userRepository,
                          AiChatHistoryRepository historyRepository,
                          ModelProvider modelProvider,
                          List<ModelProvider> allProviders) {
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
        this.historyRepository = historyRepository;
        this.defaultProvider = modelProvider; // @Primary — selected by ai.provider
        // Dedupe by id: the @Primary bean is the same instance as one of the
        // @Component providers, so the injected list can contain it twice.
        Map<String, ModelProvider> byId = new LinkedHashMap<>();
        for (ModelProvider p : allProviders) byId.putIfAbsent(p.id().toLowerCase(), p);
        this.providersById = byId;
    }

    // ═══════════════════════════════════════════════════════════════
    // PROVIDER ROUTING — "provider/model" strings pick a provider; bare
    // model names go to the default (@Primary) provider.
    // ═══════════════════════════════════════════════════════════════
    private ModelProvider resolveProvider(String requestedModel) {
        if (requestedModel != null && requestedModel.contains("/")) {
            String prefix = requestedModel.substring(0, requestedModel.indexOf('/')).trim().toLowerCase();
            ModelProvider p = providersById.get(prefix);
            if (p != null) return p;
        }
        return defaultProvider;
    }

    /** Strips a recognized "provider/" prefix so the provider gets a bare model name. */
    private String resolveModelName(String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) return null;
        if (requestedModel.contains("/")) {
            String prefix = requestedModel.substring(0, requestedModel.indexOf('/')).trim().toLowerCase();
            if (providersById.containsKey(prefix)) {
                String bare = requestedModel.substring(requestedModel.indexOf('/') + 1).trim();
                return bare.isEmpty() ? null : bare;
            }
        }
        return requestedModel;
    }

    // ═══════════════════════════════════════════════════════════════
    // HEALTH / MODELS — reports the default provider plus every other
    // configured provider (multi-provider aware).
    // ═══════════════════════════════════════════════════════════════
    public Map<String, Object> health() {
        boolean healthy = false;
        try { healthy = defaultProvider.isHealthy(); } catch (Exception ignore) { }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", healthy ? "connected" : "disconnected");
        out.put("provider", defaultProvider.id());
        out.put("model", defaultProvider.defaultModel());
        out.put("availableModels", safeModels(defaultProvider));
        List<Map<String, Object>> providers = new ArrayList<>();
        for (ModelProvider p : providersById.values()) {
            boolean h = false;
            try { h = p.isHealthy(); } catch (Exception ignore) { }
            Map<String, Object> pv = new LinkedHashMap<>();
            pv.put("id", p.id());
            pv.put("healthy", h);
            pv.put("defaultModel", p.defaultModel());
            pv.put("isDefault", p.id().equals(defaultProvider.id()));
            providers.add(pv);
        }
        out.put("providers", providers);
        if (!healthy) {
            out.put("hint", "ollama".equals(defaultProvider.id())
                ? "Run: ollama serve"
                : "Set ai." + defaultProvider.id() + ".api-key (via acquira.secrets.keys)");
        }
        return out;
    }

    /**
     * Merged model list across every healthy/configured provider. Names are
     * provider-qualified ("ollama/llama3.2", "anthropic/claude-sonnet-4-5") so
     * the same string round-trips through the UI's model picker into
     * resolveProvider() with no frontend changes.
     */
    public List<Map<String, Object>> listModels() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ModelProvider p : providersById.values()) {
            boolean h = false;
            try { h = p.isHealthy(); } catch (Exception ignore) { }
            if (!h) continue;
            for (String name : safeModels(p)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.id() + "/" + name);
                m.put("provider", p.id());
                m.put("model", name);
                result.add(m);
            }
        }
        // Degenerate case: nothing healthy — still expose the default provider's
        // configured list so the UI has something to show alongside the health hint.
        if (result.isEmpty()) {
            for (String name : safeModels(defaultProvider)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", defaultProvider.id() + "/" + name);
                m.put("provider", defaultProvider.id());
                m.put("model", name);
                result.add(m);
            }
        }
        return result;
    }

    private List<String> safeModels(ModelProvider p) {
        try { return p.availableModels(); } catch (Exception e) { return Collections.emptyList(); }
    }

    // ═══════════════════════════════════════════════════════════════
    // DATA-BOUNDS ANCHOR — latest loaded business_date for the tenant.
    // sum_daily_bank is one indexed row per day; MAX() is effectively free.
    // Falls back to sum_daily_insight, then null (-> CURRENT_DATE anchor).
    // ═══════════════════════════════════════════════════════════════
    private java.time.LocalDate latestDataDate(Long tenantId) {
        try {
            java.sql.Date d = jdbcTemplate.queryForObject(
                "SELECT MAX(business_date) FROM sum_daily_bank WHERE tenant_id = ?",
                java.sql.Date.class, tenantId);
            if (d != null) return d.toLocalDate();
        } catch (Exception e) {
            logger.debug("latestDataDate via sum_daily_bank failed: {}", e.getMessage());
        }
        try {
            java.sql.Date d = jdbcTemplate.queryForObject(
                "SELECT MAX(business_date) FROM sum_daily_insight WHERE tenant_id = ?",
                java.sql.Date.class, tenantId);
            if (d != null) return d.toLocalDate();
        } catch (Exception e) {
            logger.debug("latestDataDate via sum_daily_insight failed: {}", e.getMessage());
        }
        return null;
    }

    private String buildPrompt(Long tenantId) {
        java.time.LocalDate maxDate = latestDataDate(tenantId);
        String anchor = maxDate != null ? "'" + maxDate + "'::date" : "CURRENT_DATE";
        return SCHEMA_CONTEXT
            .replace("{TENANT_ID}", String.valueOf(tenantId))
            .replace("{ANCHOR}", anchor);
    }

    // ═══════════════════════════════════════════════════════════════
    // MAIN ASK — generate, validate, execute (guarded), persist
    // ═══════════════════════════════════════════════════════════════
    public Map<String, Object> ask(String question, String requestedModel) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return Map.of("error", "No tenant context");
        if (question == null || question.trim().isEmpty()) return Map.of("error", "Question is required");

        ModelProvider provider = resolveProvider(requestedModel);
        String modelName = resolveModelName(requestedModel);

        long totalStart = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", question);
        result.put("provider", provider.id());

        int maxRetries = 2;
        String lastError = null;
        String finalSql = null;
        String summary = null;
        int rowCount = 0;
        boolean success = false;

        String basePrompt = buildPrompt(tenantId);

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String prompt = basePrompt + "Q: " + question;
                if (attempt > 1 && lastError != null) {
                    prompt += "\n\nPREVIOUS ATTEMPT FAILED: " + lastError + "\nFix and output corrected SQL only:";
                }

                String sqlRaw = provider.generate(prompt, modelName, attempt > 1 ? 0.15 : 0.05);
                String sql = ensureTenant(cleanSql(sqlRaw), tenantId);
                finalSql = sql;
                result.put("generatedSql", sql);

                String valErr = validateSql(sql, tenantId);
                if (valErr != null) {
                    lastError = valErr;
                    logger.warn("[AI attempt {}] validation failed: {}", attempt, valErr);
                    if (attempt < maxRetries) continue;
                    result.put("error", valErr);
                    result.put("safe", false);
                    break;
                }
                result.put("safe", true);

                List<Map<String, Object>> data = executeGuarded(sql);
                rowCount = data.size();
                summary = buildSummary(data);

                result.put("data", data);
                result.put("rowCount", rowCount);
                result.put("columns", data.isEmpty() ? Collections.emptyList()
                    : new ArrayList<>(data.get(0).keySet()));
                result.put("summary", summary);
                result.put("chartHint", chartHint(data));
                lastError = null;
                success = true;
                break;
            } catch (Exception e) {
                lastError = e.getMessage();
                logger.error("[AI attempt {}/{}] failed: {}", attempt, maxRetries, lastError);
                if (attempt >= maxRetries) {
                    result.put("error", friendlyError(lastError));
                }
            }
        }

        long dur = System.currentTimeMillis() - totalStart;
        result.put("duration", dur);
        if (lastError != null && !result.containsKey("error")) result.put("error", friendlyError(lastError));

        persistHistory(tenantId, question, finalSql, summary, rowCount, dur, !success,
            success ? null : lastError);

        return result;
    }

    /** /explain — return the SQL the model would run, without executing it. */
    public Map<String, Object> explain(String question, String requestedModel) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return Map.of("error", "No tenant context");
        try {
            ModelProvider provider = resolveProvider(requestedModel);
            String modelName = resolveModelName(requestedModel);
            String prompt = buildPrompt(tenantId) + "Q: " + question;
            String sql = ensureTenant(cleanSql(provider.generate(prompt, modelName, 0.05)), tenantId);
            return Map.of("question", question, "generatedSql", sql,
                "provider", provider.id(), "safe", validateSql(sql, tenantId) == null);
        } catch (Exception e) {
            return Map.of("error", String.valueOf(e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // GUARDED EXECUTION — per-statement timeout + hard row cap, on ONE
    // connection inside a manual transaction. Done via ConnectionCallback
    // (not @Transactional) to avoid same-bean self-invocation, which would
    // bypass Spring's proxy and silently skip the transaction.
    //
    // SET LOCAL statement_timeout is scoped to this txn only, overriding the
    // global statement_timeout=0 core sets for batch ingestion. TenantAspect
    // advises this service method, so app.current_tenant is set on the session
    // before the connection is used (RLS backstop applies).
    // ═══════════════════════════════════════════════════════════════
    private List<Map<String, Object>> executeGuarded(String sql) {
        // Hard row cap the model can't defeat by omitting LIMIT.
        final String wrapped = "SELECT * FROM ( " + sql + " ) q LIMIT " + rowLimit;
        return jdbcTemplate.execute((java.sql.Connection conn) -> {
            boolean priorAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false); // begin txn so SET LOCAL has scope
            try (java.sql.Statement st = conn.createStatement()) {
                // Postgres itself refuses every write for the life of this txn
                // (SQLSTATE 25006), including writes reached through a VOLATILE
                // function that the keyword blocklist cannot see. This is the
                // enforcement boundary; the Java validation is the early exit.
                st.execute("SET TRANSACTION READ ONLY");
                st.execute("SET LOCAL statement_timeout = " + statementTimeoutMs);
                List<Map<String, Object>> out = new ArrayList<>();
                try (java.sql.ResultSet rs = st.executeQuery(wrapped)) {
                    java.sql.ResultSetMetaData md = rs.getMetaData();
                    int cols = md.getColumnCount();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= cols; i++) {
                            row.put(md.getColumnLabel(i), rs.getObject(i));
                        }
                        out.add(row);
                    }
                }
                conn.commit();
                return out;
            } catch (java.sql.SQLException e) {
                try { conn.rollback(); } catch (java.sql.SQLException ignore) { }
                throw e;
            } finally {
                try { conn.setAutoCommit(priorAutoCommit); } catch (java.sql.SQLException ignore) { }
            }
        });
    }


    // ═══════════════════════════════════════════════════════════════
    // SQL CLEANUP / VALIDATION
    // ═══════════════════════════════════════════════════════════════
    private String cleanSql(String raw) {
        String sql = raw == null ? "" : raw.trim();
        sql = sql.replaceAll("```sql\\s*", "").replaceAll("```\\s*", "");
        int sel = sql.toUpperCase().indexOf("SELECT");
        if (sel > 0) sql = sql.substring(sel);
        sql = sql.strip();
        // Keep only the first statement; drop anything after a semicolon.
        Pattern p = Pattern.compile("(?i)(SELECT\\s.+?)(?:;|$)", Pattern.DOTALL);
        Matcher m = p.matcher(sql);
        if (m.find()) sql = m.group(1).strip();
        if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).strip();
        return sql;
    }

    /**
     * Guarantees a tenant predicate exists; does not trust the model alone.
     * The injected predicate is alias-qualified against the first FROM target
     * so it isn't "column reference is ambiguous" on join queries.
     */
    private String ensureTenant(String sql, Long tenantId) {
        if (sql == null || sql.isEmpty()) return sql;
        String upper = sql.toUpperCase();
        // Only skip injection when a CORRECT caller-tenant predicate is already
        // present. A mere mention of tenant_id (in the SELECT list, GROUP BY, or
        // compared to some OTHER tenant's id) must not suppress it — the old
        // substring test let "break down volume by tenant_id" or "tenant_id = 3"
        // run unscoped. validateSql() additionally rejects foreign-tenant literals.
        if (upper.matches("(?s).*\\bTENANT_ID\\s*=\\s*" + tenantId + "\\b.*")) return sql;

        String qualifier = firstFromQualifier(sql);
        String predicate = (qualifier != null ? qualifier + "." : "") + "tenant_id = " + tenantId;

        // Inject into the first WHERE, else append one before GROUP/ORDER/LIMIT.
        int w = upper.indexOf(" WHERE ");
        if (w >= 0) {
            int after = w + 7;
            return sql.substring(0, after) + predicate + " AND " + sql.substring(after);
        }
        int ins = sql.length();
        for (String kw : new String[]{" GROUP BY", " ORDER BY", " LIMIT", " HAVING"}) {
            int idx = upper.indexOf(kw);
            if (idx >= 0 && idx < ins) ins = idx;
        }
        return sql.substring(0, ins) + " WHERE " + predicate + " " + sql.substring(ins);
    }

    /** Alias of the first FROM target if present, else the table name; null if unparseable. */
    private String firstFromQualifier(String sql) {
        Matcher m = FROM_ALIAS_PATTERN.matcher(sql);
        if (!m.find()) return null;
        String table = m.group(1);
        String alias = m.group(2);
        if (alias != null && !NON_ALIAS_KEYWORDS.contains(alias.toUpperCase())) return alias;
        return table;
    }

    private String validateSql(String sql, Long tenantId) {
        if (sql == null || sql.isEmpty()) return "No SQL generated";
        String upper = sql.toUpperCase().replaceAll("\\s+", " ");
        if (!upper.startsWith("SELECT")) return "Only SELECT queries are allowed";
        if (upper.contains(";")) return "Multiple statements are not allowed";
        // UNION would let a second SELECT ride along without the injected tenant
        // predicate (ensureTenant only scopes the first). The schema prompt never
        // emits UNION, so this only blocks adversarial/hallucinated SQL.
        if (upper.matches(".*\\bUNION\\b.*")) return "UNION is not allowed";
        for (String b : BLOCKED_KEYWORDS) {
            if (upper.matches(".*\\b" + b + "\\b.*")) return "Blocked keyword: " + b;
        }
        for (String t : BLOCKED_TOKENS) {
            if (upper.contains(t)) return "Blocked reference: " + t;
        }
        String tblErr = validateTableReferences(sql);
        if (tblErr != null) return tblErr;
        String tenantErr = validateTenantPredicates(upper, tenantId);
        if (tenantErr != null) return tenantErr;
        return null;
    }

    /**
     * Value-based tenant check — presence of the string "tenant_id" is NOT
     * isolation. Every comparison involving tenant_id must either pin the
     * CALLER's tenant (`tenant_id = <callerId>`) or be a tenant-equality join
     * between two tables (`a.tenant_id = b.tenant_id`). Any other operator
     * (IN/BETWEEN/!=/<...) or any other literal is a cross-tenant probe
     * ("show tenant 3's totals") and is rejected outright. ensureTenant() has
     * already injected the caller predicate when the model omitted it.
     *
     * @param upper the SQL upper-cased with whitespace collapsed
     */
    private String validateTenantPredicates(String upper, Long tenantId) {
        if (!upper.contains("TENANT_ID")) return "Missing tenant isolation";
        // tenant_id with any non-equality operator
        if (Pattern.compile("\\bTENANT_ID\\s+(NOT\\s+IN|IN|BETWEEN|LIKE|IS)\\b").matcher(upper).find()
                || Pattern.compile("\\bTENANT_ID\\s*(!=|<>|<=|>=|<|>)").matcher(upper).find()) {
            return "tenant_id may only be compared with '=' to the caller's tenant";
        }
        // tenant_id = <rhs> — rhs must be the caller's id or another tenant_id column
        Matcher m = Pattern.compile("\\bTENANT_ID\\s*=\\s*([A-Z0-9_]+(?:\\.[A-Z0-9_]+)?|'[^']*')").matcher(upper);
        boolean callerPredicateSeen = false;
        while (m.find()) {
            String rhs = m.group(1);
            if (rhs.matches("\\d+")) {
                if (!rhs.equals(String.valueOf(tenantId)))
                    return "Query pins a different tenant_id — not allowed";
                callerPredicateSeen = true;
            } else if (!rhs.matches("(?:[A-Z_][A-Z0-9_]*\\.)?TENANT_ID")) {
                return "tenant_id may only be compared to the caller's tenant";
            }
        }
        // reversed literal form: <n> = tenant_id
        Matcher r = Pattern.compile("(\\d+)\\s*=\\s*(?:[A-Z_][A-Z0-9_]*\\.)?TENANT_ID\\b").matcher(upper);
        while (r.find()) {
            if (!r.group(1).equals(String.valueOf(tenantId)))
                return "Query pins a different tenant_id — not allowed";
            callerPredicateSeen = true;
        }
        if (!callerPredicateSeen) return "Missing tenant isolation";
        return null;
    }

    /**
     * Enforce that EVERY table referenced after FROM/JOIN is whitelisted, and
     * reject comma-style joins in the FROM clause.
     *
     * The prior check only verified that *at least one* allowed table name
     * appeared as a substring, so a query like
     *   SELECT u.password_hash FROM users u JOIN sum_daily_bank s ON true
     *   WHERE s.tenant_id = 5
     * passed (it contained "sum_daily_bank") while actually reading the
     * non-whitelisted `users` table. The same trick via cross/comma joins or
     * subqueries could pull from `api_key`, `tenant_setting`, `refresh_token`,
     * `password_history`, etc. — none of which carry tenant_id, so the tenant
     * predicate on the analytics table does nothing to isolate them. This
     * validates each referenced table instead.
     *
     * Trade-off: CTEs and comma joins are rejected. The schema prompt never
     * emits either, so this only blocks adversarial/hallucinated SQL.
     */
    private String validateTableReferences(String sql) {
        Matcher tm = TABLE_REF_PATTERN.matcher(sql);
        while (tm.find()) {
            String ref = tm.group(1);
            if (ref == null || ref.isEmpty()) continue; // "FROM (" subquery — inner FROM is scanned too
            if (ref.contains("."))
                return "Schema-qualified tables are not allowed: " + ref;
            if (!ALLOWED_TABLES.contains(ref.toLowerCase()))
                return "Table not allowed: " + ref;
        }
        if (hasCommaJoin(sql))
            return "Comma joins are not allowed; use explicit JOIN";
        return null;
    }

    /** True if a top-level (paren-depth 0) comma appears within the FROM clause. */
    private boolean hasCommaJoin(String sql) {
        String upper = sql.toUpperCase();
        int from = indexOfWord(upper, "FROM", 0);
        if (from < 0) return false;
        int scanStart = from + 4;
        int end = sql.length();
        for (String kw : new String[]{"WHERE", "GROUP", "ORDER", "LIMIT", "HAVING", "UNION"}) {
            int idx = indexOfWord(upper, kw, scanStart);
            if (idx >= 0 && idx < end) end = idx;
        }
        int depth = 0;
        for (int i = scanStart; i < end; i++) {
            char c = sql.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) return true;
        }
        return false;
    }

    /** Word-boundary indexOf so "FROM" doesn't match inside an identifier like FORMAT. */
    private int indexOfWord(String haystackUpper, String word, int fromIndex) {
        int i = fromIndex;
        while ((i = haystackUpper.indexOf(word, i)) >= 0) {
            boolean leftOk = i == 0
                || (!Character.isLetterOrDigit(haystackUpper.charAt(i - 1)) && haystackUpper.charAt(i - 1) != '_');
            int after = i + word.length();
            boolean rightOk = after >= haystackUpper.length()
                || (!Character.isLetterOrDigit(haystackUpper.charAt(after)) && haystackUpper.charAt(after) != '_');
            if (leftOk && rightOk) return i;
            i = after;
        }
        return -1;
    }

    private String friendlyError(String err) {
        if (err == null) return "Unknown error";
        if (err.contains("bad SQL grammar") || err.toLowerCase().contains("syntax"))
            return "The generated query had a syntax issue. Try rephrasing your question.";
        if (err.toLowerCase().contains("timeout") || err.contains("canceling statement"))
            return "That query took too long and was stopped. Try narrowing the date range.";
        if (err.contains("Connection refused") || err.toLowerCase().contains("unauthorized")
                || err.contains("401") || err.contains("403"))
            return "Cannot reach the AI model provider. Check it is running / the API key is configured.";
        return err;
    }

    // ═══════════════════════════════════════════════════════════════
    // SUMMARY + CHART HINT (heuristic, no extra LLM call)
    // ═══════════════════════════════════════════════════════════════
    private String buildSummary(List<Map<String, Object>> data) {
        if (data == null || data.isEmpty()) return "No results found for your query.";
        int rows = data.size();
        List<String> cols = new ArrayList<>(data.get(0).keySet());
        List<String> dims = new ArrayList<>(), meas = new ArrayList<>();
        for (String c : cols) {
            if (data.get(0).get(c) instanceof Number) meas.add(c); else dims.add(c);
        }
        StringBuilder sb = new StringBuilder();

        if (rows == 1 && dims.isEmpty()) {
            sb.append("Here are the results: ");
            for (String mcol : meas) {
                sb.append(friendlyName(mcol)).append(" is **").append(fmtVal(data.get(0).get(mcol))).append("**. ");
            }
            return sb.toString().trim();
        }
        if (!dims.isEmpty() && !meas.isEmpty()) {
            String dimName = friendlyName(dims.get(0));
            String topMeasure = meas.get(0);
            sb.append("Found **").append(rows).append(rows == 1 ? " result" : " results")
              .append("** grouped by ").append(dimName).append(". ");
            Map<String, Object> top = data.get(0);
            Object topLabel = top.get(dims.get(0));
            Object topVal = top.get(topMeasure);
            if (topLabel != null && topVal != null) {
                sb.append("**").append(topLabel).append("** leads with ").append(friendlyName(topMeasure))
                  .append(" of **").append(fmtVal(topVal)).append("**");
                if (rows >= 2) {
                    Map<String, Object> second = data.get(1);
                    Object secLabel = second.get(dims.get(0));
                    Object secVal = second.get(topMeasure);
                    if (secLabel != null && secVal != null) {
                        sb.append(", followed by **").append(secLabel).append("** at **")
                          .append(fmtVal(secVal)).append("**");
                    }
                }
                sb.append(". ");
            }
            for (String mcol : meas) {
                String name = mcol.toLowerCase();
                if (name.contains("count") || name.contains("volume") || name.contains("msf")
                    || name.contains("revenue") || name.contains("amount") || name.contains("txn")) {
                    double total = data.stream().mapToDouble(r ->
                        r.get(mcol) instanceof Number ? ((Number) r.get(mcol)).doubleValue() : 0).sum();
                    sb.append("Overall ").append(friendlyName(mcol)).append(": **")
                      .append(formatNumber(total)).append("**. ");
                }
            }
        } else {
            sb.append("Found **").append(rows).append("** records. ");
        }
        return sb.toString().trim();
    }

    /** Suggests how the frontend should render the result. */
    private String chartHint(List<Map<String, Object>> data) {
        if (data == null || data.isEmpty()) return "empty";
        List<String> cols = new ArrayList<>(data.get(0).keySet());
        List<String> dims = new ArrayList<>(), meas = new ArrayList<>();
        for (String c : cols) {
            if (data.get(0).get(c) instanceof Number) meas.add(c); else dims.add(c);
        }
        if (data.size() == 1 && dims.isEmpty()) return "kpi";
        if (!dims.isEmpty()) {
            String d = dims.get(0).toLowerCase();
            if (d.contains("month") || d.contains("date") || d.equals("day")) return "timeseries";
            if (!meas.isEmpty()) return "bar";
        }
        return "table";
    }

    private String friendlyName(String col) {
        return col.replace("_", " ")
            .replace("txn ", "transaction ")
            .replace("msf", "MSF revenue")
            .replace("total base volume", "settlement volume")
            .replace("total volume", "volume");
    }

    private String fmtVal(Object v) {
        if (v == null) return "0";
        if (v instanceof Number) return formatNumber(((Number) v).doubleValue());
        return v.toString();
    }

    private String formatNumber(double v) {
        if (Math.abs(v) >= 1e9) return String.format("%.2fB", v / 1e9);
        if (Math.abs(v) >= 1e6) return String.format("%.2fM", v / 1e6);
        if (Math.abs(v) >= 1e3) return String.format("%,.0f", v);
        if (v == Math.floor(v)) return String.format("%.0f", v);
        return String.format("%.2f", v);
    }

    // ═══════════════════════════════════════════════════════════════
    // HISTORY PERSISTENCE — one row per ask, never blocks the response.
    // ═══════════════════════════════════════════════════════════════
    private void persistHistory(Long tenantId, String question, String sql, String summary,
                                int rowCount, long durationMs, boolean isError, String errorMsg) {
        try {
            Long userId = currentUserId();
            if (userId == null) {
                logger.debug("Skipping ai_chat_history: no resolvable user (user_id is NOT NULL)");
                return;
            }
            AiChatHistory h = new AiChatHistory();
            h.setTenantId(tenantId);
            h.setUserId(userId);
            h.setQuestion(truncate(question, 8000));
            h.setGeneratedSql(truncate(sql, 8000));
            h.setSummary(truncate(summary, 4000));
            h.setRowCount(rowCount);
            h.setDurationMs(durationMs);
            h.setIsError(isError);
            h.setErrorMsg(truncate(errorMsg, 4000));
            h.setCreatedAt(LocalDateTime.now());
            historyRepository.save(h);
        } catch (Exception e) {
            // History is best-effort; never fail the user's query over it.
            logger.warn("Failed to persist ai_chat_history (non-critical): {}", e.getMessage());
        }
    }

    public List<AiChatHistory> recentHistory(int limit) {
        Long tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) return Collections.emptyList();
        return historyRepository.findByTenantIdOrderByCreatedAtDesc(
            tenantId, org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, 50))));
    }

    private Long currentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || auth.getName() == null) return null;
            return userRepository.findByUsername(auth.getName())
                .map(User::getId).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
