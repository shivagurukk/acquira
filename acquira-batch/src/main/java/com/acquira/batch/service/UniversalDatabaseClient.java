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
     * Executes a query against any supported DB (Oracle, Postgres, MSSQL).
     * 
     * @param config The DB connection details.
     * @param sql    The SQL query to execute.
     * @param params Parameter map (e.g. "dateFrom" -> LocalDate).
     * @return List of Maps (Rows).
     */
    public List<Map<String, Object>> executeQuery(DataSourceConfig config, String sql, Map<String, Object> params) {
        String url = config.getJdbcUrl();
        log.info("Connecting to {} ({})", config.getName(), config.getDbType());

        try (Connection conn = DriverManager.getConnection(url, config.getUsername(),
                    cryptoService.decrypt(config.getEncryptedPassword()));
                PreparedStatement ps = prepareStatement(conn, sql, params)) {

            try (ResultSet rs = ps.executeQuery()) {
                return mapResultSet(rs);
            }

        } catch (SQLException e) {
            log.error("Execution failed for {}: {}", config.getName(), e.getMessage());
            throw new RuntimeException("DB Connection Error: " + e.getMessage(), e);
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
