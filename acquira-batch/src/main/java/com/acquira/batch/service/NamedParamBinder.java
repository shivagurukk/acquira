package com.acquira.batch.service;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;
import org.springframework.jdbc.core.namedparam.ParsedSql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Safe named-parameter binding for raw-JDBC queries against external databases.
 *
 * WHY THIS EXISTS (security + correctness):
 *   The previous inline approach did {@code sql.replace(":" + key, "?")} while
 *   iterating a HashMap. That had three defects:
 *     1. No word boundaries — ":date" also clobbers ":dateFrom"/":dateTo".
 *     2. HashMap iteration order is undefined, so the order values were added to
 *        the bind list did NOT necessarily match the left-to-right order the "?"
 *        appear in the final SQL → parameters could bind to the wrong columns.
 *     3. Prefix collisions could corrupt the statement entirely.
 *   Wrong-slot binding is both a correctness bug and a security problem (a value
 *   can land where it was never meant to).
 *
 *   Spring's {@link NamedParameterUtils} parses the SQL ONCE, expands ":name"
 *   placeholders to "?" in their true textual order, ignoring occurrences inside
 *   string literals / comments / casts (e.g. PostgreSQL "::text"), and returns
 *   the bind values in the matching order. This is the same machinery
 *   NamedParameterJdbcTemplate uses; we apply it directly because these queries
 *   run over arbitrary external JDBC connections, not the app DataSource.
 */
final class NamedParamBinder {

    private NamedParamBinder() {}

    /**
     * Parse {@code sql} (which may contain ":name" placeholders), bind the values
     * from {@code params} by name in correct positional order, and return a ready
     * PreparedStatement. The caller owns/closes the returned statement.
     */
    static PreparedStatement prepare(Connection conn, String sql, Map<String, Object> params)
            throws SQLException {
        SqlParameterSource source = new MapSqlParameterSource(params);
        ParsedSql parsed = NamedParameterUtils.parseSqlStatement(sql);

        // SQL with ":name" rewritten to "?" in correct textual order.
        String jdbcSql = NamedParameterUtils.substituteNamedParameters(parsed, source);
        // Bind values in the exact order the "?" now appear.
        Object[] bindValues = NamedParameterUtils.buildValueArray(parsed, source, null);

        PreparedStatement ps = conn.prepareStatement(jdbcSql);
        for (int i = 0; i < bindValues.length; i++) {
            ps.setObject(i + 1, bindValues[i]);
        }
        return ps;
    }
}
