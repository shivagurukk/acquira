package com.acquira.service;

import com.acquira.model.DataSourceConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class UniversalDatabaseClient {

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

        try (Connection conn = DriverManager.getConnection(url, config.getUsername(), config.getEncryptedPassword()); // TODO:
                                                                                                                      // Decrypt
                                                                                                                      // password
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
     * Prepares Statement with Parameter injection (Basic implementation).
     * Ideally, use NamedParameterJdbcTemplate, but raw JDBC allows dynamic URLs
     * easily.
     */
    private PreparedStatement prepareStatement(Connection conn, String sql, Map<String, Object> params)
            throws SQLException {
        // NOTE: For simplicity, assuming SQL uses '?' and params are passed in order,
        // OR using a simple regex to replace :paramName with ?.
        // Real-world: Use Spring's NamedParameterUtils.

        // Simple Named Param Parser
        List<Object> values = new ArrayList<>();
        String parsedSql = sql;

        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String placeholder = ":" + entry.getKey();
            if (parsedSql.contains(placeholder)) {
                parsedSql = parsedSql.replace(placeholder, "?");
                values.add(entry.getValue());
            }
        }

        PreparedStatement ps = conn.prepareStatement(parsedSql);
        for (int i = 0; i < values.size(); i++) {
            ps.setObject(i + 1, values.get(i));
        }
        return ps;
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
                config.getEncryptedPassword())) {
            return conn.isValid(5);
        } catch (SQLException e) {
            log.error("Test connection failed: {}", e.getMessage());
            return false;
        }
    }
}
