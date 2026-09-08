package com.acquira.batch.service;

import com.acquira.common.model.DataSourceConfig;
import com.acquira.common.service.CryptoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class UniversalDatabaseClient {

    // P0 fix: decrypt stored password before opening JDBC connection.
    private final CryptoService cryptoService;

    /**
     * Hard cap on rows pulled in one call. mapResultSet materialises every row
     * into a HashMap in heap, so without this a mistyped query against a
     * customer's core-banking table is an OOM of the Acquira JVM, not a slow
     * query. Mirrors IntegrationPullService.MAX_PULL_ROWS.
     */
    private static final int MAX_ROWS = 2_000_000;

    /** Rows fetched per network round trip — without this some drivers buffer the whole result. */
    private static final int FETCH_SIZE = 5_000;

    /** Per-statement ceiling; a runaway external query must not pin this thread forever. */
    private static final int QUERY_TIMEOUT_SECONDS = 300;

    /**
     * Executes a query against any supported DB (Oracle, Postgres, MSSQL).
     *
     * @param config The DB connection details.
     * @param sql    The SQL query to execute.
     * @param params Parameter map (e.g. "dateFrom" -> LocalDate).
     * @return List of Maps (Rows).
     */
    public List<Map<String, Object>> executeQuery(DataSourceConfig config, String sql, Map<String, Object> params) {
        // SECURITY: this client executes SQL supplied by an operator (backfill
        // sourceQueries, report_query_config.sql_text) against a THIRD-PARTY
        // production database. It previously had none of the guards its sibling
        // IntegrationPullService already applied, so a stacked payload like
        // "SELECT 1; DROP TABLE ..." would have run writes/DDL on the customer's
        // system. Reject stacked statements before we connect.
        assertSingleStatement(sql);

        String url = config.getJdbcUrl();
        log.info("Connecting to {} ({})", config.getName(), config.getDbType());

        try (Connection conn = DriverManager.getConnection(url, config.getUsername(),
                    cryptoService.decrypt(config.getEncryptedPassword()))) {

            // Advisory on Postgres, no-op on Oracle/MSSQL — worth setting, but
            // it is NOT the control: the source account must be read-only.
            try {
                conn.setReadOnly(true);
            } catch (SQLException ignored) {
                log.debug("Driver rejected setReadOnly for {} — relying on the source account's grants",
                        config.getDbType());
            }

            try (PreparedStatement ps = prepareStatement(conn, sql, params)) {
                ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                ps.setMaxRows(MAX_ROWS);
                ps.setFetchSize(FETCH_SIZE);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Map<String, Object>> rows = mapResultSet(rs);
                    if (rows.size() >= MAX_ROWS) {
                        log.warn("Query against {} hit the {}-row cap — result is TRUNCATED. "
                                + "Narrow the query's date range.", config.getName(), MAX_ROWS);
                    }
                    return rows;
                }
            }

        } catch (SQLException e) {
            log.error("Execution failed for {}: {}", config.getName(), e.getMessage());
            throw new RuntimeException("DB Connection Error: " + e.getMessage(), e);
        }
    }

    /**
     * Reject multi-statement (stacked) SQL. Strips one optional trailing
     * semicolon, then fails if any ';' remains. Pragmatic guard, not a SQL
     * parser (a ';' inside a string literal is a false positive — rare in a
     * read query) — defense-in-depth on top of a read-only source account.
     * Mirrors IntegrationPullService.assertSingleStatement.
     */
    private void assertSingleStatement(String sql) {
        if (sql == null) return;
        String trimmed = sql.strip();
        if (trimmed.endsWith(";")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (trimmed.contains(";")) {
            throw new IllegalArgumentException(
                "Source query must be a single statement (stacked ';'-separated statements are not allowed).");
        }
    }

    /**
     * Prepares a statement binding ":name" placeholders SAFELY.
     *
     * SECURITY: the previous version did sql.replace(":"+key, "?") while iterating
     * a HashMap, which has no word boundaries (":date" clobbers ":dateFrom") and
     * binds in undefined order, so values could land in the wrong "?" slot.
     * NamedParamBinder uses Spring's NamedParameterUtils to parse the SQL once and
     * bind by name in correct positional order. Values are always bound as JDBC
     * parameters (never concatenated), so they can't break out of their slot.
     */
    private PreparedStatement prepareStatement(Connection conn, String sql, Map<String, Object> params)
            throws SQLException {
        return NamedParamBinder.prepare(conn, sql, params);
    }

    private List<Map<String, Object>> mapResultSet(ResultSet rs) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();

        while (rs.next()) {
            Map<String, Object> row = new HashMap<>();
            for (int i = 1; i <= colCount; i++) {
                row.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    public boolean testConnection(DataSourceConfig config) {
        try (Connection conn = DriverManager.getConnection(config.getJdbcUrl(), config.getUsername(),
                cryptoService.decrypt(config.getEncryptedPassword()))) {
            return conn.isValid(5);
        } catch (SQLException e) {
            log.error("Test connection failed: {}", e.getMessage());
            return false;
        }
    }
}
